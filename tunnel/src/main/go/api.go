/* SPDX-License-Identifier: Apache-2.0
 *
 * Thin JNI-facing layer over amneziawg-go, derived from amneziawg-android's libwg-go
 * (Copyright © 2017-2022 Jason A. Donenfeld, WireGuard LLC; Amnezia).
 *
 * Differences that matter for battery and memory:
 *   - no UAPI unix socket and its accept goroutine: the app talks to the device through these calls;
 *   - a soft memory limit so the Go heap stays small on phones;
 *   - verbose logging off unless asked for.
 */

package main

import "C"

import (
	"errors"
	"math"
	"net"
	"os"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/sys/unix"

	"net.paramnezia/tunnel/dnsfilter"
)

var (
	mu      sync.Mutex
	handles = map[int32]*device.Device{}
	verbose bool
)

func init() {
	// Keep the heap small; GC runs more often only when we approach the limit.
	debug.SetMemoryLimit(48 << 20)
}

//export pnSetVerbose
func pnSetVerbose(on bool) { verbose = on }

//export pnTurnOn
func pnTurnOn(interfaceName string, tunFd int32, settings string, dns string, dnsDirectFd int32) int32 {
	logger := newLogger("Paramnezia/"+interfaceName, verbose)

	var tunDev tun.Device
	tunDev, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
		if dnsDirectFd >= 0 {
			unix.Close(int(dnsDirectFd))
		}
		logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}
	// The direct DNS socket is ours from here on and is closed exactly once: by udpFromFd (which keeps
	// a duplicate) or right here. A second close could hit a socket opened meanwhile under the same
	// number, such as the DNS filter's own tunnel socket.
	var direct *net.UDPConn
	if dnsDirectFd >= 0 {
		direct, _ = udpFromFd(int(dnsDirectFd))
	}
	if cfg, ok := dnsfilter.ParseConfig(dns); ok {
		if wrapped, err := dnsfilter.NewTun(tunDev, cfg, direct); err == nil {
			tunDev = wrapped
			direct = nil
		} else {
			logger.Errorf("DNS filter disabled: %v", err)
		}
	}
	if direct != nil {
		direct.Close()
	}
	logger.Verbosef("Attaching to interface %v", name)
	dev := device.NewDevice(tunDev, conn.NewStdNetBind(), logger)
	if err := dev.IpcSet(settings); err != nil {
		dev.Close()
		logger.Errorf("IpcSet: %v", err)
		return -2
	}
	// Mobile networks change addresses all the time; do not chase every roaming packet.
	dev.DisableSomeRoamingForBrokenMobileSemantics()
	if err := dev.Up(); err != nil {
		dev.Close()
		logger.Errorf("Up: %v", err)
		return -3
	}

	mu.Lock()
	defer mu.Unlock()
	var i int32
	for i = 0; i < math.MaxInt32; i++ {
		if _, used := handles[i]; !used {
			break
		}
	}
	handles[i] = dev
	return i
}

//export pnTurnOff
func pnTurnOff(handle int32) {
	mu.Lock()
	dev, ok := handles[handle]
	delete(handles, handle)
	mu.Unlock()
	if ok {
		dev.Close()
	}
}

func get(handle int32) *device.Device {
	mu.Lock()
	defer mu.Unlock()
	return handles[handle]
}

//export pnGetSocketV4
func pnGetSocketV4(handle int32) int32 {
	dev := get(handle)
	if dev == nil {
		return -1
	}
	bind, _ := dev.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd4()
	if err != nil {
		return -1
	}
	return int32(fd)
}

//export pnGetSocketV6
func pnGetSocketV6(handle int32) int32 {
	dev := get(handle)
	if dev == nil {
		return -1
	}
	bind, _ := dev.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd6()
	if err != nil {
		return -1
	}
	return int32(fd)
}

// pnGetConfig returns the UAPI "get" dump (stats, last handshake). Caller frees.
//
//export pnGetConfig
func pnGetConfig(handle int32) *C.char {
	dev := get(handle)
	if dev == nil {
		return nil
	}
	s, err := dev.IpcGet()
	if err != nil {
		return nil
	}
	return C.CString(s)
}

// pnSetConfig applies a partial UAPI "set" (e.g. a new keepalive) to a running tunnel.
//
//export pnSetConfig
func pnSetConfig(handle int32, settings string) int32 {
	dev := get(handle)
	if dev == nil {
		return -1
	}
	if err := dev.IpcSet(settings); err != nil {
		return -2
	}
	return 0
}

// pnRebind replaces the tunnel's UDP sockets with fresh ones, keeping the interface, keys and
// sessions: the cheap first cure for a stuck NAT binding or a socket left on a network that is gone.
// The new sockets must be protected from the VPN again (pnGetSocketV4/V6).
//
//export pnRebind
func pnRebind(handle int32) int32 {
	dev := get(handle)
	if dev == nil {
		return -1
	}
	if err := dev.BindUpdate(); err != nil {
		return -2
	}
	return 0
}

func udpFromFd(fd int) (*net.UDPConn, error) {
	f := os.NewFile(uintptr(fd), "pn-dns-direct")
	defer f.Close() // FileConn dups the descriptor
	c, err := net.FileConn(f)
	if err != nil {
		return nil, err
	}
	u, ok := c.(*net.UDPConn)
	if !ok {
		c.Close()
		return nil, errors.New("not a UDP socket")
	}
	return u, nil
}

// pnSetDnsRules replaces the block / direct / allow lists (one rule per line, `full:` for exact
// names) for all tunnels, effective for the next query.
//
//export pnSetDnsRules
func pnSetDnsRules(block, direct, allow string) { dnsfilter.SetRules(block, direct, allow) }

// pnSetLocalDns sets the resolvers of the local network (comma-separated), which answer local
// names such as nas.lan; empty when the phone is not on Wi-Fi or Ethernet.
//
//export pnSetLocalDns
func pnSetLocalDns(servers string) { dnsfilter.SetLocalServers(strings.Split(servers, ",")) }

// pnDnsBlocked returns how many queries were blocked since the library was loaded.
//
//export pnDnsBlocked
func pnDnsBlocked() int64 { return dnsfilter.Blocked() }

//export pnVersion
func pnVersion() *C.char {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return C.CString("unknown")
	}
	for _, dep := range info.Deps {
		if dep.Path == "github.com/amnezia-vpn/amneziawg-go/v3" {
			return C.CString(strings.TrimPrefix(dep.Version, "v"))
		}
	}
	return C.CString("unknown")
}

func main() {}
