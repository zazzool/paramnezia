#!/usr/bin/env bash
# Runs the one-time invite page next to the VPN: a busybox httpd in its own container, read-only,
# no capabilities, unprivileged user, 32 MB of memory. Idempotent; keeps the port it got first.
#
# Inputs (environment): PN_INVITE_PORT (preferred port), PN_INVITE_INDEX, PN_INVITE_CGI (file
#   contents), PN_INVITE_ASSETS (lines "<name> <base64>": screenshots for the page), PN_APP_JSON (where to download the app: {"version","sha256","urls"}; empty keeps the
#   current one).
# The page lives under a secret path (/<secret>/, the CGI at /cgi-bin/<secret>): the server's root
# answers 404 like any idle web server, so scanners indexing the address (and censors searching
# those indexes) find no VPN page, no app and no download info there. Links carry the path.
# Output: "::result::port=<port>", "::result::path=<secret>".
set -eu
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
NAME=paramnezia-invite
ROOT=/opt/amnezia/paramnezia/invite
fail() { echo "::error::$1"; exit 1; }
$SUDO docker info >/dev/null 2>&1 || fail "docker_not_running"

$SUDO mkdir -p "$ROOT/www/cgi-bin" "$ROOT/data/t" "$ROOT/data/c"
if ! $SUDO test -s "$ROOT/secret"; then
  head -c 16 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' | $SUDO tee "$ROOT/secret.new" >/dev/null && $SUDO mv "$ROOT/secret.new" "$ROOT/secret"
fi
SECRET=$($SUDO cat "$ROOT/secret" | tr -cd 'A-Za-z0-9_-')
[ "${#SECRET}" -ge 16 ] || fail "no_secret"
W=$ROOT/www/$SECRET
$SUDO mkdir -p "$W"
# Before 2.16 the page sat at the root: take it all away from there.
for f in index.html app.json paramnezia.apk Paramnezia-Setup.exe httpd.conf cgi-bin/get; do $SUDO rm -f "$ROOT/www/$f"; done
$SUDO find "$ROOT/www" -maxdepth 1 -name '*.webp' -delete 2>/dev/null || true
# Write next to the target and rename, so the page never serves a half-written file.
put() { printf '%s' "$2" | $SUDO tee "$1.new" >/dev/null && $SUDO mv "$1.new" "$1"; }
put "$W/index.html" "$PN_INVITE_INDEX"
put "$ROOT/www/cgi-bin/$SECRET" "$PN_INVITE_CGI"
$SUDO chmod 755 "$ROOT/www/cgi-bin/$SECRET"
# Images for the page.
printf '%s\n' "${PN_INVITE_ASSETS:-}" | while read -r name data; do
  case "$name" in ''|*[!A-Za-z0-9._-]*|.*) continue ;; esac
  printf '%s' "$data" | base64 -d | $SUDO tee "$W/$name.new" >/dev/null && $SUDO mv "$W/$name.new" "$W/$name"
done
# File types, outside what the web server serves.
put "$ROOT/httpd.conf" "$(printf '.webp:image/webp\n.svg:image/svg+xml\n.apk:application/vnd.android.package-archive\n.exe:application/octet-stream\n')"
if [ -n "${PN_APP_JSON:-}" ]; then put "$W/app.json" "$PN_APP_JSON"; fi
# Version 1.0 kept a personalised APK template here; links no longer carry an APK.
$SUDO rm -f "$ROOT/data/app.apk" "$ROOT/data/app.slot" "$ROOT/data/app.sha" "$ROOT/data/app.apk.new" "$ROOT/data/app.slot.new"
$SUDO chown -R 65534:65534 "$ROOT/data"
# Claimed and expired invites older than a week go away.
$SUDO find "$ROOT/data/t" "$ROOT/data/c" -mindepth 1 -maxdepth 1 -type d -mtime +7 -exec rm -rf {} + 2>/dev/null || true

running=$($SUDO docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || true)
port=$($SUDO docker inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{(index $b 0).HostPort}}{{end}}' "$NAME" 2>/dev/null || true)
keep=$port
if [ -n "$port" ] && ! $SUDO docker inspect -f '{{json .Config.Cmd}}' "$NAME" 2>/dev/null | grep -q '/srv/httpd.conf'; then
  # A container from before 2.16 (its config inside the served folder): made again on the same port.
  $SUDO docker rm -f "$NAME" >/dev/null 2>&1 || true
  port=""
fi
if [ -n "$port" ] && [ "$running" != "true" ]; then
  # Stopped (a crash, a manual stop): start it again on the port the links already point to.
  $SUDO docker start "$NAME" >/dev/null 2>&1 || port=""
fi
if [ -z "$port" ]; then
  $SUDO docker rm -f "$NAME" >/dev/null 2>&1 || true
  busy() { (ss -ltnH 2>/dev/null || netstat -ltn 2>/dev/null) | awk '{print $4}' | grep -qE "[:.]$1\$"; }
  port=${keep:-${PN_INVITE_PORT:-80}}
  if busy "$port"; then
    for _ in 1 2 3 4 5 6 7 8 9 10; do port=$((20000 + RANDOM % 40000)); busy "$port" || break; done
  fi
  ok=""
  for _ in 1 2 3; do
    if $SUDO docker run -d --name "$NAME" --restart always --log-driver none \
      --read-only --cap-drop ALL --security-opt no-new-privileges --user 65534:65534 --memory 32m --pids-limit 64 \
      -p "$port:8080" -v "$ROOT:/srv" busybox:stable httpd -f -p 8080 -h /srv/www -c /srv/httpd.conf >/dev/null 2>&1; then ok=1; break; fi
    # A failed pull (registry hiccup) or a lost port race: clean up and try again.
    $SUDO docker rm -f "$NAME" >/dev/null 2>&1 || true
    sleep 3
  done
  [ -n "$ok" ] || fail "docker_run_failed"
fi
echo "::result::port=$port"
echo "::result::path=$SECRET"
