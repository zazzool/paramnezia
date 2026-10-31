#!/bin/sh
# One-time invite downloads: /cgi-bin/get?<token>&<what>, what = apk | app | key | conf.
#   apk   Paramnezia with the invite written into its settings slot (see ApkSlot)
#   app   the invite itself, for Paramnezia that is already installed
#   key   the same key without the fleet part, for the original AmneziaVPN (iPhone and others)
#   conf  an AmneziaWG config file
# The first download claims the invite. For 15 minutes after that the same address may fetch it
# again (a resumed download, a second try at copying); everyone else gets 410.
D=/srv/data
T=${QUERY_STRING%%&*}
W=${QUERY_STRING#*&}
now=$(date +%s)

reply() { printf 'Status: %s\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n\r\n%s\n' "$1" "$2"; exit 0; }

case "$T" in ''|*[!A-Za-z0-9_-]*) reply "400 Bad Request" "bad link" ;; esac
case "$W" in apk|app|key|conf) ;; *) reply "400 Bad Request" "bad request" ;; esac

if [ -d "$D/t/$T" ]; then
  exp=$(cat "$D/t/$T/expires" 2>/dev/null || echo 0)
  [ "$now" -le "$exp" ] || reply "410 Gone" "expired"
  # mv is atomic: of two simultaneous first downloads only one wins.
  if mv "$D/t/$T" "$D/c/$T" 2>/dev/null; then
    printf '%s %s\n' "$now" "$REMOTE_ADDR" > "$D/c/$T/claim"
  fi
fi
[ -f "$D/c/$T/claim" ] || reply "410 Gone" "used"
read -r at addr < "$D/c/$T/claim"
[ "$addr" = "$REMOTE_ADDR" ] && [ $((now - at)) -le 900 ] || reply "410 Gone" "used"

F=$D/c/$T
case "$W" in
  apk)
    [ -f "$D/app.apk" ] && [ -f "$F/slot" ] || reply "404 Not Found" "no app"
    read -r off size < "$D/app.slot"
    total=$(stat -c %s "$D/app.apk")
    printf 'Content-Type: application/vnd.android.package-archive\r\nContent-Disposition: attachment; filename="paramnezia.apk"\r\nContent-Length: %s\r\nCache-Control: no-store\r\n\r\n' "$total"
    head -c "$off" "$D/app.apk"
    cat "$F/slot"
    tail -c +$((off + size + 1)) "$D/app.apk"
    ;;
  app|key)
    printf 'Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n\r\n'
    cat "$F/$W"
    ;;
  conf)
    name=$(cat "$F/name" 2>/dev/null | tr -cd 'A-Za-z0-9_-' | cut -c1-15)
    printf 'Content-Type: application/octet-stream\r\nContent-Disposition: attachment; filename="%s.conf"\r\nCache-Control: no-store\r\n\r\n' "${name:-paramnezia}"
    cat "$F/conf"
    ;;
esac
