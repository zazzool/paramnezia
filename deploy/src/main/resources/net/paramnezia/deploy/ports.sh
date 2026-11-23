#!/usr/bin/env bash
# Makes the AmneziaWG server answer on more UDP ports, so a client whose port is blocked moves to
# another. All ports lead to the same listener inside the container: only Docker's port publishing
# changes, the server config and every client (AmneziaVPN's too, on the main port) stay as they are.
#
# Docker cannot add a port to a running container, so a container that lacks some is re-created from
# a snapshot of itself (docker commit: the config lives inside it) with the same settings plus the
# new ports. The old one is only stopped until the new one has its interface up, and is put back if
# anything goes wrong. Ports are only ever added; a port in use on the host is skipped.
#
# Inputs (environment): CONTAINER, PN_PORTS (wanted extra host ports, space separated; empty: only report)
# Output: "::result::port=<main>", "::result::alt_ports=<p1,p2>", "::result::ipv6=<address or empty>",
#         "::result::changed=0|1".
set -eu
export LC_ALL=C
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
fail() { echo "::error::$1"; exit 1; }
C=${CONTAINER:-amnezia-awg2}
D="$SUDO docker"
$D inspect "$C" >/dev/null 2>&1 || fail "no_container"

lp=$($D exec "$C" sh -c "awk -F' *= *' '\$1==\"ListenPort\"{print \$2; exit}' /opt/amnezia/awg/awg0.conf /opt/amnezia/awg/wg0.conf 2>/dev/null" | tr -dc 0-9)
[ -n "$lp" ] || fail "no_listen_port"

# Host ports bound to the listener, and every binding as "<host>:<container>/<proto>".
bindings() { $D inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{.HostPort}}:{{$p}} {{end}}{{end}}' "$C"; }
ours() { bindings | tr ' ' '\n' | awk -F'[:/]' -v lp="$lp" '$2==lp && $3=="udp" && $1!=""{print $1}' | sort -un; }
busy() { (ss -lunH 2>/dev/null || netstat -lun 2>/dev/null) | awk '{print $4}' | grep -qE "[:.]$1\$"; }

have=$(ours)
want=""
for p in ${PN_PORTS:-}; do
  case "$p" in ''|*[!0-9]*) continue ;; esac
  [ "$p" -ge 1 ] && [ "$p" -le 65535 ] || continue
  printf '%s\n' "$have" | grep -qx "$p" && continue
  busy "$p" && continue
  want="$want $p"
done

changed=0
if [ -n "$want" ]; then
  echo "::step::ports"
  img="paramnezia/$C:snapshot"
  old_img=$($D image inspect -f '{{.Id}}' "$img" 2>/dev/null || true)
  $D commit "$C" "$img" >/dev/null || fail "commit_failed"
  args="-d --name $C --restart always --log-driver none"
  [ "$($D inspect -f '{{.HostConfig.Privileged}}' "$C")" = "true" ] && args="$args --privileged"
  [ "$($D inspect -f '{{.HostConfig.Init}}' "$C" 2>/dev/null)" = "true" ] && args="$args --init"
  for cap in $($D inspect -f '{{range .HostConfig.CapAdd}}{{.}} {{end}}' "$C"); do args="$args --cap-add=$cap"; done
  for s in $($D inspect -f '{{range $k, $v := .HostConfig.Sysctls}}{{$k}}={{$v}} {{end}}' "$C"); do args="$args --sysctl=$s"; done
  for m in $($D inspect -f '{{range .Mounts}}{{if eq .Type "bind"}}{{.Source}}:{{.Destination}} {{end}}{{end}}' "$C"); do args="$args -v $m"; done
  for b in $(bindings); do args="$args -p $b"; done
  for p in $want; do args="$args -p $p:$lp/udp"; done
  nets=$($D inspect -f '{{range $n, $_ := .NetworkSettings.Networks}}{{$n}} {{end}}' "$C")

  $D rename "$C" "$C-pnold" >/dev/null || fail "rename_failed"
  restore() {
    $D rm -f "$C" >/dev/null 2>&1 || true
    $D rename "$C-pnold" "$C" >/dev/null 2>&1 || true
    $D start "$C" >/dev/null 2>&1 || true
    fail "$1"
  }
  $D stop -t 5 "$C-pnold" >/dev/null 2>&1 || true
  # shellcheck disable=SC2086
  $D run $args "$img" >/dev/null 2>&1 || restore "run_failed"
  for n in $nets; do
    [ "$n" = "bridge" ] && continue
    $D network connect "$n" "$C" >/dev/null 2>&1 || true
  done
  up=""
  for _ in $(seq 1 20); do
    if $D exec "$C" sh -c 'awg show awg0 >/dev/null 2>&1 || wg show wg0 >/dev/null 2>&1'; then up=1; break; fi
    sleep 1
  done
  [ -n "$up" ] || restore "interface_not_up"
  $D rm -f "$C-pnold" >/dev/null 2>&1 || true
  # The previous snapshot, if nothing uses it any more.
  if [ -n "$old_img" ] && [ "$old_img" != "$($D image inspect -f '{{.Id}}' "$img" 2>/dev/null)" ]; then
    $D rmi "$old_img" >/dev/null 2>&1 || true
  fi
  changed=1
fi

alt=$(ours | grep -vx "$lp" | paste -sd, -)
# IPv6: the host's global address, if Docker accepts the main port on it.
v6=""
if (ss -6 -lunH 2>/dev/null || true) | awk '{print $4}' | grep -qE "^(\[::\]|\*|::):$lp\$" || \
   ($SUDO ip6tables -t nat -S DOCKER 2>/dev/null | grep -q -- "--dport $lp"); then
  v6=$(ip -6 addr show scope global 2>/dev/null | awk '/inet6/ && !/temporary|deprecated|tentative|dadfailed/ && $2 ~ /^[23][[:xdigit:]][[:xdigit:]][[:xdigit:]]:/ {print $2}' | cut -d/ -f1 | head -1)
fi
echo "::result::port=$lp"
echo "::result::alt_ports=$alt"
echo "::result::ipv6=$v6"
if $D exec "$C" sh -c 'ip -6 addr show dev awg0 | grep -q "fd38:706e:1::1/64" && ip -6 route show default | grep -q "^default"' 2>/dev/null; then
  echo "::result::tunnel_ipv6=1"
fi
# Only advertise a public IPv4 directly assigned to the SSH server. A private address can
# belong to an upstream NAT whose external endpoint cannot be inferred on this machine.
v4=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<NF;i++) if ($i=="src") {print $(i+1); exit}}')
v4=$(printf '%s\n' "$v4" | awk -F. 'NF==4 && $1>0 && $1<224 && $1!=10 && $1!=127 && !($1==100 && $2>=64 && $2<=127) && !($1==169 && $2==254) && !($1==172 && $2>=16 && $2<=31) && !($1==192 && $2==168)')
[ -z "$v4" ] || echo "::result::ipv4=$v4"
server_pub=$($D exec "$C" sh -c 'awg show awg0 public-key || wg show wg0 public-key' 2>/dev/null || true)
[ -z "$server_pub" ] || echo "::result::server_pub=$server_pub"
echo "::result::changed=$changed"
