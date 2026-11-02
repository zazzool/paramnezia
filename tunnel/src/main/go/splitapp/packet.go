/* SPDX-License-Identifier: Apache-2.0 */

package splitapp

import (
	"encoding/binary"
	"net/netip"
)

const (
	tcpFIN = 0x01
	tcpSYN = 0x02
	tcpRST = 0x04
	tcpACK = 0x10
)

// parseFlow reads the 5-tuple of an IPv4/IPv6 TCP or UDP packet and the TCP flags. Fragments after
// the first and IPv6 packets with extension headers are not classified (they stay in the tunnel).
func parseFlow(p []byte) (k flowKey, flags uint8, ok bool) {
	if len(p) < 20 {
		return
	}
	var l4 []byte
	switch p[0] >> 4 {
	case 4:
		ihl := int(p[0]&0x0f) * 4
		if ihl < 20 || len(p) < ihl+8 {
			return
		}
		if binary.BigEndian.Uint16(p[6:8])&0x1fff != 0 { // not the first fragment
			return
		}
		k.proto = p[9]
		src := netip.AddrFrom4([4]byte(p[12:16]))
		dst := netip.AddrFrom4([4]byte(p[16:20]))
		l4 = p[ihl:]
		k.src = netip.AddrPortFrom(src, binary.BigEndian.Uint16(l4[0:2]))
		k.dst = netip.AddrPortFrom(dst, binary.BigEndian.Uint16(l4[2:4]))
	case 6:
		if len(p) < 48 {
			return
		}
		k.proto = p[6]
		src := netip.AddrFrom16([16]byte(p[8:24]))
		dst := netip.AddrFrom16([16]byte(p[24:40]))
		l4 = p[40:]
		k.src = netip.AddrPortFrom(src, binary.BigEndian.Uint16(l4[0:2]))
		k.dst = netip.AddrPortFrom(dst, binary.BigEndian.Uint16(l4[2:4]))
	default:
		return
	}
	switch k.proto {
	case 6:
		if len(l4) < 20 {
			return
		}
		flags = l4[13]
	case 17:
	default:
		return
	}
	// Multicast and broadcast are not connections to anyone in particular: leave them alone.
	if !k.dst.Addr().IsGlobalUnicast() {
		return
	}
	return k, flags, true
}
