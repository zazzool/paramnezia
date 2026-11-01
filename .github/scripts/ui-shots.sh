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
scroll() { adb shell input swipe 540 1700 540 700 500; }
demo() {
  adb shell am force-stop $pkg
  adb shell am start -n $pkg/net.paramnezia.app.DemoActivity --es demo "$1" >/dev/null
  sleep 7
}
crashed() { grep -A1 "FATAL EXCEPTION" <(adb logcat -d) | grep -q "Process: $pkg"; }

adb install -r "$apk" || { echo "::error::[$tag] debug install failed"; exit 1; }
adb shell wm size 1080x2340; adb shell wm density 440; sleep 3
adb logcat -c

# A fresh install: someone who was sent a key, or an admin about to get a server.
demo empty
tap "Добавить" && shot add
tap "Свой VPS" && shot add-vps
# The keyboard comes only on request, and the field stays above it.
tap "Ввести с клавиатуры" && shot add-keyboard 3
adb shell input keyevent KEYCODE_BACK; sleep 1
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
adb shell input swipe 540 700 540 2000 300; adb shell input swipe 540 700 540 2000 300; sleep 1
tap "Пригласить" && shot invite-dialog
adb shell input keyevent KEYCODE_BACK; sleep 1

adb shell wm size reset; adb shell wm density reset
adb logcat -d > "logcat-$tag-ui.txt"
if crashed; then
  echo "::error::[$tag] the app crashed on the UI screens"
  grep -B2 -A40 "FATAL EXCEPTION" "logcat-$tag-ui.txt" | head -80
  exit 1
fi
echo "[$tag] UI screenshots: OK"
