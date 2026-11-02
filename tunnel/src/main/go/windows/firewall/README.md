# firewall

Windows Filtering Platform rules, copied from wireguard-windows v1.1.1 (`tunnel/firewall`, MIT,
Copyright © 2019-2026 WireGuard LLC). Only display names were changed and `dnsonly.go` added.

Paramnezia splits traffic (Russian addresses go past the tunnel), so the kill switch of
`EnableFirewall` (block everything but the tunnel) cannot be used. `BlockDNSLeaks` installs only:

- permit everything for the Paramnezia service process itself (its direct DNS and the direct path);
- block DNS (port 53) to any resolver except the listed ones (the virtual resolver in the tunnel).

Without it, Windows asks the DNS servers of every network adapter at once ("smart multi-homed
name resolution"), and the provider's resolver could answer first with a stub address.

All objects live in a dynamic WFP session: they disappear when the service stops or crashes.
