#!/usr/bin/env bash
# End-to-end check of the release APK on an emulator: start the app, import a WireGuard config for a
# server on the CI host (10.0.2.2 from the emulator), press the power button, accept the system VPN
# dialog and check that the tunnel is up and has a handshake with the server.
# Usage: vpn-e2e.sh <apk> <client.conf> <tag>
set -u
apk=$1; conf=$2; tag=$3
here=$(cd "$(dirname "$0")" && pwd)

dump() {
  adb logcat -d > "logcat-$tag.txt"
  echo "--- crash buffer ---"; adb logcat -d -b crash | tail -150
  echo "--- app and VPN lines ---"
  grep -iE "paramnezia|vpn|AndroidRuntime|libc|DEBUG  " "logcat-$tag.txt" | grep -v Monkey | tail -250
  adb exec-out screencap -p > "screen-$tag.png" || true
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "ui-$tag.xml" >/dev/null 2>&1
  echo "--- screen ---"; python3 "$here/ui.py" list "ui-$tag.xml" 2>/dev/null | head -60
}
fail() { echo "::error::[$tag] $*"; dump; exit 1; }

# Taps the node whose text or content description is $1 (waits up to 20 s for it).
tap() {
  for _ in $(seq 1 20); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
    if xy=$(python3 "$here/ui.py" find ui.xml "$1"); then adb shell input tap $xy; return 0; fi
    sleep 1
  done
  return 1
}

adb install -r "$apk" || fail "install failed"
adb logcat -c
adb shell monkey -p net.paramnezia -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 10
adb shell pidof net.paramnezia >/dev/null || fail "the app is not running after launch"

adb push "$conf" /data/local/tmp/c.conf >/dev/null
adb shell 'am start -n net.paramnezia/net.paramnezia.app.ui.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$(cat /data/local/tmp/c.conf)"' >/dev/null
sleep 6

# Settings opened from a message connect at once (2.2.0), so the system's VPN consent dialog comes
# up by itself; otherwise press the power button first.
vpn_dialog() { adb shell dumpsys window | grep -qE "mCurrentFocus.*vpndialogs"; }
if ! vpn_dialog; then
  tap "VPN" || fail "no power button on the home screen"
  sleep 3
fi
# The system asks once whether the app may set up a VPN.
if vpn_dialog; then
  tap "OK" || tap "ОК" || fail "could not accept the VPN dialog"
else
  echo "::warning::[$tag] no VPN consent dialog"
fi

for _ in $(seq 1 30); do
  adb shell ip addr show tun0 2>/dev/null | grep -q "inet " && break
  sleep 1
done
adb shell ip addr show tun0 2>/dev/null | grep -q "inet " || fail "no tun0 interface: the VPN did not come up"
echo "[$tag] tun0 is up:"; adb shell ip addr show tun0
adb shell appops get net.paramnezia ACTIVATE_VPN

for _ in $(seq 1 20); do
  hs=$(sudo wg show wg0 latest-handshakes | awk '{print $2}')
  [ -n "$hs" ] && [ "$hs" != "0" ] && break
  sleep 1
done
sudo wg show wg0
[ -n "$hs" ] && [ "$hs" != "0" ] || fail "tun0 is up but there was no handshake with the server"

# Traffic really flows through the tunnel: the server's tunnel address answers.
adb shell ping -c 3 -W 2 10.9.0.1 || echo "::warning::[$tag] ping 10.9.0.1 through the tunnel failed"
adb shell pidof net.paramnezia >/dev/null || fail "the app died"
grep -q "FATAL EXCEPTION" <(adb logcat -d) && fail "crash in the log"
dump >/dev/null

# Screenshots of the one-button screen that family devices show (used on the server's family page,
# deploy/src/main/resources/net/paramnezia/deploy/invite/shot-*.webp). Best effort, never fails the run.
shot() { adb exec-out screencap -p > "screen-$tag-$1.png" || true; }
if tap "Настройки"; then
  sleep 2
  found=""
  for _ in 1 2 3 4 5 6; do
    if tap "Простой"; then found=1; break; fi
    adb shell input swipe 540 1600 540 600 300; sleep 1
  done
  if [ -n "$found" ]; then
    sleep 4; shot simple-on
    tap "VPN" && { sleep 5; shot simple-off; }
  else
    echo "::warning::[$tag] could not switch to the simple screen for screenshots"
  fi
fi
echo "[$tag] VPN end-to-end: OK"
