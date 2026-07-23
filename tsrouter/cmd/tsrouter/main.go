package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
	"time"

	"tsrouter"
)

func main() {
	routerPort := flag.Int("router-port", 10800, "Local SOCKS5 router listening port")
	easyssPort := flag.Int("easyss-port", 10801, "Upstream Easyss SOCKS5 port")
	authKey := flag.String("auth-key", "", "Tailscale Auth Key")
	controlURL := flag.String("control-url", "https://controlplane.tailscale.com", "Tailscale/Headscale Control Server URL")
	stateDir := flag.String("state-dir", "", "Tailscale state directory")
	hostname := flag.String("hostname", "EasyssTun-Android", "Tailscale device hostname")

	flag.Parse()

	// Set GOGC to 200 for lower CPU & GC overhead on mobile
	debug.SetGCPercent(200)

	log.Printf("[tsrouter-cli] Starting tsrouter daemon: routerPort=%d, easyssPort=%d, controlURL=%s, hostname=%s",
		*routerPort, *easyssPort, *controlURL, *hostname)

	errStr := tsrouter.Start(*routerPort, *easyssPort, *authKey, *controlURL, *stateDir, *hostname)
	if errStr != "" {
		log.Fatalf("[tsrouter-cli] Start error: %s", errStr)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	// Status logger loop
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case sig := <-sigCh:
			log.Printf("[tsrouter-cli] Received signal %v, shutting down...", sig)
			tsrouter.Stop()
			return
		case <-ticker.C:
			statusJSON := tsrouter.GetStatusJSON()
			log.Printf("[tsrouter-status] %s", statusJSON)
		}
	}
}
