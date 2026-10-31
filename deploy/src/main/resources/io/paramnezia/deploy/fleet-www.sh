#!/usr/bin/env bash
# The fleet's in-tunnel web server (http://<server tunnel address>:8080): stores the signed fleet
# manifest and makes sure the server answers, also in containers that AmneziaVPN installed (they have
# no web server of their own) and after a reboot. Idempotent.
#
# Inputs (environment): CONTAINER, optional MANIFEST (signed manifest to store).
# Output: "::result::stored=0|1", "::result::www=ok|missing|no_address".
set -eu
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
[ "$($SUDO docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" = "true" ] || { echo "::error::container_not_running"; exit 1; }
WWW=/opt/amnezia/paramnezia/www
if [ -n "${MANIFEST:-}" ]; then
  printf '%s' "$MANIFEST" | $SUDO docker exec -i "$CONTAINER" sh -c "mkdir -p $WWW && cat > $WWW/fleet.json.new && mv $WWW/fleet.json.new $WWW/fleet.json"
  echo "::result::stored=1"
else
  echo "::result::stored=0"
fi
$SUDO docker exec -i "$CONTAINER" sh -s <<'IN'
set -eu
WWW=/opt/amnezia/paramnezia/www
mkdir -p "$WWW"
echo ok > "$WWW/ok"
conf=/opt/amnezia/awg/awg0.conf; [ -f "$conf" ] || conf=/opt/amnezia/awg/wg0.conf
ip=$(awk -F' *= *' '$1=="Address"{print $2; exit}' "$conf" 2>/dev/null | cut -d, -f1 | cut -d/ -f1)
[ -n "$ip" ] || { echo "::result::www=no_address"; exit 0; }
up() { wget -q -T 3 -O /dev/null "http://$ip:8080/ok" 2>/dev/null; }
if ! up; then
  command -v httpd >/dev/null 2>&1 || apk add --no-cache busybox-extras >/dev/null 2>&1 || true
  if command -v httpd >/dev/null 2>&1; then httpd -p "$ip:8080" -h "$WWW" 2>/dev/null || true; sleep 1; fi
fi
if up; then echo "::result::www=ok"; else echo "::result::www=missing"; fi
IN
