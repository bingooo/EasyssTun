package tsrouter

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	"github.com/wlynxg/anet"
	"tailscale.com/client/tailscale"
	"tailscale.com/ipn"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/tsnet"
)

// TSStatus represents the status returned to Kotlin.
type TSStatus struct {
	State          string   `json:"state"`
	HostName       string   `json:"host_name"`
	IPs            []string `json:"ips"`
	Subnets        []string `json:"subnets,omitempty"`
	MagicDNSSuffix string   `json:"magic_dns_suffix"`
	PeerCount      int      `json:"peer_count"`
	ErrMessage     string   `json:"err_message,omitempty"`
}

type TSNetNode struct {
	server    *tsnet.Server
	localClient *tailscale.LocalClient
	matcher   *IPMatcher
	mu        sync.RWMutex
	state     string
	ips       []string
	dnsSuffix string
	lastErr   error
	cancel    context.CancelFunc
	readyCh   chan struct{}
	readyOnce sync.Once
}

func init() {
	// Disable kernel netlink, force userspace netstack, and disable home router netlink lookup on Android
	netns.SetEnabled(false)
	os.Setenv("TS_NETSTACK", "1")
	os.Setenv("TSNET_FORCE_NETSTACK", "1")
	os.Setenv("TS_DEBUG_DISABLE_LIKELY_HOME_ROUTER_IP_SELF", "true")
	os.Setenv("TS_DISABLE_PORTMAPPER", "true")
	os.Setenv("TS_DISABLE_UPNP", "true")

	// Register anet interface getter to bypass Android NETLINK_ROUTE socket permission denied
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := anet.Interfaces()
		if err != nil {
			return nil, fmt.Errorf("anet.Interfaces: %w", err)
		}
		ret := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			addrs, err := anet.InterfaceAddrsByInterface(&ifs[i])
			if err != nil {
				return nil, fmt.Errorf("ifs[%d].Addrs: %w", i, err)
			}
			ret[i] = netmon.Interface{
				Interface: &ifs[i],
				AltAddrs:  addrs,
			}
		}
		return ret, nil
	})
}

func NewTSNetNode(hostname, authKey, controlURL, stateDir string, matcher *IPMatcher) *TSNetNode {
	if stateDir != "" {
		_ = os.MkdirAll(stateDir, 0755)
	}

	srv := &tsnet.Server{
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Dir:        stateDir,
		Logf:       log.Printf,
	}

	return &TSNetNode{
		server:  srv,
		matcher: matcher,
		state:   "Stopped",
		readyCh: make(chan struct{}),
	}
}

// Start launches the tsnet server asynchronously.
func (n *TSNetNode) Start() error {
	n.mu.Lock()
	n.state = "Starting"
	n.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	n.cancel = cancel

	go func() {
		log.Println("[TSNetNode] Starting tsnet.Server.Up()...")
		status, err := n.server.Up(ctx)
		if err != nil {
			log.Printf("[TSNetNode] tsnet Up failed: %v", err)
			n.mu.Lock()
			n.state = "Error"
			n.lastErr = err
			n.mu.Unlock()
			return
		}

		lc, err := n.server.LocalClient()
		if err != nil {
			log.Printf("[TSNetNode] LocalClient error: %v", err)
		} else {
			n.localClient = lc
		}

		n.mu.Lock()
		n.state = status.BackendState
		n.mu.Unlock()

		// Periodic status update loop
		n.pollStatusLoop(ctx)
	}()

	return nil
}

func (n *TSNetNode) pollStatusLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		if n.localClient == nil {
			if lc, err := n.server.LocalClient(); err == nil {
				n.localClient = lc
			} else {
				time.Sleep(1 * time.Second)
				continue
			}
		}

		// Use WatchIPNBus to listen for status/route change events (event-driven instead of polling)
		watcher, err := n.localClient.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyInitialNetMap)
		if err != nil {
			log.Printf("[TSNetNode] WatchIPNBus failed, falling back to 5s ticker: %v", err)
			time.Sleep(5 * time.Second)
			n.updateStatusOnce(ctx)
			continue
		}

		log.Printf("[TSNetNode] WatchIPNBus event listener started.")
		for {
			_, err := watcher.Next()
			if err != nil {
				watcher.Close()
				break // Reconnect watcher on error
			}
			n.updateStatusOnce(ctx)
		}
	}
}

func (n *TSNetNode) updateStatusOnce(ctx context.Context) {
	if n.localClient == nil {
		return
	}
	st, err := n.localClient.Status(ctx)
	if err != nil {
		return
	}

	n.mu.Lock()
	n.state = st.BackendState
	var prefixes []netip.Prefix
	if st.Self != nil {
		n.ips = nil
		for _, ip := range st.Self.TailscaleIPs {
			n.ips = append(n.ips, ip.String())
			if pfx, err := ip.Prefix(ip.BitLen()); err == nil {
				prefixes = append(prefixes, pfx)
			}
		}
	}
			// Get current local physical network interface IPs to avoid local LAN subnet overlap
			var localIPs []netip.Addr
			if ifs, err := anet.Interfaces(); err == nil {
				for i := range ifs {
					if addrs, err := anet.InterfaceAddrsByInterface(&ifs[i]); err == nil {
						for _, a := range addrs {
							if ipNet, ok := a.(*net.IPNet); ok {
								if addr, err := netip.ParseAddr(ipNet.IP.String()); err == nil {
									localIPs = append(localIPs, addr)
								}
							}
						}
					}
				}
			}

			// Traverse PeerStatus to collect Subnet Router routes and Peer IPs
			if st.Peer != nil {
				for _, peer := range st.Peer {
					// 1. Add peer's Tailscale IPs
					for _, ip := range peer.TailscaleIPs {
						if pfx, err := ip.Prefix(ip.BitLen()); err == nil {
							prefixes = append(prefixes, pfx)
						}
					}
					// 2. Add advertised/approved Subnet routes from AllowedIPs and PrimaryRoutes (ignoring local LAN overlap)
					var routesToCollect []netip.Prefix
					if peer.PrimaryRoutes != nil {
						routesToCollect = append(routesToCollect, peer.PrimaryRoutes.AsSlice()...)
					}
					if peer.AllowedIPs != nil {
						routesToCollect = append(routesToCollect, peer.AllowedIPs.AsSlice()...)
					}
					for _, pfx := range routesToCollect {
						if pfx.Bits() == 0 {
							continue // Filter out default exit nodes (0.0.0.0/0, ::/0)
						}
						// Check if this Subnet overlaps with any current local physical LAN IP
						isLocalOverlap := false
						for _, localIP := range localIPs {
							if pfx.Contains(localIP) {
								isLocalOverlap = true
								log.Printf("[TSNetNode] Subnet route %s overlaps with local physical LAN IP %s, bypassing Tailnet for this subnet", pfx.String(), localIP.String())
								break
							}
						}
						if !isLocalOverlap {
							prefixes = append(prefixes, pfx)
						}
					}
				}
			}
			n.matcher.UpdateDynamicPrefixes(prefixes)

			if st.MagicDNSSuffix != "" {
				n.dnsSuffix = st.MagicDNSSuffix
			}
			if st.BackendState == "Running" {
				n.readyOnce.Do(func() {
					close(n.readyCh)
				})
			}
			n.mu.Unlock()
}

// Dial connects to a target host on the Tailnet.
func (n *TSNetNode) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	return n.server.Dial(ctx, network, address)
}

// Stop shuts down the tsnet server.
func (n *TSNetNode) Stop() error {
	n.mu.Lock()
	n.state = "Stopped"
	n.mu.Unlock()

	if n.cancel != nil {
		n.cancel()
	}
	if n.server != nil {
		return n.server.Close()
	}
	return nil
}

// IsReady returns true if backend state is Running.
func (n *TSNetNode) IsReady() bool {
	n.mu.RLock()
	defer n.mu.RUnlock()
	return n.state == "Running"
}

// WaitReady blocks until the node is in Running state or ctx is cancelled.
func (n *TSNetNode) WaitReady(ctx context.Context) error {
	if n.IsReady() {
		return nil
	}
	select {
	case <-n.readyCh:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// GetStatusJSON returns JSON representation of current status.
func (n *TSNetNode) GetStatusJSON() string {
	n.mu.RLock()
	defer n.mu.RUnlock()

	st := TSStatus{
		State:          n.state,
		HostName:       n.server.Hostname,
		IPs:            n.ips,
		Subnets:        n.matcher.GetSubnets(),
		MagicDNSSuffix: n.dnsSuffix,
	}
	if n.lastErr != nil {
		st.ErrMessage = n.lastErr.Error()
	}

	data, _ := json.Marshal(st)
	return string(data)
}
