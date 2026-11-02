//go:build windows

/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2019-2026 WireGuard LLC. All Rights Reserved.
 * Paramnezia: DNS-only variant of EnableFirewall for split tunneling.
 */

package firewall

import (
	"errors"
	"net/netip"
)

// BlockDNSLeaks blocks DNS to every resolver except dnsServers, and permits all traffic of the
// current process. Unlike EnableFirewall it does not block other traffic, so split routing works.
func BlockDNSLeaks(dnsServers []netip.Addr) error {
	if wfpSession != 0 {
		return errors.New("The firewall has already been enabled")
	}
	if len(dnsServers) == 0 {
		return errors.New("no DNS servers to allow")
	}
	session, err := createWfpSession()
	if err != nil {
		return wrapErr(err)
	}
	err = runTransaction(session, func(session uintptr) error {
		baseObjects, err := registerBaseObjects(session)
		if err != nil {
			return wrapErr(err)
		}
		if err = permitWireGuardService(session, baseObjects, 15); err != nil {
			return wrapErr(err)
		}
		if err = permitLoopback(session, baseObjects, 13); err != nil {
			return wrapErr(err)
		}
		return blockDNS(dnsServers, session, baseObjects, 15, 14)
	})
	if err != nil {
		fwpmEngineClose0(session)
		return wrapErr(err)
	}
	wfpSession = session
	return nil
}
