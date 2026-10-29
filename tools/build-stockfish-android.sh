#!/usr/bin/env bash
# Builds the official Stockfish 19 (tag sf_19, commit pinned in stockfish-common.sh) + the JNI bridge into
#   build/jnilibs/arm64-v8a/libstockfish.so      (ARM64, the only ABI the app ships)
# and writes build/jnilibs/engine-build-info.txt (versions, hashes, flags) which goes into the APK.
#
#   ANDROID_NDK_HOME   NDK r27c or newer (Stockfish's own requirement for Android builds)
#   SF_ARCH            armv8 (default: every ARM64 phone)  |  armv8-dotprod (ARMv8.2 dot-product CPUs only;
#                      the Realme 11 4G's Helio G99 has them, but a phone without them would crash)
#   ANDROID_API        minimum Android API of the library (default 26 = the app's minSdk)
#
# The NNUE network is downloaded by Stockfish's own scripts/net.sh and its SHA-256 is verified
# against the file name before it is embedded.  Nothing is fetched at run time on the phone.
set -e
source "$(dirname "$0")/stockfish-common.sh"
[ "$SF_VERSION" = "19" ] || { echo "the Android build is Stockfish 19 only"; exit 1; }

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -d "$NDK" ] || { echo "Set ANDROID_NDK_HOME to your NDK (r27c or newer)"; exit 1; }
HOST_TAG=linux-x86_64; [ "$(uname)" = "Darwin" ] && HOST_TAG=darwin-x86_64
NDKBIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
API="${ANDROID_API:-26}"
SF_ARCH="${SF_ARCH:-armv8}"
CXX="$NDKBIN/aarch64-linux-android${API}-clang++"
[ -x "$CXX" ] || { echo "compiler not found: $CXX"; exit 1; }

OUT="$ROOT/build/jnilibs/arm64-v8a"
mkdir -p "$OUT"
sf_fetch_source
sf_fetch_net

# flags = what Stockfish's own Makefile uses for  COMP=ndk ARCH=$SF_ARCH  (see Makefile sections 3.x)
ARCHF="-DIS_64BIT -DUSE_POPCNT -DUSE_NEON=8"
case "$SF_ARCH" in
  armv8) ;;
  armv8-dotprod) ARCHF="$ARCHF -march=armv8.2-a+dotprod -DUSE_NEON_DOTPROD" ;;
  *) echo "unsupported SF_ARCH=$SF_ARCH"; exit 1 ;;
esac
FLAGS="-std=c++17 -O3 -funroll-loops -DNDEBUG -fno-exceptions -fPIC -Wall -Wno-unused-result $ARCHF -I$SF_SRC"
MK="$(mktemp -d)"; trap 'rm -rf "$MK"' EXIT
for s in $(sf_sources); do
  echo "$CXX $FLAGS -c $SF_SRC/$s.cpp -o $MK/$(echo "$s" | tr / _).o"
done | xargs -P "$(nproc 2>/dev/null || echo 2)" -I{} bash -c '{}'
$CXX $FLAGS -c "$ROOT/native/jni_bridge.cpp" -o "$MK/jni_bridge.o"
OBJS=""; for s in $(sf_sources); do OBJS="$OBJS $MK/$(echo "$s" | tr / _).o"; done
# no -llog on purpose (an unresolved liblog link broke System.loadLibrary on some firmware in an earlier release);
# -z max-page-size=16384 keeps the library loadable on 16 KB page-size devices (Android 15+)
$CXX -shared -Wl,-soname,libstockfish.so -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 $FLAGS $OBJS "$MK/jni_bridge.o" \
    -static-libstdc++ -o "$OUT/libstockfish.so"
"$NDKBIN/llvm-strip" --strip-unneeded "$OUT/libstockfish.so"
ls -la "$OUT/libstockfish.so"

NDK_VER="$(grep -E 'Pkg.Revision' "$NDK/source.properties" 2>/dev/null | cut -d= -f2 | tr -d ' ')"
{
  echo "Chess Hint Panel - bundled chess engine"
  echo "======================================="
  echo "Engine:            Stockfish 19 (official release, tag $SF_TAG)"
  echo "Source repository: https://github.com/official-stockfish/Stockfish"
  echo "Source commit:     $SF_COMMIT"
  echo "Network (NNUE):    $SF_NET_NAME"
  echo "Network SHA-256:   $SF_NET_SHA256"
  echo "ABI:               arm64-v8a ($SF_ARCH)"
  echo "Android API:       minimum $API"
  echo "NDK:               ${NDK_VER:-unknown}"
  echo "Compiler:          $("$CXX" --version | head -1)"
  echo "Compile flags:     $FLAGS"
  echo "Bridge source:     native/jni_bridge.cpp (this repository)"
  echo "Library SHA-256:   $(sha256sum "$OUT/libstockfish.so" | cut -d' ' -f1)"
  echo "Library size:      $(stat -c %s "$OUT/libstockfish.so") bytes"
  echo "Built (UTC):       $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "License: GNU General Public License v3 or later (see LICENSE-STOCKFISH-GPLv3.txt in this app)."
  echo "Corresponding source of this exact engine build = the commit above + the network file above"
  echo "+ native/jni_bridge.cpp + tools/build-stockfish-android.sh of the app source tree (see SOURCE-OFFER.txt)."
} > "$ROOT/build/jnilibs/engine-build-info.txt"
cp "$SF_DIR/Copying.txt" "$ROOT/build/jnilibs/LICENSE-STOCKFISH-GPLv3.txt"
cp "$SF_DIR/AUTHORS" "$ROOT/build/jnilibs/STOCKFISH-AUTHORS.txt"
echo "done -> $OUT"
cat "$ROOT/build/jnilibs/engine-build-info.txt"
