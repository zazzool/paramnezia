#!/usr/bin/env bash
# What an administrator wants to know about a server, in one look and without changing anything:
# the AmneziaWG engine and its age, the interface, peers (how many are active right now), traffic,
# the ports clients can use, the throughput tuning, and the host itself.
#
# Inputs (environment): CONTAINER
# Output: "::result::<key>=<value>" lines; a missing piece is left out, never fatal.
set -u
export LC_ALL=C
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
C=${CONTAINER:-amnezia-awg2}
D="$SUDO docker"
r() { printf '::result::%s=%s\n' "$1" "$(printf '%s' "$2" | tr '\n\r' '  ' | sed 's/[[:space:]]*$//')"; }

# --- the host ----------------------------------------------------------------------------------
r os "$( (. /etc/os-release 2>/dev/null && printf '%s' "$PRETTY_NAME") || uname -s)"
r kernel "$(uname -r)"
r uptime "$(cut -d' ' -f1 /proc/uptime 2>/dev/null | cut -d. -f1)"
r load "$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
r cpus "$(nproc 2>/dev/null)"
r mem "$(awk '/^MemTotal/{t=$2} /^MemAvailable/{a=$2} END{if(t) printf "%d %d", t/1024, a/1024}' /proc/meminfo 2>/dev/null)"
r cc "$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)"
r docker "$($D version --format '{{.Server.Version}}' 2>/dev/null)"
r tuned "$([ -f /etc/sysctl.d/90-paramnezia.conf ] && echo 1 || echo 0)"

# --- the container -------------------------------------------------------------------------------
if ! $D inspect "$C" >/dev/null 2>&1; then
  r container missing
  exit 0
fi
r container "$($D inspect -f '{{.State.Status}}' "$C" 2>/dev/null)"
r started "$($D inspect -f '{{.State.StartedAt}}' "$C" 2>/dev/null)"
img=$($D inspect -f '{{.Image}}' "$C" 2>/dev/null)
r image_created "$($D image inspect -f '{{.Created}}' "$img" 2>/dev/null)"
r engine "$($D exec "$C" sh -c 'amneziawg-go --version 2>/dev/null | head -1' 2>/dev/null)"
r engine_sum "$($D exec "$C" sh -c 'sha256sum "$(command -v amneziawg-go)" 2>/dev/null | cut -c1-12' 2>/dev/null)"
r tools "$($D exec "$C" sh -c 'awg --version 2>/dev/null | head -1 | cut -d" " -f1-2' 2>/dev/null)"

IF=awg0
$D exec "$C" sh -c '[ -e /sys/class/net/awg0 ]' 2>/dev/null || IF=wg0
r iface "$IF"
r mtu "$($D exec "$C" cat /sys/class/net/$IF/mtu 2>/dev/null)"
now=$(date +%s)
hs=$($D exec "$C" sh -c "awg show $IF latest-handshakes 2>/dev/null || wg show $IF latest-handshakes" 2>/dev/null)
r peers "$(printf '%s\n' "$hs" | grep -c .)"
r active "$(printf '%s\n' "$hs" | awk -v now="$now" '$2>0 && now-$2<180{n++} END{print n+0}')"
r transfer "$($D exec "$C" sh -c "awg show $IF transfer 2>/dev/null || wg show $IF transfer" 2>/dev/null | awk '{rx+=$2; tx+=$3} END{printf "%d %d", rx, tx}')"
r mss "$($D exec "$C" iptables -t mangle -S FORWARD 2>/dev/null | grep -c TCPMSS)"

# Ports clients can use: every host port bound to the listener.
lp=$($D exec "$C" sh -c "awk -F' *= *' '\$1==\"ListenPort\"{print \$2; exit}' /opt/amnezia/awg/awg0.conf /opt/amnezia/awg/wg0.conf 2>/dev/null" | tr -dc 0-9)
if [ -n "$lp" ]; then
  r listen "$lp"
  r ports "$($D inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{.HostPort}}:{{$p}} {{end}}{{end}}' "$C" | tr ' ' '\n' \
    | awk -F'[:/]' -v lp="$lp" '$2==lp && $3=="udp" && $1!=""{print $1}' | sort -un | paste -sd, -)"
  if (ss -lunH 2>/dev/null || true) | awk '{print $4}' | grep -qE "^(\[::\]|\*|::):$lp\$"; then
    r ipv6 "$(ip -6 addr show scope global 2>/dev/null | awk '/inet6/ && !/temporary|deprecated|tentative/ {print $2}' | cut -d/ -f1 | grep -vi '^f[cd]' | head -1)"
  fi
fi
r invite "$($D inspect -f '{{.State.Status}}' paramnezia-invite 2>/dev/null)"
