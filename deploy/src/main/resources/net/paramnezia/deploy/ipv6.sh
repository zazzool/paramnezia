#!/usr/bin/env bash
# Add IPv6 internet access to an existing Paramnezia container with a native IPv6 uplink.
set -eu
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
D="$SUDO docker"
C=${CONTAINER:-amnezia-awg2}
net=paramnezia-ipv6
# Route lookup sends no traffic. Require a global unicast source before changing the host.
ip -6 route get 2606:4700:4700::1111 2>/dev/null | grep -qE ' src [23][[:xdigit:]]{3}:' || exit 0
# Third-party containers can have a different startup and firewall policy.
$D exec "$C" grep -q 'Paramnezia fleet manifest' /opt/amnezia/start.sh || exit 0
if ! $D network inspect "$net" >/dev/null 2>&1; then
  $D network create --ipv4=false --ipv6 --subnet fd38:706e:2::/64 "$net" >/dev/null
fi
if [ -z "$($D inspect -f '{{with index .NetworkSettings.Networks "paramnezia-ipv6"}}{{.GlobalIPv6Address}}{{end}}' "$C")" ]; then
  $D network connect "$net" "$C"
fi
$D exec -i "$C" bash -s <<'CONFIG'
set -eu
cd /opt/amnezia/awg
exec 9>.lock
flock 9
conf=awg0.conf
ip -6 route show default | grep -q '^default'
grep -q '^Address = .*fd38:706e:1::1/64' "$conf" && exit 0
command -v ip6tables >/dev/null
temporary=$(mktemp -d .ipv6.XXXXXX)
trap 'rm -rf "$temporary"' EXIT
next="$temporary/$conf"
awk '
  /^Address = / && !peer {
    print $0 ", fd38:706e:1::1/64"
    print "PostUp = sysctl -qw net.ipv6.conf.all.forwarding=1"
    print "PostUp = ip6tables -I FORWARD -i %i -s fd38:706e:1::/64 -j ACCEPT; ip6tables -I FORWARD -o %i -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; ip6tables -t nat -A POSTROUTING -s fd38:706e:1::/64 ! -o %i -j MASQUERADE"
    print "PostDown = ip6tables -D FORWARD -i %i -s fd38:706e:1::/64 -j ACCEPT; ip6tables -D FORWARD -o %i -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; ip6tables -t nat -D POSTROUTING -s fd38:706e:1::/64 ! -o %i -j MASQUERADE"
    next
  }
  /^\[Peer\]/ { peer=1 }
  peer && /^AllowedIPs = [0-9.]+\/32([,[:space:]]|$)/ && !/fd38:706e:1:/ {
    split($3, addr, /[.\/]/)
    printf "%s, fd38:706e:1::%x:%x/128\n", $0, addr[1]*256+addr[2], addr[3]*256+addr[4]
    next
  }
  { print }
' "$conf" > "$next"
chmod 600 "$next"
grep -q '^Address = .*fd38:706e:1::1/64' "$next"
awg-quick strip "$next" > "$temporary/new"
# Bring the additional address and rules up without interrupting established IPv4 peers.
sysctl -qw net.ipv6.conf.all.forwarding=1
ip -6 address replace fd38:706e:1::1/64 dev awg0
ip6tables -C FORWARD -i awg0 -s fd38:706e:1::/64 -j ACCEPT 2>/dev/null ||
  ip6tables -I FORWARD -i awg0 -s fd38:706e:1::/64 -j ACCEPT
ip6tables -C FORWARD -o awg0 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null ||
  ip6tables -I FORWARD -o awg0 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
ip6tables -t nat -C POSTROUTING -s fd38:706e:1::/64 ! -o awg0 -j MASQUERADE 2>/dev/null ||
  ip6tables -t nat -A POSTROUTING -s fd38:706e:1::/64 ! -o awg0 -j MASQUERADE
awg syncconf awg0 "$temporary/new"
mv "$next" "$conf"
CONFIG
