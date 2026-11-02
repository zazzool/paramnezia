//go:build windows

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"net.paramnezia/tunnel/splitapp"
)

// Programs past the VPN, the Windows half (see package splitapp for the idea): which process owns
// a flow, from the system's connection tables, and sockets bound to the physical interface.

var (
	iphlpapi                = windows.NewLazySystemDLL("iphlpapi.dll")
	procGetExtendedTcpTable = iphlpapi.NewProc("GetExtendedTcpTable")
	procGetExtendedUdpTable = iphlpapi.NewProc("GetExtendedUdpTable")
)

const (
	tcpTableOwnerPidAll = 5 // TCP_TABLE_OWNER_PID_ALL
	udpTableOwnerPid    = 1 // UDP_TABLE_OWNER_PID
)

type ownerKey struct {
	proto     uint8
	localPort uint16
	remote    netip.AddrPort // TCP only
	localAddr netip.Addr     // UDP only; unspecified for wildcard binds
}

// owners looks flows up in the system's TCP and UDP tables. A snapshot is reused until a flow is
// missing from it, so a burst of new connections costs one table read, not one each.
type owners struct {
	mu    sync.Mutex
	tcp   map[ownerKey]uint32
	udp   map[ownerKey]uint32
	exes  map[uint32]exeEntry
	clean time.Time
}

type exeEntry struct {
	path string
	at   time.Time
}

func newOwners() *owners {
	return &owners{tcp: map[ownerKey]uint32{}, udp: map[ownerKey]uint32{}, exes: map[uint32]exeEntry{}}
}

func (o *owners) lookup(proto uint8, local, remote netip.AddrPort) string {
	o.mu.Lock()
	defer o.mu.Unlock()
	pid, ok := o.find(proto, local, remote)
	if !ok {
		o.refresh(proto)
		if pid, ok = o.find(proto, local, remote); !ok {
			return ""
		}
	}
	return o.exe(pid)
}

func (o *owners) find(proto uint8, local, remote netip.AddrPort) (uint32, bool) {
	if proto == 6 {
		pid, ok := o.tcp[ownerKey{proto: 6, localPort: local.Port(), remote: unmap(remote)}]
		return pid, ok
	}
	if pid, ok := o.udp[ownerKey{proto: 17, localPort: local.Port(), localAddr: local.Addr().Unmap()}]; ok {
		return pid, true
	}
	unspec := netip.IPv4Unspecified()
	if local.Addr().Unmap().Is6() {
		unspec = netip.IPv6Unspecified()
	}
	pid, ok := o.udp[ownerKey{proto: 17, localPort: local.Port(), localAddr: unspec}]
	return pid, ok
}

func unmap(a netip.AddrPort) netip.AddrPort { return netip.AddrPortFrom(a.Addr().Unmap(), a.Port()) }

func (o *owners) refresh(proto uint8) {
	if proto == 6 {
		m := map[ownerKey]uint32{}
		if b, err := extendedTable(procGetExtendedTcpTable, windows.AF_INET, tcpTableOwnerPidAll); err == nil {
			// MIB_TCPROW_OWNER_PID: state, local addr, local port, remote addr, remote port, pid
			eachRow(b, 24, func(r []byte) {
				m[ownerKey{proto: 6, localPort: port(r[8:]), remote: netip.AddrPortFrom(netip.AddrFrom4([4]byte(r[12:16])), port(r[16:]))}] = le32(r[20:])
			})
		}
		if b, err := extendedTable(procGetExtendedTcpTable, windows.AF_INET6, tcpTableOwnerPidAll); err == nil {
			// MIB_TCP6ROW_OWNER_PID: local addr[16], scope, local port, remote addr[16], scope, remote port, state, pid
			eachRow(b, 56, func(r []byte) {
				m[ownerKey{proto: 6, localPort: port(r[20:]), remote: netip.AddrPortFrom(netip.AddrFrom16([16]byte(r[24:40])).Unmap(), port(r[44:]))}] = le32(r[52:])
			})
		}
		o.tcp = m
	} else {
		m := map[ownerKey]uint32{}
		if b, err := extendedTable(procGetExtendedUdpTable, windows.AF_INET, udpTableOwnerPid); err == nil {
			// MIB_UDPROW_OWNER_PID: local addr, local port, pid
			eachRow(b, 12, func(r []byte) {
				m[ownerKey{proto: 17, localPort: port(r[4:]), localAddr: netip.AddrFrom4([4]byte(r[0:4]))}] = le32(r[8:])
			})
		}
		if b, err := extendedTable(procGetExtendedUdpTable, windows.AF_INET6, udpTableOwnerPid); err == nil {
			// MIB_UDP6ROW_OWNER_PID: local addr[16], scope, local port, pid
			eachRow(b, 28, func(r []byte) {
				m[ownerKey{proto: 17, localPort: port(r[20:]), localAddr: netip.AddrFrom16([16]byte(r[0:16])).Unmap()}] = le32(r[24:])
			})
		}
		o.udp = m
	}
}

// exe is the full path of a process, cached briefly (process ids are reused, but not that fast).
func (o *owners) exe(pid uint32) string {
	now := time.Now()
	if e, ok := o.exes[pid]; ok && now.Sub(e.at) < 30*time.Second {
		return e.path
	}
	if now.Sub(o.clean) > time.Minute {
		for p, e := range o.exes {
			if now.Sub(e.at) > 30*time.Second {
				delete(o.exes, p)
			}
		}
		o.clean = now
	}
	path := ""
	if pid > 4 { // 0 idle, 4 System
		if h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid); err == nil {
			buf := make([]uint16, windows.MAX_LONG_PATH)
			n := uint32(len(buf))
			if windows.QueryFullProcessImageName(h, 0, &buf[0], &n) == nil {
				path = windows.UTF16ToString(buf[:n])
			}
			windows.CloseHandle(h)
		}
	}
	o.exes[pid] = exeEntry{path: path, at: now}
	return path
}

func extendedTable(proc *windows.LazyProc, family uint32, class uint32) ([]byte, error) {
	size := uint32(16 << 10)
	for i := 0; i < 5; i++ {
		buf := make([]byte, size)
		r, _, _ := proc.Call(uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)), 0, uintptr(family), uintptr(class), 0)
		switch syscall.Errno(r) {
		case 0:
			return buf[:size], nil
		case windows.ERROR_INSUFFICIENT_BUFFER:
			size += 4 << 10 // connections come and go between the calls
			continue
		default:
			return nil, syscall.Errno(r)
		}
	}
	return nil, errors.New("connection table keeps growing")
}

// eachRow walks a MIB_*TABLE_OWNER_PID: a DWORD count, then the rows.
func eachRow(b []byte, rowSize int, f func([]byte)) {
	if len(b) < 4 {
		return
	}
	n := int(le32(b))
	for i := 0; i < n; i++ {
		off := 4 + i*rowSize
		if off+rowSize > len(b) {
			return
		}
		f(b[off : off+rowSize])
	}
}

func le32(b []byte) uint32 { return binary.LittleEndian.Uint32(b) }

// port reads a DWORD port field: the port in network byte order in its first two bytes.
func port(b []byte) uint16 { return binary.BigEndian.Uint16(b[:2]) }

// physicalDialer opens the excluded programs' real connections on the physical interface. Unlike
// the SOCKS proxy it never falls back to "let the system decide": that would loop into the tunnel.
type physicalDialer struct{ ours func() winipcfg.LUID }

func (d physicalDialer) control(network, _ string, rc syscall.RawConn) error {
	v6 := network == "tcp6" || network == "udp6"
	family := winipcfg.AddressFamily(windows.AF_INET)
	if v6 {
		family = windows.AF_INET6
	}
	index, err := defaultInterface(family, d.ours())
	if err != nil {
		return err
	}
	return bindToInterface(rc, v6, index)
}

func (d physicalDialer) DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	network := "tcp4"
	if dst.Addr().Unmap().Is6() {
		network = "tcp6"
	}
	nd := net.Dialer{Control: d.control, KeepAlive: 30 * time.Second}
	return nd.DialContext(ctx, network, unmap(dst).String())
}

func (d physicalDialer) ListenUDP(v6 bool) (net.PacketConn, error) {
	network := "udp4"
	if v6 {
		network = "udp6"
	}
	lc := net.ListenConfig{Control: d.control}
	return lc.ListenPacket(context.Background(), network, ":0")
}

// bindToInterface sets IP_UNICAST_IF / IPV6_UNICAST_IF: the socket's unicast traffic leaves through
// that interface whatever the routing table says.
func bindToInterface(rc syscall.RawConn, v6 bool, index uint32) error {
	var serr error
	err := rc.Control(func(fd uintptr) {
		if v6 {
			serr = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IPV6, 31 /* IPV6_UNICAST_IF */, int(index))
		} else {
			// IP_UNICAST_IF takes the index in network byte order.
			var b [4]byte
			binary.BigEndian.PutUint32(b[:], index)
			serr = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IP, 31 /* IP_UNICAST_IF */, int(binary.LittleEndian.Uint32(b[:])))
		}
	})
	if err != nil {
		return err
	}
	return serr
}

// splitKeep: what stays in the tunnel whoever sends it, the tunnel's subnet and DNS servers.
func splitKeep(addrs []netip.Prefix, dns []netip.Addr) []netip.Prefix {
	var keep []netip.Prefix
	for _, a := range addrs {
		bits := a.Bits()
		if a.Addr().Is4() && bits > 24 {
			bits = 24
		}
		keep = append(keep, netip.PrefixFrom(a.Addr(), bits).Masked())
	}
	for _, d := range dns {
		keep = append(keep, netip.PrefixFrom(d, d.BitLen()))
	}
	return keep
}

func newSplit(dev tun.Device, apps []string, mtu int, addrs []netip.Prefix, dns []netip.Addr, ours func() winipcfg.LUID, logf func(string, ...any)) (tun.Device, error) {
	o := newOwners()
	return splitapp.NewTun(dev, splitapp.Config{
		Rules:  splitapp.NewRules(apps),
		Owner:  o.lookup,
		Dialer: physicalDialer{ours: ours},
		MTU:    mtu,
		Keep:   splitKeep(addrs, dns),
		Logf:   logf,
	})
}
