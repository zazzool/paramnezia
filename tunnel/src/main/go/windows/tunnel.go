//go:build windows

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"net.paramnezia/tunnel/dnsfilter"
	"net.paramnezia/tunnel/windows/firewall"
)

func init() {
	// A stable adapter GUID: Windows keeps one network profile for it instead of a new
	// "Network 2, 3, …" on every connect.
	tun.WintunStaticRequestedGUID = &windows.GUID{Data1: 0x7061726d, Data2: 0x6e65, Data3: 0x7a69, Data4: [8]byte{'a', 'w', 'g', 0, 0, 0, 0, 1}}
	debug.SetMemoryLimit(64 << 20)
}

type tunnel struct {
	mu      sync.Mutex
	dev     *device.Device
	tun     tun.Device
	bind    conn.Bind
	name    string
	since   time.Time
	luid    atomic.Uint64
	watches []winipcfg.ChangeCallback
}

func (t *tunnel) up(s *upSpec) (err error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	addrs, err := parsePrefixes(s.Addresses)
	if err != nil {
		return err
	}
	routes, err := parsePrefixes(s.Routes)
	if err != nil {
		return err
	}
	var dnsServers []netip.Addr
	for _, d := range s.DNSServers {
		if a, err := netip.ParseAddr(strings.TrimSpace(d)); err == nil {
			dnsServers = append(dnsServers, a)
		}
	}
	mtu := s.MTU
	if mtu <= 0 {
		mtu = 1280
	}

	t.downLocked()
	defer func() {
		if err != nil {
			t.downLocked()
		}
	}()

	native, err := tun.CreateTUN(adapterName, mtu)
	if err != nil {
		return fmt.Errorf("wintun: %w", err)
	}
	t.tun = native
	luid := winipcfg.LUID(native.(*tun.NativeTun).LUID())
	t.luid.Store(uint64(luid))

	var dev tun.Device = native
	if cfg, ok := dnsfilter.ParseConfig(s.DNSConfig); ok {
		// Russian names are resolved over this socket. The direct resolvers are routed outside the
		// tunnel by the app, and the firewall lets this process through.
		direct, _ := net.ListenUDP("udp4", nil)
		if wrapped, err := dnsfilter.NewTun(native, cfg, direct); err == nil {
			dev = wrapped
			t.tun = wrapped
		} else {
			log.Printf("DNS filter disabled: %v", err)
			if direct != nil {
				direct.Close()
			}
		}
	}

	t.bind = conn.NewDefaultBind()
	t.dev = device.NewDevice(dev, t.bind, newLogger(s.Name))
	if err := t.dev.IpcSet(s.UAPI); err != nil {
		return fmt.Errorf("config: %w", err)
	}

	if err := configure(luid, addrs, routes, dnsServers, s.DNSSearch, mtu); err != nil {
		return err
	}
	// Our UDP sockets leave over the physical network even if a route would send the server's
	// address into the tunnel; follow the default interface as networks come and go.
	t.watchDefaultInterface(luid)

	if len(dnsServers) > 0 {
		if err := firewall.BlockDNSLeaks(dnsServers); err != nil {
			log.Printf("DNS leak protection off: %v", err)
		}
	}
	if err := t.dev.Up(); err != nil {
		return fmt.Errorf("up: %w", err)
	}
	t.name = s.Name
	t.since = time.Now()
	return nil
}

func (t *tunnel) down() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.downLocked()
}

func (t *tunnel) downLocked() {
	for _, w := range t.watches {
		_ = w.Unregister()
	}
	t.watches = nil
	firewall.DisableFirewall()
	if t.dev != nil {
		t.dev.Close() // closes the tun device (and the Wintun adapter with it) and the bind
	} else if t.tun != nil {
		t.tun.Close()
	}
	t.dev, t.tun, t.bind = nil, nil, nil
	t.luid.Store(0)
	t.name = ""
}

func (t *tunnel) status() response {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.dev == nil {
		return response{}
	}
	stats, _ := t.dev.IpcGet()
	return response{Up: true, Name: t.name, Since: t.since.UnixMilli(), Stats: withoutSecrets(stats)}
}

func (t *tunnel) set(uapi string) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.dev == nil {
		return errNotUp
	}
	return t.dev.IpcSet(uapi)
}

// configure gives the adapter its addresses, routes and DNS, and the lowest metric so its DNS
// server is asked first.
func configure(luid winipcfg.LUID, addrs, routes []netip.Prefix, dns []netip.Addr, search []string, mtu int) error {
	for _, family := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
		is4 := family == windows.AF_INET
		var fa []netip.Prefix
		for _, a := range addrs {
			if a.Addr().Is4() == is4 {
				fa = append(fa, a)
			}
		}
		// On-link routes: the next hop must be the unspecified address of the family (a zero
		// netip.Addr is rejected with "The parameter is incorrect").
		nextHop := netip.IPv6Unspecified()
		if is4 {
			nextHop = netip.IPv4Unspecified()
		}
		var fr []*winipcfg.RouteData
		for _, r := range routes {
			if r.Addr().Is4() == is4 {
				fr = append(fr, &winipcfg.RouteData{Destination: r.Masked(), NextHop: nextHop, Metric: 0})
			}
		}
		if len(fa) == 0 {
			continue
		}
		if err := luid.SetIPAddressesForFamily(family, fa); err != nil {
			return fmt.Errorf("addresses: %w", err)
		}
		ipif, err := luid.IPInterface(family)
		if err != nil {
			return err
		}
		ipif.RouterDiscoveryBehavior = winipcfg.RouterDiscoveryDisabled
		ipif.DadTransmits = 0
		ipif.ManagedAddressConfigurationSupported = false
		ipif.OtherStatefulConfigurationSupported = false
		ipif.NLMTU = uint32(mtu)
		ipif.UseAutomaticMetric = false
		ipif.Metric = 0
		if err := ipif.Set(); err != nil {
			return fmt.Errorf("metric: %w", err)
		}
		if err := luid.SetRoutesForFamily(family, fr); err != nil {
			return fmt.Errorf("routes: %w", err)
		}
		var fd []netip.Addr
		for _, d := range dns {
			if d.Is4() == is4 {
				fd = append(fd, d)
			}
		}
		if err := luid.SetDNS(family, fd, search); err != nil {
			return fmt.Errorf("dns: %w", err)
		}
	}
	return nil
}

func (t *tunnel) watchDefaultInterface(ours winipcfg.LUID) {
	binder, ok := t.bind.(conn.BindSocketToInterface)
	if !ok {
		return
	}
	var mu sync.Mutex
	last4, last6 := ^uint32(0), ^uint32(0)
	update := func() {
		mu.Lock()
		defer mu.Unlock()
		if i, err := defaultInterface(windows.AF_INET, ours); err == nil && i != last4 {
			if binder.BindSocketToInterface4(i, false) == nil {
				last4 = i
			}
		}
		if i, err := defaultInterface(windows.AF_INET6, ours); err == nil && i != last6 {
			if binder.BindSocketToInterface6(i, false) == nil {
				last6 = i
			}
		}
	}
	update()
	if cb, err := winipcfg.RegisterRouteChangeCallback(func(n winipcfg.MibNotificationType, r *winipcfg.MibIPforwardRow2) {
		if r != nil && r.DestinationPrefix.PrefixLength == 0 {
			go update()
		}
	}); err == nil {
		t.watches = append(t.watches, cb)
	}
	if cb, err := winipcfg.RegisterInterfaceChangeCallback(func(n winipcfg.MibNotificationType, i *winipcfg.MibIPInterfaceRow) {
		if n == winipcfg.MibParameterNotification {
			go update()
		}
	}); err == nil {
		t.watches = append(t.watches, cb)
	}
}

// defaultInterface is the index of the interface with the best default route, not counting ours
// (the same choice as wireguard-windows makes for its MTU monitor).
func defaultInterface(family winipcfg.AddressFamily, ours winipcfg.LUID) (uint32, error) {
	rows, err := winipcfg.GetIPForwardTable2(family)
	if err != nil {
		return 0, err
	}
	best := ^uint64(0)
	index := uint32(0)
	for i := range rows {
		r := &rows[i]
		if r.DestinationPrefix.PrefixLength != 0 || r.InterfaceLUID == ours {
			continue
		}
		ifrow, err := r.InterfaceLUID.Interface()
		if err != nil || ifrow.OperStatus != winipcfg.IfOperStatusUp {
			continue
		}
		ipif, err := r.InterfaceLUID.IPInterface(family)
		if err != nil {
			continue
		}
		if m := uint64(r.Metric) + uint64(ipif.Metric); m < best {
			best, index = m, r.InterfaceIndex
		}
	}
	if index == 0 {
		return 0, errors.New("no default route")
	}
	return index, nil
}

func parsePrefixes(list []string) ([]netip.Prefix, error) {
	out := make([]netip.Prefix, 0, len(list))
	for _, s := range list {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		p, err := netip.ParsePrefix(s)
		if err != nil {
			return nil, fmt.Errorf("bad prefix %q", s)
		}
		out = append(out, p)
	}
	return out, nil
}

func newLogger(name string) *device.Logger {
	return &device.Logger{
		Verbosef: device.DiscardLogf,
		Errorf: func(format string, args ...any) {
			log.Printf("["+name+"] "+format, args...)
		},
	}
}

func amneziawgVersion() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	for _, dep := range info.Deps {
		if dep.Path == "github.com/amnezia-vpn/amneziawg-go/v3" {
			return strings.TrimPrefix(dep.Version, "v")
		}
	}
	return "unknown"
}

// withoutSecrets drops the keys from a UAPI dump: any logged-on user may ask for the status,
// and the app needs only counters, handshake times and endpoints.
func withoutSecrets(uapi string) string {
	var b strings.Builder
	for _, line := range strings.Split(uapi, "\n") {
		if strings.HasPrefix(line, "private_key=") || strings.HasPrefix(line, "preshared_key=") || line == "" {
			continue
		}
		b.WriteString(line)
		b.WriteByte('\n')
	}
	return b.String()
}
