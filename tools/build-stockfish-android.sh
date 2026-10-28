#!/usr/bin/env bash
# Build official Stockfish 19 (sf_19) into an Android JNI shared library.
# The official NNUE file is fetched by upstream scripts/net.sh, validated by SHA-256,
# and embedded into libstockfish.so by Stockfish's upstream incbin mechanism at compile time.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "ERROR: Set ANDROID_NDK_HOME to Android NDK r27c or later (Stockfish 19 requirement)." >&2
  exit 1
fi
HOST_TAG=linux-x86_64
[[ "$(uname)" == "Darwin" ]] && HOST_TAG=darwin-x86_64
NDKBIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$NDKBIN/aarch64-linux-android29-clang++" ]] || { echo "ERROR: NDK target toolchains missing at $NDKBIN" >&2; exit 1; }

WORK="${SF_WORK_DIR:-$ROOT/build/sf}"
SRC="$WORK/Stockfish-sf_19/src"
OUT="$ROOT/build/jnilibs"
mkdir -p "$WORK" "$OUT"

if [[ ! -d "$SRC" ]]; then
  echo "== Downloading official Stockfish 19 source (sf_19) =="
  command -v curl >/dev/null || { echo "ERROR: curl is required to fetch official Stockfish source." >&2; exit 1; }
  ARCHIVE="$WORK/stockfish-sf19.tar.gz"
  curl --fail --location --retry 2 --output "$ARCHIVE" \
    https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_19.tar.gz
  tar -xzf "$ARCHIVE" -C "$WORK"
fi
[[ -f "$SRC/uci.cpp" && -f "$WORK/Stockfish-sf_19/scripts/net.sh" && -f "$SRC/evaluate.h" ]] || {
  echo "ERROR: $SRC is not a complete official Stockfish sf_19 source tree." >&2; exit 1;
}

# Upstream script verifies downloaded NNUE by the network's SHA-derived filename.
if ! (cd "$SRC" && sh ../scripts/net.sh 1); then
  echo "ERROR: Stockfish NNUE fetch/validation failed. The engine cannot run without its network." >&2
  exit 1
fi

# Official Stockfish source units minus main.cpp; JNI bridge supplies the entry point/UCI loop.
SRCS=(attacks benchmark bitboard evaluate misc movegen movepick position search thread timeman tt uci ucioption tune syzygy/tbprobe nnue/nnue_accumulator nnue/nnue_misc nnue/network nnue/features/half_ka_v2_hm nnue/features/full_threats nnue/features/pp_3wide engine score memory)
COMMON="-std=c++17 -O3 -funroll-loops -DNDEBUG -fno-exceptions -fno-rtti -fPIC -Wall -Wno-unused-parameter -I$SRC"

build_abi() {
  local name="$1" cxx="$2" extra="$3"
  local mk; mk="$(mktemp -d "$WORK/obj.XXXXXX")"
  echo "== Building Stockfish 19 for $name =="
  local s
  for s in "${SRCS[@]}"; do
    local obj="$mk/$(basename "$s").o"
    (cd "$SRC" && "$NDKBIN/$cxx" $COMMON $extra -c "$s.cpp" -o "$obj")
  done
  "$NDKBIN/$cxx" $COMMON $extra -fexceptions -c "$ROOT/native/jni_bridge.cpp" -o "$mk/jni_bridge.o"
  local objs=()
  for s in "${SRCS[@]}"; do objs+=("$mk/$(basename "$s").o"); done
  "$NDKBIN/$cxx" -shared -Wl,-soname,libstockfish.so -static-libstdc++ \
    "${objs[@]}" "$mk/jni_bridge.o" -llog -o "$OUT/libstockfish.so.$name"
  "$NDKBIN/llvm-strip" --strip-unneeded "$OUT/libstockfish.so.$name"
  rm -rf "$mk"
  ls -lh "$OUT/libstockfish.so.$name"
}

# ABI flags follow upstream Stockfish's armv8/armv7 and x86-64 baseline feature macros.
build_abi arm64-v8a aarch64-linux-android29-clang++ "-DIS_64BIT -DUSE_POPCNT -DUSE_NEON=8"
build_abi armeabi-v7a armv7a-linux-androideabi29-clang++ "-DUSE_POPCNT -DUSE_NEON=7 -march=armv7-a -mthumb -mfpu=neon -mfloat-abi=softfp"
build_abi x86_64 x86_64-linux-android29-clang++ "-DIS_64BIT -DUSE_SSE2 -msse2 -DUSE_POPCNT -mpopcnt"

echo "Built official Stockfish 19 JNI shared libraries -> $OUT"
echo "Next: build the APK with tools/build-apk.sh and tools/package-apk.sh; verify on a real Android device before relying on it."
