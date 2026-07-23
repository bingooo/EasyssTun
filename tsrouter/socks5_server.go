package tsrouter

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"time"
)

type SOCKS5Server struct {
	listenAddr  string // "127.0.0.1:10800"
	listener    net.Listener
	matcher     *IPMatcher
	tsNode      *TSNetNode
	socksClient *SOCKS5Client
	cancel      context.CancelFunc
	wg          sync.WaitGroup
}

func NewSOCKS5Server(listenAddr string, matcher *IPMatcher, tsNode *TSNetNode, socksClient *SOCKS5Client) *SOCKS5Server {
	return &SOCKS5Server{
		listenAddr:  listenAddr,
		matcher:     matcher,
		tsNode:      tsNode,
		socksClient: socksClient,
	}
}

func (s *SOCKS5Server) Start() error {
	l, err := net.Listen("tcp", s.listenAddr)
	if err != nil {
		return fmt.Errorf("socks5 server listen on %s failed: %w", s.listenAddr, err)
	}
	s.listener = l

	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel

	log.Printf("[SOCKS5Server] Listening on %s...", s.listenAddr)

	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		for {
			conn, err := l.Accept()
			if err != nil {
				select {
				case <-ctx.Done():
					return
				default:
					log.Printf("[SOCKS5Server] Accept error: %v", err)
					continue
				}
			}
			if tc, ok := conn.(*net.TCPConn); ok {
				tc.SetNoDelay(true)
			}
			go s.handleConn(ctx, conn)
		}
	}()

	return nil
}

func (s *SOCKS5Server) handleConn(ctx context.Context, clientConn net.Conn) {
	defer clientConn.Close()

	clientConn.SetReadDeadline(time.Now().Add(10 * time.Second))

	// 1. SOCKS5 Handshake
	header := make([]byte, 2)
	if _, err := io.ReadFull(clientConn, header); err != nil {
		return
	}
	if header[0] != 0x05 {
		return
	}
	nmethods := int(header[1])
	methods := make([]byte, nmethods)
	if _, err := io.ReadFull(clientConn, methods); err != nil {
		return
	}
	if _, err := clientConn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. Read Request
	reqHeader := make([]byte, 4)
	if _, err := io.ReadFull(clientConn, reqHeader); err != nil {
		return
	}
	if reqHeader[0] != 0x05 {
		return
	}

	cmd := reqHeader[1]
	atyp := reqHeader[3]

	var host string
	switch atyp {
	case 0x01:
		ipBuf := make([]byte, 4)
		if _, err := io.ReadFull(clientConn, ipBuf); err != nil {
			return
		}
		host = net.IP(ipBuf).String()
	case 0x03:
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(clientConn, lenBuf); err != nil {
			return
		}
		domainBuf := make([]byte, int(lenBuf[0]))
		if _, err := io.ReadFull(clientConn, domainBuf); err != nil {
			return
		}
		host = string(domainBuf)
	case 0x04:
		ipBuf := make([]byte, 16)
		if _, err := io.ReadFull(clientConn, ipBuf); err != nil {
			return
		}
		host = net.IP(ipBuf).String()
	default:
		return
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(clientConn, portBuf); err != nil {
		return
	}
	port := int(portBuf[0])<<8 | int(portBuf[1])
	targetAddr := net.JoinHostPort(host, strconv.Itoa(port))

	clientConn.SetReadDeadline(time.Time{})

	switch cmd {
	case 0x01:
		s.handleConnect(ctx, clientConn, host, targetAddr)
	case 0x03:
		s.handleUDPAssociate(ctx, clientConn)
	default:
		clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	}
}

func (s *SOCKS5Server) handleConnect(ctx context.Context, clientConn net.Conn, host, targetAddr string) {
	isTailnet := s.matcher.IsTailnet(host)

	var targetConn net.Conn
	var err error

	if isTailnet {
		log.Printf("[Router] Target %s -> TAILSCALE", targetAddr)
		if !s.tsNode.IsReady() {
			waitCtx, cancel := context.WithTimeout(ctx, 1*time.Second)
			_ = s.tsNode.WaitReady(waitCtx)
			cancel()
		}
		targetConn, err = s.tsNode.Dial(ctx, "tcp", targetAddr)
	} else if isPrivateHost(host) {
		// Private host (e.g. 192.168.x.x) not yet matched in matcher:
		// Try tsNode first (in case it is an unlisted Subnet route), then fallback to EASYSS.
		log.Printf("[Router] Target %s is private IP, trying TAILSCALE first...", targetAddr)
		targetConn, err = s.tsNode.Dial(ctx, "tcp", targetAddr)
		if err != nil {
			log.Printf("[Router] TAILSCALE dial for private %s failed (%v), falling back to EASYSS", targetAddr, err)
			targetConn, err = s.socksClient.Dial("tcp", targetAddr)
		}
	} else {
		log.Printf("[Router] Target %s -> EASYSS (Public)", targetAddr)
		targetConn, err = s.socksClient.Dial("tcp", targetAddr)
	}

	if err != nil {
		log.Printf("[Router] Connect to %s failed: %v", targetAddr, err)
		clientConn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer targetConn.Close()

	if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	relay(clientConn, targetConn)
}

// handleUDPAssociate relays SOCKS5 UDP frames between client and upstream easyss.
// A single goroutine reads all packets from localUDP and routes by source address:
//   - source == clientAddr  → forward to upstream relay addr
//   - source == upstream    → forward back to clientAddr
func (s *SOCKS5Server) handleUDPAssociate(ctx context.Context, clientConn net.Conn) {
	// 1. Open upstream UDP ASSOCIATE session with easyss
	upstreamTCP, err := net.DialTimeout("tcp", s.socksClient.UpstreamAddr, 10*time.Second)
	if err != nil {
		log.Printf("[UDP] connect upstream failed: %v", err)
		clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer upstreamTCP.Close()

	// Handshake
	if _, err := upstreamTCP.Write([]byte{0x05, 0x01, 0x00}); err != nil ||
		func() bool {
			r := make([]byte, 2)
			io.ReadFull(upstreamTCP, r)
			return r[1] != 0x00
		}() {
		clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Request UDP ASSOCIATE (0.0.0.0:0 = wildcard)
	if _, err := upstreamTCP.Write([]byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	uaReply := make([]byte, 4)
	if _, err := io.ReadFull(upstreamTCP, uaReply); err != nil || uaReply[1] != 0x00 {
		clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Parse upstream UDP relay endpoint
	var upstreamUDPAddr *net.UDPAddr
	switch uaReply[3] {
	case 0x01:
		addrBuf := make([]byte, 6)
		if _, err := io.ReadFull(upstreamTCP, addrBuf); err != nil {
			clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		ip := net.IP(addrBuf[0:4])
		// If upstream returns 0.0.0.0, use the remote TCP address
		if ip.Equal(net.IPv4zero) {
			host, _, _ := net.SplitHostPort(upstreamTCP.RemoteAddr().String())
			ip = net.ParseIP(host)
		}
		upstreamUDPAddr = &net.UDPAddr{IP: ip, Port: int(addrBuf[4])<<8 | int(addrBuf[5])}
	case 0x04:
		addrBuf := make([]byte, 18)
		if _, err := io.ReadFull(upstreamTCP, addrBuf); err != nil {
			clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		upstreamUDPAddr = &net.UDPAddr{IP: net.IP(addrBuf[0:16]), Port: int(addrBuf[16])<<8 | int(addrBuf[17])}
	default:
		clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// 2. Bind local UDP socket
	localUDP, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer localUDP.Close()

	_, portStr, _ := net.SplitHostPort(localUDP.LocalAddr().String())
	localPort, _ := strconv.Atoi(portStr)

	// 3. Reply to hev with our UDP port
	reply := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, byte(localPort >> 8), byte(localPort)}
	if _, err := clientConn.Write(reply); err != nil {
		return
	}

	log.Printf("[UDP] relay: local=127.0.0.1:%d <-> upstream=%s", localPort, upstreamUDPAddr)

	// 4. Single goroutine reads ALL packets from localUDP,
	//    routes by source address: client→upstream or upstream→client
	var (
		mu         sync.Mutex
		clientAddr *net.UDPAddr
	)

	go func() {
		buf := make([]byte, 65535)
		for {
			n, src, err := localUDP.ReadFromUDP(buf)
			if err != nil {
				return
			}

			// Determine direction by source address
			isFromUpstream := src.Port == upstreamUDPAddr.Port &&
				src.IP.Equal(upstreamUDPAddr.IP)

			if isFromUpstream {
				// upstream → client
				mu.Lock()
				ca := clientAddr
				mu.Unlock()
				if ca != nil {
					localUDP.WriteToUDP(buf[:n], ca)
				}
			} else {
				// client → upstream or tsnet
				mu.Lock()
				clientAddr = src
				mu.Unlock()

				// Parse SOCKS5 UDP header: RSV(2) + FRAG(1) + ATYP(1) + ADDR + PORT(2)
				targetHost := extractSocks5UDPTarget(buf[:n])
				if targetHost != "" && s.matcher.IsTailnet(targetHost) {
					// Tailnet UDP packet -> Dial via tsnet in goroutine
					go func(data []byte) {
						s.proxyTailnetUDP(ctx, localUDP, src, data)
					}(append([]byte(nil), buf[:n]...))
				} else {
					// Non-tailnet UDP packet -> forward to Easyss SOCKS5 upstream
					localUDP.WriteToUDP(buf[:n], upstreamUDPAddr)
				}
			}
		}
	}()

	// 5. Keep alive until TCP control conn closes
	tcpBuf := make([]byte, 1)
	for {
		clientConn.SetReadDeadline(time.Now().Add(120 * time.Second))
		_, err := clientConn.Read(tcpBuf)
		if err != nil {
			break
		}
	}
}

var bufPool = sync.Pool{
	New: func() any {
		b := make([]byte, 32*1024)
		return &b
	},
}

func relay(c1, c2 net.Conn) {
	if tc, ok := c1.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	if tc, ok := c2.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		bufPtr := bufPool.Get().(*[]byte)
		defer bufPool.Put(bufPtr)
		_, _ = io.CopyBuffer(c1, c2, *bufPtr)
		_ = c1.SetReadDeadline(time.Now())
	}()

	go func() {
		defer wg.Done()
		bufPtr := bufPool.Get().(*[]byte)
		defer bufPool.Put(bufPtr)
		_, _ = io.CopyBuffer(c2, c1, *bufPtr)
		_ = c2.SetReadDeadline(time.Now())
	}()

	wg.Wait()
}

func (s *SOCKS5Server) Stop() error {
	if s.cancel != nil {
		s.cancel()
	}
	if s.listener != nil {
		s.listener.Close()
	}
	s.wg.Wait()
	return nil
}

// isPrivateHost checks if host IP is a private RFC1918 address.
func isPrivateHost(host string) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsPrivate()
}

// extractSocks5UDPTarget parses the target address from a SOCKS5 UDP request frame header.
func extractSocks5UDPTarget(data []byte) string {
	if len(data) < 10 {
		return ""
	}
	atyp := data[3]
	var host string
	var headerLen int

	switch atyp {
	case 0x01: // IPv4
		if len(data) < 10 {
			return ""
		}
		host = net.IP(data[4:8]).String()
		headerLen = 10
	case 0x03: // Domain
		domainLen := int(data[4])
		if len(data) < 5+domainLen+2 {
			return ""
		}
		host = string(data[5 : 5+domainLen])
		headerLen = 5 + domainLen + 2
	case 0x04: // IPv6
		if len(data) < 22 {
			return ""
		}
		host = net.IP(data[4:20]).String()
		headerLen = 22
	default:
		return ""
	}
	_ = headerLen
	return host
}

// proxyTailnetUDP dials target via tsnet and relays the UDP packet payload back.
func (s *SOCKS5Server) proxyTailnetUDP(ctx context.Context, localUDP *net.UDPConn, clientAddr *net.UDPAddr, frameData []byte) {
	if len(frameData) < 10 {
		return
	}
	atyp := frameData[3]
	var host string
	var port int
	var payloadOffset int

	switch atyp {
	case 0x01:
		host = net.IP(frameData[4:8]).String()
		port = int(frameData[8])<<8 | int(frameData[9])
		payloadOffset = 10
	case 0x03:
		dLen := int(frameData[4])
		host = string(frameData[5 : 5+dLen])
		port = int(frameData[5+dLen])<<8 | int(frameData[5+dLen+1])
		payloadOffset = 5 + dLen + 2
	case 0x04:
		host = net.IP(frameData[4:20]).String()
		port = int(frameData[20])<<8 | int(frameData[21])
		payloadOffset = 22
	default:
		return
	}

	targetAddr := net.JoinHostPort(host, strconv.Itoa(port))
	uConn, err := s.tsNode.Dial(ctx, "udp", targetAddr)
	if err != nil {
		log.Printf("[UDP] Dial tsnet UDP %s failed: %v", targetAddr, err)
		return
	}
	defer uConn.Close()

	// Write payload to tsnet UDP connection
	if _, err := uConn.Write(frameData[payloadOffset:]); err != nil {
		return
	}

	// Read response from tsnet and wrap back into SOCKS5 UDP frame for client
	respBuf := make([]byte, 65535)
	uConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	rn, err := uConn.Read(respBuf)
	if err != nil || rn == 0 {
		return
	}

	// Wrap original SOCKS5 UDP header + response payload
	replyFrame := make([]byte, payloadOffset+rn)
	copy(replyFrame[:payloadOffset], frameData[:payloadOffset])
	copy(replyFrame[payloadOffset:], respBuf[:rn])

	localUDP.WriteToUDP(replyFrame, clientAddr)
}
