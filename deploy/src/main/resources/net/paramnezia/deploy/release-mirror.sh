#!/usr/bin/env bash
# Puts an app release on this server. The server downloads the APK itself from public HTTPS mirrors
# (GitHub Releases) and keeps it only if its SHA-256 matches the signed release: phones never upload
# APK files anywhere.
#
# Inputs (environment): PN_SHA256, PN_URLS (one per line), optional PN_RELEASE (signed release JSON
#   for the in-tunnel update channel), optional CONTAINER (AmneziaWG container whose in-tunnel web
#   server gets the APK), optional PN_INVITE=1 (a copy for the invite page), optional PN_WIN_SHA256 +
#   PN_WIN_URLS (the Windows installer for the invite page, same rules; its failure is not fatal).
# Output: "::result::apk=cached|downloaded", "::result::tunnel=ok|skipped", "::result::invite=ok|skipped",
#   "::result::windows=ok|failed|skipped".
set -eu
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
fail() { echo "::error::$1"; exit 1; }
sha=${PN_SHA256:-}
case "$sha" in ''|*[!0-9a-f]*) fail "bad_sha256" ;; esac
[ "${#sha}" -eq 64 ] || fail "bad_sha256"

CACHE=/opt/amnezia/paramnezia/cache
$SUDO mkdir -p "$CACHE"
valid() { [ -f "$1" ] && [ "$($SUDO sha256sum "$1" | cut -d' ' -f1)" = "$2" ]; }

# fetch <file> <sha256> <urls>: keeps <file> if it already matches, else downloads the first URL
# whose bytes match. Prints cached|downloaded; fails if nothing matched.
fetch() {
  if valid "$1" "$2"; then echo cached; return 0; fi
  tmp=$1.part
  old_ifs=$IFS; IFS='
'
  for u in $3; do
    case "$u" in https://*) ;; *) continue ;; esac
    $SUDO rm -f "$tmp"
    if command -v curl >/dev/null 2>&1; then
      $SUDO curl -fsSL --proto '=https' --retry 2 --connect-timeout 15 --max-time 300 -o "$tmp" "$u" 2>/dev/null || continue
    elif command -v wget >/dev/null 2>&1; then
      $SUDO wget -q -T 60 -O "$tmp" "$u" 2>/dev/null || continue
    else
      IFS=$old_ifs; echo "no curl or wget" >&2; return 1
    fi
    if valid "$tmp" "$2"; then $SUDO mv "$tmp" "$1"; IFS=$old_ifs; echo downloaded; return 0; fi
  done
  IFS=$old_ifs
  $SUDO rm -f "$tmp"
  return 1
}

APK=$CACHE/$sha.apk
got=$(fetch "$APK" "$sha" "${PN_URLS:-}") || fail "download_failed"
echo "::result::apk=$got"
$SUDO chmod 644 "$APK"

wsha=${PN_WIN_SHA256:-}
case "$wsha" in *[!0-9a-f]*) wsha="" ;; esac
[ "${#wsha}" -eq 64 ] || wsha=""
EXE=""
if [ -n "$wsha" ] && [ "${PN_INVITE:-0}" = "1" ]; then
  if fetch "$CACHE/$wsha.exe" "$wsha" "${PN_WIN_URLS:-}" >/dev/null; then EXE=$CACHE/$wsha.exe; $SUDO chmod 644 "$EXE"; fi
fi
# Only the current releases are kept (the installer we already had stays if a new one could not be fetched).
keep_exe=$(basename "${EXE:-none}")
if [ -z "$EXE" ]; then keep_exe=$( $SUDO ls -t "$CACHE" 2>/dev/null | grep -m1 '\.exe$' || echo none ); fi
$SUDO find "$CACHE" -maxdepth 1 -type f ! -name "$sha.apk" ! -name "$keep_exe" -delete 2>/dev/null || true

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

if [ -z "$wsha" ] || [ "${PN_INVITE:-0}" != "1" ] || [ ! -d "$INV" ]; then
  echo "::result::windows=skipped"
elif [ -n "$EXE" ]; then
  $SUDO cp "$EXE" "$INV/Paramnezia-Setup.exe.new" && $SUDO chmod 644 "$INV/Paramnezia-Setup.exe.new" && $SUDO mv "$INV/Paramnezia-Setup.exe.new" "$INV/Paramnezia-Setup.exe"
  echo "::result::windows=ok"
else
  echo "::result::windows=failed"
fi
