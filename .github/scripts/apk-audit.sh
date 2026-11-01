#!/usr/bin/env bash
# Checks a built APK for everything that makes Google Play Protect (or any malware scanner) suspicious
# of an app installed outside Google Play. Fails the build when a rule is broken; see
# docs/PLAY-PROTECT.md for why each rule exists. Usage: apk-audit.sh <apk> [--release]
#   --release  also require a non-debug signing certificate.
set -euo pipefail
APK=$1; MODE=${2:-}
bt=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
fail=0
bad() { echo "::error::apk-audit: $*"; fail=1; }

echo "== Signature"
sig=$("$bt/apksigner" verify --verbose --print-certs "$APK")
echo "$sig" | grep -E "Verified using|Signer #1 certificate (DN|SHA-256)|Number of signers"
echo "$sig" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" || bad "not signed with APK Signature Scheme v2"
echo "$sig" | grep -q "Number of signers: 1" || bad "expected exactly one signer"
if [ "$MODE" = "--release" ] && echo "$sig" | grep -q "certificate DN: C=US, O=Android, CN=Android Debug"; then bad "release signed with the debug key"; fi

echo "== APK Signing Block (only the signer's own entries; nothing appended after signing)"
python3 - "$APK" <<'PY' || fail=1
import struct, sys
known = {0x7109871a: "v2 signature", 0xf05368c0: "v3 signature", 0x1b93ad61: "v3.1 signature",
         0x42726577: "padding", 0x6dff800d: "source stamp", 0x504b4453: "dependency info (AGP)"}
data = open(sys.argv[1], "rb").read()
eocd = data.rfind(b"PK\x05\x06")
cd = struct.unpack_from("<I", data, eocd + 16)[0]
if data[cd - 16:cd] != b"APK Sig Block 42":
    sys.exit("::error::apk-audit: no APK Signing Block")
size = struct.unpack_from("<Q", data, cd - 24)[0]
p, end, bad = cd - size - 8 + 8, cd - 24, False
while p < end:
    n, i = struct.unpack_from("<QI", data, p)
    name = known.get(i)
    print(f"  0x{i:08x} {n - 4:>7} bytes  {name or 'UNKNOWN'}")
    bad |= name is None
    p += 8 + n
if bad:
    sys.exit("::error::apk-audit: unknown entry in the APK Signing Block (the APK was modified after signing)")
PY

echo "== Manifest"
badging=$("$bt/aapt2" dump badging "$APK")
echo "$badging" | grep -E "^(package|sdkVersion|targetSdkVersion|uses-permission|application-debuggable|testOnly)" || true
echo "$badging" | grep -q "application-debuggable" && bad "debuggable APK"
echo "$badging" | grep -q "testOnly" && bad "testOnly APK"
target=$(echo "$badging" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p")
[ "${target:-0}" -ge 35 ] || bad "targetSdkVersion $target is below 35"
# Every permission must be on this list. Extending it needs a reason in docs/PLAY-PROTECT.md.
allowed="android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
android.permission.CAMERA
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.WAKE_LOCK
android.permission.FOREGROUND_SERVICE
net.paramnezia.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
net.paramnezia.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
for p in $(echo "$badging" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p"); do
  echo "$allowed" | grep -qx "$p" || bad "permission $p is not on the allowed list"
done
echo "$badging" | grep -q "QUERY_ALL_PACKAGES" && bad "QUERY_ALL_PACKAGES"
echo "$badging" | grep -q "INSTALL_PACKAGES" && bad "install permission (updates go through the browser)"

echo "== Code"
unzip -l "$APK" | awk '{print $4}' | grep -E '\.(dex|jar|apk|so)$' | sort
unzip -l "$APK" | awk '{print $4}' | grep -E '^assets/.*\.(dex|jar|apk|zip)$' && bad "code or packages inside assets (looks like a dropper)"
unzip -l "$APK" | awk '{print $4}' | grep -E '\.so$' | grep -v 'libparamnezia.so$' | grep -v 'libandroidx' && echo "note: other native libraries above"
dex=$(unzip -Z1 "$APK" | grep -E '^classes[0-9]*\.dex$')
for f in $dex; do unzip -p "$APK" "$f"; done > "${TMPDIR:-/tmp}/pn-dex.bin"
# The app never installs packages itself and talks to no relay networks (docs/PLAY-PROTECT.md).
grep -aq 'Landroid/content/pm/PackageInstaller$Session;' "${TMPDIR:-/tmp}/pn-dex.bin" && bad "code uses PackageInstaller sessions"
grep -aq 'wss://' "${TMPDIR:-/tmp}/pn-dex.bin" && bad "WebSocket relay URLs in the code"
rm -f "${TMPDIR:-/tmp}/pn-dex.bin"

[ "$fail" -eq 0 ] && echo "apk-audit: OK" || { echo "apk-audit: FAILED"; exit 1; }
