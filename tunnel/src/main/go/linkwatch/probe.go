/* SPDX-License-Identifier: Apache-2.0 */

package linkwatch

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"syscall"
	"time"
)

// Targets answer on 53 and 443 everywhere and are never in the "Russian direct" lists.
var Targets = []string{"1.1.1.1", "8.8.8.8", "9.9.9.9"}

// query is "." IN NS with recursion desired: tiny, cacheable, answered by every resolver.
var query = []byte{0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1}

// Control prepares each probe socket before it connects; the Windows service uses it to pin the
// socket to the tunnel adapter, so the probe goes through the tunnel whatever the routes say.
type Control func(network, address string, c syscall.RawConn) error

// Probe sends a DNS query to each resolver and opens a TCP connection to each target on 443, all at
// once; the first answer of any kind wins. It returns the round trip, or false within timeout.
func Probe(dns []string, tcp []string, timeout time.Duration, control Control) (time.Duration, bool) {
	return ProbeAt(addrs(dns, 53, 4), addrs(tcp, 443, 3), timeout, control)
}

// ProbeAt is Probe with explicit ports (tests use local stand-ins).
func ProbeAt(dns, tcp []netip.AddrPort, timeout time.Duration, control Control) (time.Duration, bool) {
	if len(dns)+len(tcp) == 0 {
		return 0, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	start := time.Now()
	won := make(chan struct{}, len(dns)+len(tcp))
	d := net.Dialer{Control: control}
	for _, a := range tcp {
		go func(a netip.AddrPort) {
			if c, err := d.DialContext(ctx, "tcp", a.String()); err == nil {
				c.Close()
				won <- struct{}{}
			}
		}(a)
	}
	if len(dns) > 0 {
		lc := net.ListenConfig{Control: control}
		if pc, err := lc.ListenPacket(ctx, "udp4", ":0"); err == nil {
			defer pc.Close()
			id := uint16(start.UnixNano())
			q := append([]byte(nil), query...)
			binary.BigEndian.PutUint16(q, id)
			for _, a := range dns {
				pc.WriteTo(q, net.UDPAddrFromAddrPort(a))
			}
			pc.SetReadDeadline(start.Add(timeout))
			go func() {
				buf := make([]byte, 512)
				for {
					n, _, err := pc.ReadFrom(buf)
					if err != nil {
						return
					}
					if n >= 12 && binary.BigEndian.Uint16(buf) == id && buf[2]&0x80 != 0 {
						won <- struct{}{}
						return
					}
				}
			}()
		}
	}
	select {
	case <-won:
		rtt := time.Since(start)
		if rtt < time.Millisecond {
			rtt = time.Millisecond
		}
		return rtt, true
	case <-ctx.Done():
		return 0, false
	}
}

func addrs(list []string, port uint16, max int) []netip.AddrPort {
	var out []netip.AddrPort
	seen := map[netip.Addr]bool{}
	for _, s := range list {
		a, err := netip.ParseAddr(s)
		if err != nil || !a.Is4() || seen[a] {
			continue
		}
		seen[a] = true
		out = append(out, netip.AddrPortFrom(a, port))
		if len(out) == max {
			break
		}
	}
	return out
}
