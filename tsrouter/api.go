package tsrouter

import (
	"fmt"
	"log"
	"sync"
)

var (
	globalMu       sync.Mutex
	globalTSNode   *TSNetNode
	globalServer   *SOCKS5Server
	globalMatcher  *IPMatcher
)

// Start initializes and launches Tailscale tsnet node and local SOCKS5 router server.
func Start(routerPort, easyssPort int, authKey, controlURL, stateDir, hostname string) string {
	globalMu.Lock()
	defer globalMu.Unlock()

	if globalServer != nil {
		return "already started"
	}

	if routerPort <= 0 {
		routerPort = 10800
	}
	if easyssPort <= 0 {
		easyssPort = 10801
	}
	if hostname == "" {
		hostname = "easysstun-node"
	}

	matcher := NewIPMatcher()
	tsNode := NewTSNetNode(hostname, authKey, controlURL, stateDir, matcher)

	easyssAddr := fmt.Sprintf("127.0.0.1:%d", easyssPort)
	socksClient := NewSOCKS5Client(easyssAddr)

	routerAddr := fmt.Sprintf("127.0.0.1:%d", routerPort)
	server := NewSOCKS5Server(routerAddr, matcher, tsNode, socksClient)

	// 1. Start tsnet node asynchronously
	if err := tsNode.Start(); err != nil {
		return fmt.Sprintf("tsnet start error: %v", err)
	}

	// 2. Start SOCKS5 router server
	if err := server.Start(); err != nil {
		tsNode.Stop()
		return fmt.Sprintf("router server start error: %v", err)
	}

	globalMatcher = matcher
	globalTSNode = tsNode
	globalServer = server

	log.Printf("[tsrouter] Successfully started router on %s, forwarding non-tailnet to easyss at %s", routerAddr, easyssAddr)
	return ""
}

// Stop shuts down the router server and tsnet node.
func Stop() string {
	globalMu.Lock()
	defer globalMu.Unlock()

	if globalServer == nil {
		return ""
	}

	if err := globalServer.Stop(); err != nil {
		log.Printf("[tsrouter] Stop server error: %v", err)
	}
	if err := globalTSNode.Stop(); err != nil {
		log.Printf("[tsrouter] Stop tsnode error: %v", err)
	}

	globalServer = nil
	globalTSNode = nil
	globalMatcher = nil

	log.Println("[tsrouter] Successfully stopped router.")
	return ""
}

// GetStatusJSON returns the current status of the Tailscale node in JSON format.
func GetStatusJSON() string {
	globalMu.Lock()
	tsNode := globalTSNode
	globalMu.Unlock()

	if tsNode == nil {
		return `{"state":"Stopped"}`
	}
	return tsNode.GetStatusJSON()
}

// IsReady returns true if Tailscale node is ready/running.
func IsReady() bool {
	globalMu.Lock()
	tsNode := globalTSNode
	globalMu.Unlock()

	if tsNode == nil {
		return false
	}
	return tsNode.IsReady()
}
