#!/bin/sh
# One-time invite downloads: /cgi-bin/get?<token>&<what>, what = app | key | conf.
#   app   the invite itself, opened by Paramnezia (fleet invite or vpn:// key)
#   key   the same key without the fleet part, for the original AmneziaVPN (iPhone and others)
#   conf  an AmneziaWG config file
# The app itself is not here: it is the same public release APK for everyone (see app.json).
# The first download claims the invite. For 30 minutes after that the same address may fetch it
# again (installing the app in between, a second try at copying); everyone else gets 410.
D=/srv/data
T=${QUERY_STRING%%&*}
W=${QUERY_STRING#*&}
now=$(date +%s)

reply() { printf 'Status: %s\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n\r\n%s\n' "$1" "$2"; exit 0; }

case "$T" in ''|*[!A-Za-z0-9_-]*) reply "400 Bad Request" "bad link" ;; esac
case "$W" in app|key|conf) ;; *) reply "400 Bad Request" "bad request" ;; esac

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
[ "$addr" = "$REMOTE_ADDR" ] && [ $((now - at)) -le 1800 ] || reply "410 Gone" "used"

F=$D/c/$T
case "$W" in
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
