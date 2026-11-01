#!/usr/bin/env bash
# Puts an app release on this server. The server downloads the APK itself from public HTTPS mirrors
# (GitHub Releases) and keeps it only if its SHA-256 matches the signed release: phones never upload
# APK files anywhere.
#
# Inputs (environment): PN_SHA256, PN_URLS (one per line), optional PN_RELEASE (signed release JSON
#   for the in-tunnel update channel), optional CONTAINER (AmneziaWG container whose in-tunnel web
#   server gets the APK), optional PN_INVITE=1 (a copy for the invite page).
# Output: "::result::apk=cached|downloaded", "::result::tunnel=ok|skipped", "::result::invite=ok|skipped".
set -eu
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
fail() { echo "::error::$1"; exit 1; }
sha=${PN_SHA256:-}
case "$sha" in ''|*[!0-9a-f]*) fail "bad_sha256" ;; esac
[ "${#sha}" -eq 64 ] || fail "bad_sha256"

CACHE=/opt/amnezia/paramnezia/cache
APK=$CACHE/$sha.apk
$SUDO mkdir -p "$CACHE"
valid() { [ -f "$1" ] && [ "$($SUDO sha256sum "$1" | cut -d' ' -f1)" = "$sha" ]; }

if valid "$APK"; then
  echo "::result::apk=cached"
else
  tmp=$APK.part
  got=""
  old_ifs=$IFS; IFS='
'
  for u in ${PN_URLS:-}; do
    case "$u" in https://*) ;; *) continue ;; esac
    $SUDO rm -f "$tmp"
    if command -v curl >/dev/null 2>&1; then
      $SUDO curl -fsSL --proto '=https' --retry 2 --connect-timeout 15 --max-time 300 -o "$tmp" "$u" 2>/dev/null || continue
    elif command -v wget >/dev/null 2>&1; then
      $SUDO wget -q -T 60 -O "$tmp" "$u" 2>/dev/null || continue
    else
      fail "no_downloader"
    fi
    if valid "$tmp"; then $SUDO mv "$tmp" "$APK"; got=1; break; fi
  done
  IFS=$old_ifs
  $SUDO rm -f "$tmp"
  [ -n "$got" ] || fail "download_failed"
  echo "::result::apk=downloaded"
fi
$SUDO chmod 644 "$APK"
# Only the current release is kept.
$SUDO find "$CACHE" -maxdepth 1 -type f ! -name "$sha.apk" -delete 2>/dev/null || true

WWW=/opt/amnezia/paramnezia/www
if [ -n "${CONTAINER:-}" ] && [ "$($SUDO docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" = "true" ]; then
  $SUDO docker exec -i "$CONTAINER" sh -c "mkdir -p $WWW/apk && cat > $WWW/apk/$sha.new && find $WWW/apk -type f ! -name $sha.new -delete && mv $WWW/apk/$sha.new $WWW/apk/$sha" < "$APK"
  if [ -n "${PN_RELEASE:-}" ]; then
    printf '%s' "$PN_RELEASE" | $SUDO docker exec -i "$CONTAINER" sh -c "cat > $WWW/release.json.new && mv $WWW/release.json.new $WWW/release.json"
  fi
  echo "::result::tunnel=ok"
else
  echo "::result::tunnel=skipped"
fi

INV=/opt/amnezia/paramnezia/invite/www
if [ "${PN_INVITE:-0}" = "1" ] && [ -d "$INV" ]; then
  $SUDO cp "$APK" "$INV/paramnezia.apk.new" && $SUDO chmod 644 "$INV/paramnezia.apk.new" && $SUDO mv "$INV/paramnezia.apk.new" "$INV/paramnezia.apk"
  echo "::result::invite=ok"
else
  echo "::result::invite=skipped"
fi
