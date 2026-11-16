#!/usr/bin/env bash
# Tunes a server for bulk traffic through the tunnel (video above all). Idempotent; everything
# survives a reboot. Safe on AmneziaVPN's containers too.
#
#  - TCP MSS clamping in the container: a client whose tunnel MTU is larger than the server's own
#    interface (clients measure their path and may go up to 1420; a VPS on a 1450-byte network gives
#    its interface 1370) would otherwise get segments the server cannot forward, and stall wherever
#    ICMP "too big" is filtered. SYNs through the tunnel get an MSS that fits the server's interface,
#    in both directions.
#  - Host: larger socket buffers and input backlog for the bursts a video segment arrives in, BBR
#    with fq for the server's own TCP (the in-tunnel downloads), and IP forwarding, written to
#    /etc/sysctl.d so a reboot keeps them (before 2.17 they were set only until the next reboot).
#
# Inputs (environment): CONTAINER. Output: "::result::mss=ok|skipped", "::result::sysctl=ok|skipped".
set -eu
export LC_ALL=C
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
fail() { echo "::error::$1"; exit 1; }
C=${CONTAINER:-amnezia-awg2}
$SUDO docker inspect "$C" >/dev/null 2>&1 || fail "no_container"

# --- the container: MSS clamping, now and at every start -------------------------------------
$SUDO docker exec -i "$C" sh -c 'mkdir -p /opt/amnezia/paramnezia && cat > /opt/amnezia/paramnezia/tune.sh && chmod 755 /opt/amnezia/paramnezia/tune.sh' <<'TUNE'
#!/bin/sh
# Paramnezia: TCP MSS clamping for the tunnel (see tune.sh on the host side).
for IF in awg0 wg0; do [ -e /sys/class/net/$IF ] && break; done
[ -e /sys/class/net/$IF ] || exit 0
mtu=$(cat /sys/class/net/$IF/mtu 2>/dev/null || echo 1420)
mss=$((mtu - 40))
rule_out="FORWARD -o $IF -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
rule_in="FORWARD -i $IF -p tcp --tcp-flags SYN,RST SYN -m tcpmss --mss $((mss + 1)):65535 -j TCPMSS --set-mss $mss"
# shellcheck disable=SC2086
iptables -t mangle -C $rule_out 2>/dev/null || iptables -t mangle -A $rule_out
# shellcheck disable=SC2086
iptables -t mangle -C $rule_in 2>/dev/null || iptables -t mangle -A $rule_in
TUNE
mss=skipped
if $SUDO docker exec "$C" /opt/amnezia/paramnezia/tune.sh 2>/dev/null; then mss=ok; fi
# Run it from the container's start script too (after the interface is up, before it idles).
$SUDO docker exec "$C" sh -c '
  f=/opt/amnezia/start.sh
  [ -f "$f" ] || exit 0
  grep -q "/opt/amnezia/paramnezia/tune.sh" "$f" && exit 0
  if grep -q "^tail -f /dev/null" "$f"; then
    sed -i "s#^tail -f /dev/null#[ -x /opt/amnezia/paramnezia/tune.sh ] \&\& /opt/amnezia/paramnezia/tune.sh\ntail -f /dev/null#" "$f"
  fi' || true
echo "::result::mss=$mss"

# --- the host: buffers, backlog, BBR, forwarding; persisted -----------------------------------
want="# Paramnezia: tunnel throughput (see tune.sh)
net.ipv4.ip_forward = 1
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.core.netdev_max_backlog = 16384"
if $SUDO modprobe tcp_bbr 2>/dev/null || grep -qw bbr /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null; then
  want="$want
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr"
fi
sysctl=skipped
if [ -d /etc/sysctl.d ]; then
  f=/etc/sysctl.d/90-paramnezia.conf
  if [ "$($SUDO cat "$f" 2>/dev/null || true)" != "$want" ]; then
    printf '%s\n' "$want" | $SUDO tee "$f.new" >/dev/null && $SUDO mv "$f.new" "$f"
  fi
  $SUDO sysctl -q -p "$f" >/dev/null 2>&1 && sysctl=ok
fi
echo "::result::sysctl=$sysctl"
