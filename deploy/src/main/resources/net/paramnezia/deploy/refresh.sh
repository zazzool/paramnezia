#!/usr/bin/env bash
# Brings the AmneziaWG server engine up to date: AmneziaVPN rebuilds amneziavpn/amneziawg-go:latest
# all the time, while a container keeps the binaries of the day it was installed. When the latest
# image has other amneziawg-go / awg binaries than the running container, a fresh image is built
# from it (the same Dockerfile as install-awg.sh) and the container is re-created on it with the
# same settings, ports and everything under /opt/amnezia (keys, awg0.conf, clients, start.sh, the
# in-tunnel web server's files). The old container is only stopped until the new one has its
# interface up, and is put back if anything goes wrong. Nothing changes when the engine is current.
#
# On an AmneziaWG 3 server (HeaderProtectionKey set) without its own content padding, the refreshed
# container also gets ContentPaddingAddition = 0-31 (see ObfuscationProfile): the engine that reads
# it is the one just installed.
#
# Inputs (environment): CONTAINER (only amnezia-awg2)
# Output: "::result::refreshed=0|1", "::result::engine=<sha256 prefix>".
set -eu
export LC_ALL=C
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
fail() { echo "::error::$1"; exit 1; }
C=${CONTAINER:-amnezia-awg2}
D="$SUDO docker"
[ "$C" = amnezia-awg2 ] || fail "no_container"
$D inspect "$C" >/dev/null 2>&1 || fail "no_container"
BASE=amneziavpn/amneziawg-go:latest
IMG=paramnezia/amnezia-awg2:latest

sums='for b in amneziawg-go awg; do p=$(command -v $b) && sha256sum "$p" | cut -c1-16; done'
have=$($D exec "$C" sh -c "$sums" 2>/dev/null | tr '\n' ' ')
$D pull -q "$BASE" >/dev/null 2>&1 || fail "pull_failed"
latest=$($D run --rm --entrypoint sh "$BASE" -c "$sums" 2>/dev/null | tr '\n' ' ')
[ -n "$latest" ] || fail "pull_failed"
if [ "$have" = "$latest" ]; then
  echo "::result::refreshed=0"
  echo "::result::engine=${latest%% *}"
  exit 0
fi

echo "::step::image"
dir=/opt/amnezia/paramnezia/image
$SUDO mkdir -p "$dir"
$SUDO tee "$dir/Dockerfile" >/dev/null <<'DOCKERFILE'
FROM amneziavpn/amneziawg-go:latest
RUN apk add --no-cache jq busybox-extras >/dev/null 2>&1 || true
RUN mkdir -p /opt/amnezia && printf '#!/bin/bash\ntail -f /dev/null\n' > /opt/amnezia/start.sh && chmod a+x /opt/amnezia/start.sh
ENTRYPOINT [ "/bin/bash", "/opt/amnezia/start.sh" ]
DOCKERFILE
old_img=$($D image inspect -f '{{.Id}}' "$IMG" 2>/dev/null || true)
$D build -q -t "$IMG" "$dir" >/dev/null 2>&1 || fail "build_failed"

# Everything the server keeps lives under /opt/amnezia inside the container.
# (docker cp writes as root: without root everything here goes through sudo.)
tmp=$($SUDO mktemp -d)
trap '$SUDO rm -rf "$tmp"' EXIT
$D cp "$C:/opt/amnezia/." "$tmp/" || fail "copy_failed"
conf=$tmp/awg/awg0.conf
if $SUDO test -f "$conf" && $SUDO grep -q '^HeaderProtectionKey' "$conf" && ! $SUDO grep -q '^ContentPaddingAddition' "$conf"; then
  $SUDO sed -i 's/^\(HeaderProtectionKey = .*\)$/\1\nContentPaddingAddition = 0-31/' "$conf"
fi

args="--name $C --restart always --log-driver none"
[ "$($D inspect -f '{{.HostConfig.Privileged}}' "$C")" = "true" ] && args="$args --privileged"
# Our image has no init of its own (AmneziaVPN's has dumb-init): a tiny PID 1 that reaps zombies.
args="$args --init"
for cap in $($D inspect -f '{{range .HostConfig.CapAdd}}{{.}} {{end}}' "$C"); do args="$args --cap-add=$cap"; done
for s in $($D inspect -f '{{range $k, $v := .HostConfig.Sysctls}}{{$k}}={{$v}} {{end}}' "$C"); do args="$args --sysctl=$s"; done
for m in $($D inspect -f '{{range .Mounts}}{{if eq .Type "bind"}}{{.Source}}:{{.Destination}} {{end}}{{end}}' "$C"); do args="$args -v $m"; done
for b in $($D inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{.HostPort}}:{{$p}} {{end}}{{end}}' "$C"); do args="$args -p $b"; done
nets=$($D inspect -f '{{range $n, $_ := .NetworkSettings.Networks}}{{$n}} {{end}}' "$C")
primary_net=$($D inspect -f '{{.HostConfig.NetworkMode}}' "$C")
if [ "$primary_net" = pn-dualstack ]; then args="$args --network $primary_net"; fi

echo "::step::switch"
$D rename "$C" "$C-pnold" >/dev/null || fail "rename_failed"
restore() {
  $D rm -f "$C" >/dev/null 2>&1 || true
  $D rename "$C-pnold" "$C" >/dev/null 2>&1 || true
  $D start "$C" >/dev/null 2>&1 || true
  fail "$1"
}
$D stop -t 5 "$C-pnold" >/dev/null 2>&1 || true
# shellcheck disable=SC2086
$D create $args "$IMG" >/dev/null 2>&1 || restore "create_failed"
$D cp "$tmp/." "$C:/opt/amnezia/" >/dev/null 2>&1 || restore "copy_failed"
for n in $nets; do
  [ "$n" = "bridge" ] && continue
  [ "$n" = pn-dualstack ] && [ "$primary_net" = "$n" ] && continue
  $D network connect "$n" "$C" >/dev/null 2>&1 || true
done
$D start "$C" >/dev/null 2>&1 || restore "start_failed"
up=""
for _ in $(seq 1 20); do
  if $D exec "$C" awg show awg0 >/dev/null 2>&1; then up=1; break; fi
  sleep 1
done
[ -n "$up" ] || restore "interface_not_up"
$D rm -f "$C-pnold" >/dev/null 2>&1 || true
if [ -n "$old_img" ] && [ "$old_img" != "$($D image inspect -f '{{.Id}}' "$IMG" 2>/dev/null)" ]; then
  $D rmi "$old_img" >/dev/null 2>&1 || true
fi
# A snapshot ports.sh left behind is not needed any more (refused while something still uses it).
$D rmi paramnezia/amnezia-awg2:snapshot >/dev/null 2>&1 || true
echo "::result::refreshed=1"
echo "::result::engine=${latest%% *}"
