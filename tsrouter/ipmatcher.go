package tsrouter

import (
	"net"
	"net/netip"
	"strings"
	"sync"
)

// IPMatcher keeps track of Tailscale IP ranges, domains, and dynamic routes.
type IPMatcher struct {
	mu            sync.RWMutex
	cgnatIPv4     netip.Prefix // 100.64.0.0/10
	tailscaleIPv6 netip.Prefix // fd7a:115c:a1e0::/48
	dynamicPrefixes []netip.Prefix
}

func NewIPMatcher() *IPMatcher {
	return &IPMatcher{
		cgnatIPv4:     netip.MustParsePrefix("100.64.0.0/10"),
		tailscaleIPv6: netip.MustParsePrefix("fd7a:115c:a1e0::/48"),
	}
}

// UpdateDynamicPrefixes sets dynamic subnets/peer IPs learned from tsnet status.
func (m *IPMatcher) UpdateDynamicPrefixes(prefixes []netip.Prefix) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.dynamicPrefixes = prefixes
}

// GetSubnets returns string representation of dynamic subnets (excluding single host IPs).
func (m *IPMatcher) GetSubnets() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var subnets []string
	for _, pfx := range m.dynamicPrefixes {
		if !pfx.IsSingleIP() {
			subnets = append(subnets, pfx.String())
		}
	}
	return subnets
}

// IsTailnet determines whether host (domain or IP string) should be routed to Tailscale.
func (m *IPMatcher) IsTailnet(host string) bool {
	// 1. Domain name check
	lowerHost := strings.ToLower(strings.TrimSuffix(host, "."))
	if strings.HasSuffix(lowerHost, ".ts.net") {
		return true
	}

	// 2. Parse IP address
	ip, err := netip.ParseAddr(lowerHost)
	if err != nil {
		// Host is a domain name (not an IP). If not .ts.net, fallback to false (or attempt DNS lookup if needed).
		return false
	}

	// 3. Match fixed Tailscale IP ranges
	if m.cgnatIPv4.Contains(ip) || m.tailscaleIPv6.Contains(ip) {
		return true
	}

	// 4. Match dynamic prefixes (advertised subnets)
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, pfx := range m.dynamicPrefixes {
		if pfx.Contains(ip) {
			return true
		}
	}

	return false
}

// IsTailnetAddr checks a net.Addr.
func (m *IPMatcher) IsTailnetAddr(addr net.Addr) bool {
	if addr == nil {
		return false
	}
	host, _, err := net.SplitHostPort(addr.String())
	if err != nil {
		host = addr.String()
	}
	return m.IsTailnet(host)
}
