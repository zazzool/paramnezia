/* SPDX-License-Identifier: Apache-2.0 */

// Package splitapp sends chosen programs past the VPN on Windows without a kernel driver.
//
// Windows routes by destination only: it has no per-program routes and no equivalent of Android's
// addDisallowedApplication. The tools that do it (Mullvad, Proton, NordVPN) ship a WFP callout
// driver that rewrites the program's bind/connect to the physical interface, and a kernel driver
// only loads when Microsoft has signed it, which needs an EV certificate. Paramnezia has none.
//
// So the decision is made where the packets already are: in the service, between Wintun and
// amneziawg-go. For the first packet of every TCP or UDP flow the service asks Windows which
// process owns the local port (GetExtendedTcpTable / GetExtendedUdpTable, the same tables
// `netstat -b` shows). If that program is on the list, the flow does not go to the server: a
// small user-space TCP/IP stack (gVisor's netstack, the one wireguard-go uses) answers the program
// in place of the destination and opens the real connection from the service, bound to the
// physical interface (IP_UNICAST_IF). The program sees an ordinary connection; the site sees the
// home address. Everything else goes into the tunnel unchanged.
package splitapp

import (
	"path/filepath"
	"strings"
)

// Rules is the set of programs that go direct. An entry with a slash or backslash is a full path;
// otherwise it is a file name ("telegram.exe") and matches that program wherever it is installed,
// which survives updates that move programs into versioned folders. Case does not matter.
type Rules struct {
	names map[string]bool
	paths map[string]bool
}

func NewRules(list []string) *Rules {
	r := &Rules{names: map[string]bool{}, paths: map[string]bool{}}
	for _, e := range list {
		e = strings.ToLower(strings.TrimSpace(e))
		if e == "" {
			continue
		}
		if strings.ContainsAny(e, `\/`) {
			r.paths[normPath(e)] = true
		} else {
			r.names[e] = true
		}
	}
	return r
}

func (r *Rules) Empty() bool { return r == nil || len(r.names)+len(r.paths) == 0 }

// Match reports whether the program at path (as Windows reports it, e.g.
// C:\Program Files\Telegram\Telegram.exe) goes direct.
func (r *Rules) Match(path string) bool {
	if r.Empty() || path == "" {
		return false
	}
	p := normPath(strings.ToLower(path))
	if r.paths[p] {
		return true
	}
	return r.names[baseName(p)]
}

func normPath(p string) string { return strings.ReplaceAll(p, "/", `\`) }

func baseName(p string) string {
	if i := strings.LastIndexByte(p, '\\'); i >= 0 {
		return p[i+1:]
	}
	return filepath.Base(p)
}
