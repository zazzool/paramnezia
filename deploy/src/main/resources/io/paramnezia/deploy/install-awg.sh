#!/usr/bin/env bash
# Installs an AmneziaWG server in the same Docker layout AmneziaVPN uses, so both apps can manage it:
#   container  amnezia-awg2 (image built from amneziavpn/amneziawg-go)
#   config     /opt/amnezia/awg/awg0.conf, keys next to it, clientsTable for user names
#   network    amnezia-dns-net (172.29.172.0/24), where AmneziaDNS lives if installed later
#
# Inputs (environment): AWG_PORT, AWG_SUBNET, AWG_CIDR, AWG_PARAMS (wg-quick lines), PN_REINSTALL (0/1)
# Output: "::step::<name>" progress markers, then "::result::<key>=<value>" lines.
set -eu
export LC_ALL=C DEBIAN_FRONTEND=noninteractive

CONTAINER=amnezia-awg2
FOLDER=/opt/amnezia/$CONTAINER
step() { echo "::step::$1"; }
fail() { echo "::error::$1"; exit 1; }

if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
step check
if [ -n "$SUDO" ] && ! sudo -n true 2>/dev/null; then fail "sudo_password_required"; fi

pm=""
for c in apt-get dnf yum zypper pacman; do
  if command -v "$c" >/dev/null 2>&1; then pm=$c; break; fi
done

pkg_install() {
  case "$pm" in
    apt-get) $SUDO apt-get -yq update >/dev/null && $SUDO apt-get -yq install "$@" >/dev/null ;;
    dnf|yum) $SUDO "$pm" -yq install "$@" >/dev/null ;;
    zypper) $SUDO zypper -nq install "$@" >/dev/null ;;
    pacman) $SUDO pacman -S --noconfirm --noprogressbar --quiet "$@" >/dev/null ;;
    *) fail "no_package_manager" ;;
  esac
}

step docker
if ! command -v docker >/dev/null 2>&1; then
  case "$pm" in
    apt-get) pkg_install docker.io ;;
    *) pkg_install docker ;;
  esac
  $SUDO systemctl enable --now docker >/dev/null 2>&1 || $SUDO service docker start >/dev/null 2>&1 || true
fi
for _ in 1 2 3 4 5 6 7 8 9 10; do $SUDO docker info >/dev/null 2>&1 && break; sleep 2; done
$SUDO docker info >/dev/null 2>&1 || fail "docker_not_running"

if $SUDO docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  if [ "${PN_REINSTALL:-0}" = "1" ]; then
    $SUDO docker rm -f "$CONTAINER" >/dev/null
  else
    fail "already_installed"
  fi
fi

step image
$SUDO mkdir -p "$FOLDER"
$SUDO tee "$FOLDER/Dockerfile" >/dev/null <<'DOCKERFILE'
FROM amneziavpn/amneziawg-go:latest
# The base image already has bash, iptables, awg and flock; jq only makes clientsTable edits nicer.
RUN apk add --no-cache jq >/dev/null 2>&1 || true
RUN mkdir -p /opt/amnezia && printf '#!/bin/bash\ntail -f /dev/null\n' > /opt/amnezia/start.sh && chmod a+x /opt/amnezia/start.sh
ENTRYPOINT [ "/bin/bash", "/opt/amnezia/start.sh" ]
DOCKERFILE
$SUDO docker build --pull -q -t "$CONTAINER" "$FOLDER" >/dev/null || fail "docker_build_failed"

step run
if ! $SUDO docker network ls --format '{{.Name}}' | grep -qx amnezia-dns-net; then
  $SUDO docker network create --driver bridge --subnet=172.29.172.0/24 \
    --opt com.docker.network.bridge.name=amn0 amnezia-dns-net >/dev/null
fi
# --init gives the container a tiny PID 1 that reaps zombies, which dumb-init does in AmneziaVPN's image.
$SUDO docker run -d --init --log-driver none --restart always --privileged \
  --cap-add=NET_ADMIN --cap-add=SYS_MODULE \
  -p "$AWG_PORT:$AWG_PORT/udp" -v /lib/modules:/lib/modules \
  --sysctl="net.ipv4.conf.all.src_valid_mark=1" \
  --name "$CONTAINER" "$CONTAINER" >/dev/null || fail "docker_run_failed"
$SUDO docker network connect amnezia-dns-net "$CONTAINER" >/dev/null 2>&1 || true

step configure
# Values go in through the environment, never through shell quoting.
$SUDO docker exec -i -e AWG_PORT -e AWG_SUBNET -e AWG_CIDR -e AWG_PARAMS "$CONTAINER" bash -s <<'CONFIGURE'
set -eu
mkdir -p /opt/amnezia/awg
cd /opt/amnezia/awg
umask 077
priv=$(awg genkey)
echo "$priv" > wireguard_server_private_key.key
echo "$priv" | awg pubkey > wireguard_server_public_key.key
awg genpsk > wireguard_psk.key
{
  echo "[Interface]"
  echo "PrivateKey = $priv"
  echo "Address = $AWG_SUBNET/$AWG_CIDR"
  echo "ListenPort = $AWG_PORT"
  # AmneziaVPN keeps I1-I5 commented out in the server config; servers ignore them.
  printf '%s\n' "$AWG_PARAMS" | sed -E 's/^(I[1-5] = )/# \1/'
  echo
} > awg0.conf
[ -f clientsTable ] || echo "[]" > clientsTable
CONFIGURE

step start
$SUDO docker exec -i -e AWG_SUBNET -e AWG_CIDR "$CONTAINER" bash -c 'cat > /opt/amnezia/start.sh' <<START
#!/bin/bash
echo "Container startup"
awg-quick down /opt/amnezia/awg/awg0.conf 2>/dev/null
if [ -f /opt/amnezia/awg/awg0.conf ]; then awg-quick up /opt/amnezia/awg/awg0.conf; fi
iptables -A INPUT -i awg0 -j ACCEPT
iptables -A FORWARD -i awg0 -j ACCEPT
iptables -A OUTPUT -o awg0 -j ACCEPT
iptables -A FORWARD -i awg0 -o eth0 -s $AWG_SUBNET/$AWG_CIDR -j ACCEPT
iptables -A FORWARD -i awg0 -o eth1 -s $AWG_SUBNET/$AWG_CIDR -j ACCEPT
iptables -A FORWARD -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -t nat -A POSTROUTING -s $AWG_SUBNET/$AWG_CIDR -o eth0 -j MASQUERADE
iptables -t nat -A POSTROUTING -s $AWG_SUBNET/$AWG_CIDR -o eth1 -j MASQUERADE
tail -f /dev/null
START
$SUDO docker exec -d "$CONTAINER" sh -c "chmod a+x /opt/amnezia/start.sh && /opt/amnezia/start.sh"

step firewall
$SUDO sysctl -qw net.ipv4.ip_forward=1 || true
if command -v iptables >/dev/null 2>&1; then
  $SUDO iptables -C FORWARD -j DOCKER-USER 2>/dev/null || $SUDO iptables -A FORWARD -j DOCKER-USER 2>/dev/null || true
fi
# BBR keeps throughput up on lossy mobile links at no extra cost.
if $SUDO modprobe tcp_bbr 2>/dev/null || grep -qw bbr /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null; then
  $SUDO sysctl -qw net.core.default_qdisc=fq net.ipv4.tcp_congestion_control=bbr 2>/dev/null || true
fi

for _ in 1 2 3 4 5 6 7 8 9 10; do
  $SUDO docker exec "$CONTAINER" awg show awg0 >/dev/null 2>&1 && break
  sleep 1
done
$SUDO docker exec "$CONTAINER" awg show awg0 >/dev/null 2>&1 || fail "interface_not_up"

step done
echo "::result::server_pub=$($SUDO docker exec "$CONTAINER" cat /opt/amnezia/awg/wireguard_server_public_key.key)"
echo "::result::psk=$($SUDO docker exec "$CONTAINER" cat /opt/amnezia/awg/wireguard_psk.key)"
