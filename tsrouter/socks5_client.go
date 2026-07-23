package tsrouter

import (
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

// SOCKS5Client handles forwarding to an upstream SOCKS5 server (Easyss).
// It maintains a connection pool to avoid per-request TCP+handshake overhead.
type SOCKS5Client struct {
	UpstreamAddr string // e.g., "127.0.0.1:2080"

	poolMu   sync.Mutex
	poolIdle []*poolConn // idle pre-authenticated connections
	poolSize int         // max idle pool size
}

type poolConn struct {
	net.Conn
	createdAt time.Time
}

func NewSOCKS5Client(upstreamAddr string) *SOCKS5Client {
	c := &SOCKS5Client{
		UpstreamAddr: upstreamAddr,
		poolSize:     8,
	}
	// Pre-warm a couple connections
	go func() {
		time.Sleep(200 * time.Millisecond) // wait briefly for easyss to start
		for i := 0; i < 2; i++ {
			if pc, err := c.newIdleConn(); err == nil {
				c.returnConn(pc)
			}
		}
	}()
	return c
}

// newIdleConn creates a new TCP connection to easyss and completes the no-auth SOCKS5 handshake.
// The returned connection is ready to send CONNECT commands directly.
func (c *SOCKS5Client) newIdleConn() (*poolConn, error) {
	conn, err := net.DialTimeout("tcp", c.UpstreamAddr, 10*time.Second)
	if err != nil {
		return nil, fmt.Errorf("dial upstream %s: %w", c.UpstreamAddr, err)
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	conn.SetDeadline(time.Now().Add(5 * time.Second))

	// SOCKS5 no-auth handshake
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		conn.Close()
		return nil, fmt.Errorf("handshake write: %w", err)
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil || resp[1] != 0x00 {
		conn.Close()
		return nil, fmt.Errorf("handshake auth: resp=%v err=%v", resp, err)
	}
	conn.SetDeadline(time.Time{})
	return &poolConn{Conn: conn, createdAt: time.Now()}, nil
}

// acquire gets an idle conn from pool, or creates a new one.
func (c *SOCKS5Client) acquire() (*poolConn, error) {
	c.poolMu.Lock()
	maxAge := 30 * time.Second
	for len(c.poolIdle) > 0 {
		pc := c.poolIdle[len(c.poolIdle)-1]
		c.poolIdle = c.poolIdle[:len(c.poolIdle)-1]
		c.poolMu.Unlock()
		if time.Since(pc.createdAt) < maxAge {
			// Quick health-check: SetDeadline to very near future, try peek
			pc.SetReadDeadline(time.Now().Add(1 * time.Millisecond))
			one := make([]byte, 1)
			if _, err := pc.Read(one); err != nil {
				// conn is alive (timeout error is expected), reset deadline
				pc.SetReadDeadline(time.Time{})
				return pc, nil
			}
			pc.Close() // stale/EOF
		} else {
			pc.Close() // too old
		}
		c.poolMu.Lock()
	}
	c.poolMu.Unlock()
	return c.newIdleConn()
}

// returnConn returns a post-CONNECT connection to the pool (only for idle pre-CONNECT conns).
func (c *SOCKS5Client) returnConn(pc *poolConn) {
	c.poolMu.Lock()
	defer c.poolMu.Unlock()
	if len(c.poolIdle) >= c.poolSize {
		pc.Close()
		return
	}
	c.poolIdle = append(c.poolIdle, pc)
}

// Dial connects to the upstream SOCKS5 proxy and requests a CONNECT to target.
// Reuses a pooled handshaked connection when available.
func (c *SOCKS5Client) Dial(network, target string) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return nil, fmt.Errorf("invalid target %s: %w", target, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return nil, fmt.Errorf("invalid port %s: %w", portStr, err)
	}

	pc, err := c.acquire()
	if err != nil {
		return nil, err
	}

	// Build CONNECT request
	var buf []byte
	ip := net.ParseIP(host)
	if ip4 := ip.To4(); ip4 != nil {
		buf = make([]byte, 4+net.IPv4len+2)
		buf[0], buf[1], buf[2], buf[3] = 0x05, 0x01, 0x00, 0x01
		copy(buf[4:8], ip4)
		buf[8], buf[9] = byte(port>>8), byte(port)
	} else if ip6 := ip.To16(); ip6 != nil {
		buf = make([]byte, 4+net.IPv6len+2)
		buf[0], buf[1], buf[2], buf[3] = 0x05, 0x01, 0x00, 0x04
		copy(buf[4:20], ip6)
		buf[20], buf[21] = byte(port>>8), byte(port)
	} else {
		if len(host) > 255 {
			pc.Close()
			return nil, fmt.Errorf("domain too long: %s", host)
		}
		buf = make([]byte, 4+1+len(host)+2)
		buf[0], buf[1], buf[2], buf[3] = 0x05, 0x01, 0x00, 0x03
		buf[4] = byte(len(host))
		copy(buf[5:], host)
		buf[5+len(host)] = byte(port >> 8)
		buf[6+len(host)] = byte(port)
	}

	pc.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := pc.Write(buf); err != nil {
		pc.Close()
		// Retry once with fresh connection
		return c.dialFresh(network, target)
	}

	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(pc.Conn, replyHeader); err != nil {
		pc.Close()
		return c.dialFresh(network, target)
	}
	if replyHeader[1] != 0x00 {
		pc.Close()
		return nil, fmt.Errorf("socks5 CONNECT error: 0x%02x", replyHeader[1])
	}

	// Drain bound address
	var addrLen int
	switch replyHeader[3] {
	case 0x01:
		addrLen = net.IPv4len
	case 0x04:
		addrLen = net.IPv6len
	case 0x03:
		lb := make([]byte, 1)
		io.ReadFull(pc.Conn, lb)
		addrLen = int(lb[0])
	}
	io.ReadFull(pc.Conn, make([]byte, addrLen+2))

	pc.SetDeadline(time.Time{})
	return pc.Conn, nil
}

// dialFresh creates a brand-new connection without pool, used as retry.
func (c *SOCKS5Client) dialFresh(network, target string) (net.Conn, error) {
	pc, err := c.newIdleConn()
	if err != nil {
		return nil, err
	}
	// Recursive single-retry without pool: call Dial with a fresh conn directly
	host, portStr, _ := net.SplitHostPort(target)
	port, _ := strconv.Atoi(portStr)

	var buf []byte
	ip := net.ParseIP(host)
	if ip4 := ip.To4(); ip4 != nil {
		buf = make([]byte, 10)
		buf[0], buf[1], buf[2], buf[3] = 0x05, 0x01, 0x00, 0x01
		copy(buf[4:8], ip4)
		buf[8], buf[9] = byte(port>>8), byte(port)
	} else if ip6 := ip.To16(); ip6 != nil {
		buf = make([]byte, 22)
		buf[0], buf[1], buf[2], buf[3] = 0x05, 0x01, 0x00, 0x04
		copy(buf[4:20], ip6)
		buf[20], buf[21] = byte(port>>8), byte(port)
	} else {
		buf = make([]byte, 4+1+len(host)+2)
		buf[0], buf[1], buf[2], buf[3] = 0x05, 0x01, 0x00, 0x03
		buf[4] = byte(len(host))
		copy(buf[5:], host)
		buf[5+len(host)] = byte(port >> 8)
		buf[6+len(host)] = byte(port)
	}

	pc.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := pc.Write(buf); err != nil {
		pc.Close()
		return nil, err
	}
	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(pc.Conn, replyHeader); err != nil {
		pc.Close()
		return nil, err
	}
	if replyHeader[1] != 0x00 {
		pc.Close()
		return nil, fmt.Errorf("socks5 CONNECT error: 0x%02x", replyHeader[1])
	}
	var addrLen int
	switch replyHeader[3] {
	case 0x01:
		addrLen = net.IPv4len
	case 0x04:
		addrLen = net.IPv6len
	case 0x03:
		lb := make([]byte, 1)
		io.ReadFull(pc.Conn, lb)
		addrLen = int(lb[0])
	}
	io.ReadFull(pc.Conn, make([]byte, addrLen+2))
	pc.SetDeadline(time.Time{})
	return pc.Conn, nil
}
