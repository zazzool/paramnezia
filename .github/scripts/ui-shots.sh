#!/usr/bin/env bash
# Screenshots of the «Добавить» and «Флот» screens for pull requests, on a phone-sized screen. Uses the
# debug APK: its DemoActivity fills the app with sample data (a fleet with two servers, devices and
# invitations), so admin screens can be shown without real servers. Fails only if the app crashes.
# Usage: ui-shots.sh <debug apk> <tag>
set -u
apk=$1; tag=$2
here=$(cd "$(dirname "$0")" && pwd)
pkg=net.paramnezia.debug

tap() {
  for _ in $(seq 1 ${TAP_WAIT:-10}); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
    if xy=$(python3 "$here/ui.py" find ui.xml "$1"); then adb shell input tap $xy; return 0; fi
    sleep 1
  done
  echo "::warning::[$tag] '$1' not found on screen"
  return 1
}
shot() {
  sleep "${2:-2}"
  adb exec-out screencap -p > "screen-$tag-ui-$1.png" || true
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "ui-$tag-ui-$1.xml" >/dev/null 2>&1
  echo "--- screen: $1 ---"; python3 "$here/ui.py" list "ui-$tag-ui-$1.xml" 2>/dev/null | grep "$pkg" | head -40
}
# Swipes in the middle of the screen (the bottom bar must not catch the gesture).
size() { adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1; }
scroll() { local w h; read -r w h < <(size); adb shell input swipe $((w / 2)) $((h * 3 / 5)) $((w / 2)) $((h / 5)) 600; sleep 1; }
top() { local w h; read -r w h < <(size); for _ in 1 2 3; do adb shell input swipe $((w / 2)) $((h / 4)) $((w / 2)) $((h * 3 / 4)) 200; done; sleep 1; }
demo() {
  adb shell am force-stop $pkg
  adb shell am start -n $pkg/net.paramnezia.app.DemoActivity --es demo "$1" >/dev/null
  sleep 7
}
# Back only while the keyboard is up: otherwise it would leave the app.
hide_keyboard() { adb shell dumpsys input_method | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK; sleep 1; }
crashed() { grep -A1 "FATAL EXCEPTION" <(adb logcat -d) | grep -q "Process: $pkg"; }

adb install -r "$apk" || { echo "::error::[$tag] debug install failed"; exit 1; }
adb shell wm size 1080x2340; adb shell wm density 440; sleep 3
adb logcat -c

# A fresh install: someone who was sent a key, or an admin about to get a server.
demo empty
tap "Добавить" && shot add
tap "Свой VPS" && { sleep 1; scroll; shot add-vps; }
# The keyboard comes only on request, and the field stays above it.
if tap "Ввести с клавиатуры"; then shot add-keyboard 3; hide_keyboard; fi
top; tap "Ввести" && shot add-type 3
hide_keyboard
tap "Флот" && shot fleet-empty

# An admin with a server of their own but no fleet yet.
demo server
tap "Флот" && shot fleet-server
scroll; shot fleet-server-2

# A fleet admin: devices, servers, sharing, delivery.
demo admin
tap "Флот" && shot fleet-admin
scroll; shot fleet-admin-2
scroll; shot fleet-admin-3
scroll; shot fleet-admin-4
top
tap "Пригласить" && shot invite-dialog
adb shell input keyevent KEYCODE_BACK; sleep 1

# Buying a server from a hoster: the one-tap offer, then places, plans and the hoster list.
demo cloud
tap "Добавить" && { scroll; tap "Купить у хостера"; } && {
  shot cloud-pick 3
  tap "Другое: место" && { shot cloud-places; scroll; shot cloud-places-2; }
  top
  tap "Скрыть"; tap "Другое: тариф" && { shot cloud-plans; scroll; shot cloud-plans-2; }
  top
  tap "Хостер" && { shot cloud-providers; tap "Vultr" && shot cloud-provider-token; }
}

adb shell wm size reset; adb shell wm density reset
adb logcat -d > "logcat-$tag-ui.txt"
if crashed; then
  echo "::error::[$tag] the app crashed on the UI screens"
  grep -B2 -A40 "FATAL EXCEPTION" "logcat-$tag-ui.txt" | head -80
  exit 1
fi
echo "[$tag] UI screenshots: OK"
