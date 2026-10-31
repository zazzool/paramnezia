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
	"math"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/sys/unix"
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
	if cfg, ok := parseDNSConfig(dns); ok {
		if wrapped, err := newDNSTun(tunDev, cfg, int(dnsDirectFd)); err == nil {
			tunDev = wrapped
		} else {
			logger.Errorf("DNS filter disabled: %v", err)
		}
	}
	if dnsDirectFd >= 0 {
		unix.Close(int(dnsDirectFd)) // newDNSTun keeps its own duplicate
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
