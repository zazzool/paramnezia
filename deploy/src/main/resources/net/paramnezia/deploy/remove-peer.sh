#!/usr/bin/env bash
# Revokes a client: drops its [Peer] block and clientsTable entry, then syncs the live interface.
# Inputs (environment): CONTAINER, CLIENT_PUB
set -eu
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
$SUDO docker exec -i -e "CLIENT_PUB=$CLIENT_PUB" "$CONTAINER" bash -s <<'REMOVE'
set -eu
cd /opt/amnezia/awg
umask 077
if [ -f awg0.conf ]; then CONF=awg0.conf; IFACE=awg0; BIN=awg; else CONF=wg0.conf; IFACE=wg0; BIN=wg; command -v wg >/dev/null || BIN=awg; fi
exec 9>/opt/amnezia/awg/.lock
flock 9 2>/dev/null || true
awk -v k="$CLIENT_PUB" '
  /^\[Peer\]/ { if (blk != "" && !drop) printf "%s", blk; blk = $0 "\n"; drop = 0; inpeer = 1; next }
  inpeer { blk = blk $0 "\n"; if ($1 == "PublicKey" && $3 == k) drop = 1; next }
  { print }
  END { if (blk != "" && !drop) printf "%s", blk }
' "$CONF" > "$CONF.new" && mv "$CONF.new" "$CONF"
"$BIN" syncconf "$IFACE" <("$BIN-quick" strip "/opt/amnezia/awg/$CONF")
if [ -s clientsTable ] && command -v jq >/dev/null 2>&1; then
  jq --arg id "$CLIENT_PUB" 'map(select(.clientId != $id))' clientsTable > clientsTable.new && mv clientsTable.new clientsTable
fi
echo "::result::removed=$CLIENT_PUB"
REMOVE
