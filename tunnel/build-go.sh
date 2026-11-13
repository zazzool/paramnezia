#!/usr/bin/env bash
# Builds libparamnezia.so (amneziawg-go + JNI glue) for the given Android ABIs.
#
#   build-go.sh <out-dir> <min-sdk> <abi>...
#
# Needs Go and the Android NDK (ANDROID_NDK_HOME, ANDROID_NDK_LATEST_HOME or $ANDROID_HOME/ndk/<ver>).
# The Go runtime is patched to measure time with CLOCK_BOOTTIME, so WireGuard timers stay correct
# across deep sleep (same patch as wireguard-android and amneziawg-android).
set -euo pipefail

OUT=$1; MIN_SDK=$2; shift 2
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/src/main/go
WORK=$HERE/build/go

ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}
if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
  ndk=$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -d "$ndk" ] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
case "$(uname -s)" in Linux) host=linux-x86_64 ;; Darwin) host=darwin-x86_64 ;; *) echo "unsupported host" >&2; exit 1 ;; esac
toolchain=$ndk/toolchains/llvm/prebuilt/$host/bin

# Patched copy of the Go toolchain the module asks for.
cd "$SRC"
goroot=$(go env GOROOT)
gover=$(go env GOVERSION)
patched=$WORK/goroot-$gover
if [ ! -f "$patched/.boottime" ]; then
  rm -rf "$patched"; mkdir -p "$WORK"
  cp -a "$goroot" "$patched"
  chmod -R u+w "$patched"
  rt=$patched/src/runtime
  sed -i.bak -E 's/^#define[[:space:]]+CLOCK_MONOTONIC[[:space:]]+1$/#define CLOCK_MONOTONIC 7 \/\/ CLOCK_BOOTTIME/' "$rt/sys_linux_arm64.s" "$rt/sys_linux_arm.s"
  sed -i.bak -E 's/\$1, ([A-Z0-9()]+)[[:space:]]*\/\/ CLOCK_MONOTONIC/$7, \1 \/\/ CLOCK_BOOTTIME/' "$rt/sys_linux_amd64.s" "$rt/sys_linux_386.s"
  n=$(grep -c "CLOCK_BOOTTIME" "$rt/sys_linux_arm64.s" "$rt/sys_linux_arm.s" "$rt/sys_linux_amd64.s" | awk -F: '{s+=$2} END {print s}')
  [ "$n" -ge 3 ] || { echo "Go runtime layout changed; boottime patch did not apply" >&2; exit 1; }
  rm -f "$rt"/*.bak
  touch "$patched/.boottime"
fi
export GOROOT=$patched PATH=$patched/bin:$PATH GOTOOLCHAIN=local

# A library of an ABI that is no longer asked for must not stay behind and end up in the APK.
mkdir -p "$OUT" && find "$OUT" -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} +

for abi in "$@"; do
  case $abi in
    arm64-v8a)   goarch=arm64; cc=aarch64-linux-android$MIN_SDK-clang ;;
    armeabi-v7a) goarch=arm;   cc=armv7a-linux-androideabi$MIN_SDK-clang; export GOARM=7 ;;
    x86_64)      goarch=amd64; cc=x86_64-linux-android$MIN_SDK-clang ;;
    x86)         goarch=386;   cc=i686-linux-android$MIN_SDK-clang ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
  esac
  mkdir -p "$OUT/$abi"
  # 16 KB pages are required on Android 15+ devices that use them.
  CGO_ENABLED=1 GOOS=android GOARCH=$goarch CC=$toolchain/$cc \
  CGO_CFLAGS="-O2" CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,-soname=libparamnezia.so" \
    go build -buildmode=c-shared -trimpath -buildvcs=false \
      -ldflags="-s -w -buildid=" \
      -o "$OUT/$abi/libparamnezia.so" .
  rm -f "$OUT/$abi/libparamnezia.h"
  echo "built $abi: $(du -h "$OUT/$abi/libparamnezia.so" | cut -f1)"
done
