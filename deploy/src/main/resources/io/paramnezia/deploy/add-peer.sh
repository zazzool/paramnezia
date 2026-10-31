#!/usr/bin/env bash
# Adds a client to an AmneziaWG server installed by Paramnezia or AmneziaVPN, the way AmneziaVPN does:
# append a [Peer] to the server config, sync the live interface, record the name in clientsTable.
# The client's private key never reaches the server: we only get its public key.
#
# Inputs (environment): CONTAINER, CLIENT_PUB, CLIENT_NAME, optional CLIENT_IP (fleet devices keep
# the same address on every server)
# Output: "::result::client_ip=…", "::result::server_pub=…", "::result::psk=…", then the server
#         config without private keys between ::conf-begin:: and ::conf-end::.
set -eu
export LC_ALL=C
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi

$SUDO docker exec -i -e CLIENT_PUB -e CLIENT_NAME -e CLIENT_IP="${CLIENT_IP:-}" -e CREATED="$(date -u '+%a %b %d %H:%M:%S %Y')" "$CONTAINER" bash -s <<'PEER'
set -eu
cd /opt/amnezia/awg
if [ -f awg0.conf ]; then CONF=awg0.conf; IFACE=awg0; BIN=awg; else CONF=wg0.conf; IFACE=wg0; BIN=wg; command -v wg >/dev/null || BIN=awg; fi

exec 9>/opt/amnezia/awg/.lock
flock 9 2>/dev/null || true

if grep -q "^PublicKey = $CLIENT_PUB\$" "$CONF"; then
  ip=$(awk -v k="$CLIENT_PUB" '$1=="PublicKey"&&$3==k{f=1;next} f&&$1=="AllowedIPs"{split($3,a,"/");print a[1];exit}' "$CONF")
elif [ -n "$CLIENT_IP" ] && grep -q "^AllowedIPs = $CLIENT_IP/32" "$CONF"; then
  echo "::error::address_taken"; exit 1
else
  subnet=$(awk -F' = ' '$1=="Address"{print $2;exit}' "$CONF" | cut -d/ -f1)
  last=$(grep -E '^AllowedIPs = [0-9.]+' "$CONF" | sed -E 's/^AllowedIPs = ([0-9.]+).*/\1/' | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n | tail -1)
  base=${last:-$subnet}
  IFS=. read -r a b c d <<EOF
$base
EOF
  n=$(( (a<<24) + (b<<16) + (c<<8) + d + 1 ))
  # Skip network/broadcast-looking addresses, as AmneziaVPN does.
  case $((n & 255)) in 0) n=$((n + 1)) ;; 255) n=$((n + 2)) ;; esac
  ip="$(( (n>>24)&255 )).$(( (n>>16)&255 )).$(( (n>>8)&255 )).$(( n&255 ))"
  [ -n "$CLIENT_IP" ] && ip=$CLIENT_IP
  psk=$(cat wireguard_psk.key)
  printf '[Peer]\nPublicKey = %s\nPresharedKey = %s\nAllowedIPs = %s/32\n\n' "$CLIENT_PUB" "$psk" "$ip" >> "$CONF"
  "$BIN" syncconf "$IFACE" <("$BIN-quick" strip "/opt/amnezia/awg/$CONF")

  [ -s clientsTable ] || echo "[]" > clientsTable
  if command -v jq >/dev/null 2>&1; then
    jq --arg id "$CLIENT_PUB" --arg n "$CLIENT_NAME" --arg d "$CREATED" \
      '. + [{clientId:$id, userData:{clientName:$n, creationDate:$d}}]' clientsTable > clientsTable.new
  else
    # Minimal JSON append without jq; names are escaped for quotes and backslashes.
    name=$(printf '%s' "$CLIENT_NAME" | sed 's/\\/\\\\/g; s/"/\\"/g')
    entry=$(printf '{"clientId": "%s", "userData": {"clientName": "%s", "creationDate": "%s"}}' "$CLIENT_PUB" "$name" "$CREATED")
    body=$(tr -d '\n' < clientsTable | sed -E 's/^[[:space:]]*\[//; s/\][[:space:]]*$//')
    if printf '%s' "$body" | grep -q '[^[:space:]]'; then printf '[%s, %s]\n' "$body" "$entry"; else printf '[%s]\n' "$entry"; fi > clientsTable.new
  fi
  mv clientsTable.new clientsTable
fi

echo "::result::client_ip=$ip"
echo "::result::server_pub=$(cat wireguard_server_public_key.key)"
echo "::result::psk=$(cat wireguard_psk.key)"
echo "::conf-begin::"
sed -n '1,/^\[Peer\]/p' "$CONF" | grep -v -e '^PrivateKey' -e '^\[Peer\]'
echo "::conf-end::"
PEER
