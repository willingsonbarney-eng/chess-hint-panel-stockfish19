#!/usr/bin/env bash
# Builds official Stockfish 19 as an embedded JNI library for Android.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/ndk/27.2.12479018}}"
[ -d "$NDK" ] || { echo "Set ANDROID_NDK_HOME to Android NDK r27c (27.2.12479018) or later." >&2; exit 1; }
HOST_TAG=linux-x86_64
[ "$(uname)" = "Darwin" ] && HOST_TAG=darwin-x86_64
NDKBIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

WORK="${SF_WORK_DIR:-$ROOT/build/sf}"
ARCHIVE="$WORK/stockfish-sf19.tar.gz"
SRC="$WORK/Stockfish-sf_19/src"
OUT="$ROOT/build/jnilibs"
mkdir -p "$WORK" "$OUT"

if [ ! -f "$SRC/Makefile" ] || [ ! -f "$SRC/uci.h" ]; then
  echo "== fetching official Stockfish 19 source =="
  rm -rf "$WORK/Stockfish-sf_19"
  curl --fail --location --retry 3 --silent --show-error \
    -o "$ARCHIVE" https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_19.tar.gz
  tar xzf "$ARCHIVE" -C "$WORK"
fi

# Stockfish embeds its default NNUE file into network.cpp at compile time.
# Keeping the file in the source directory also lets the assembler resolve INCBIN.
echo "== fetching and validating the Stockfish 19 NNUE network =="
( cd "$SRC" && sh ../scripts/net.sh )

SRCS=(
  attacks bitboard benchmark evaluate misc movegen movepick position search thread timeman tt
  uci ucioption tune syzygy/tbprobe nnue/nnue_accumulator nnue/nnue_misc nnue/network
  nnue/features/half_ka_v2_hm nnue/features/full_threats nnue/features/pp_3wide
  engine score memory
)
COMMON_FLAGS=(-std=c++17 -stdlib=libc++ -O3 -DNDEBUG -fno-exceptions -fPIC -Wall -Wno-unused-result -pthread -I"$SRC")
JOBS="${BUILD_JOBS:-$(nproc 2>/dev/null || echo 2)}"
[ "$JOBS" -gt 0 ] || JOBS=2

build_abi() {
  local name="$1" cxx="$2"
  shift 2
  local -a extra_flags=("$@")
  local mk="$WORK/objects-$name"
  local -a objects=()
  local running=0
  mkdir -p "$mk"

  echo "== compiling Stockfish 19 for $name =="
  for s in "${SRCS[@]}"; do
    local obj="$mk/$(basename "$s").o"
    objects+=("$obj")
    (
      cd "$SRC"
      "$NDKBIN/$cxx" "${COMMON_FLAGS[@]}" "${extra_flags[@]}" \
        -c "$SRC/$s.cpp" -o "$obj"
    ) &
    running=$((running + 1))
    if [ "$running" -ge "$JOBS" ]; then
      wait -n
      running=$((running - 1))
    fi
  done
  wait

  "$NDKBIN/$cxx" "${COMMON_FLAGS[@]}" "${extra_flags[@]}" \
    -c "$ROOT/native/jni_bridge.cpp" -o "$mk/jni_bridge.o"
  "$NDKBIN/$cxx" -shared -Wl,-soname,libstockfish.so -static-libstdc++ \
    "${COMMON_FLAGS[@]}" "${extra_flags[@]}" "${objects[@]}" "$mk/jni_bridge.o" \
    -llog -o "$OUT/libstockfish.so.$name"
  "$NDKBIN/llvm-strip" --strip-unneeded "$OUT/libstockfish.so.$name"
  ls -lh "$OUT/libstockfish.so.$name"
}

# Use a conservative baseline per ABI so the same APK runs on a wide range of devices.
build_abi arm64-v8a aarch64-linux-android29-clang++ \
  -DIS_64BIT -DUSE_NEON=8 -DUSE_POPCNT -DUSE_PREFETCH -DARCH=armv8
build_abi armeabi-v7a armv7a-linux-androideabi29-clang++ \
  -DUSE_NEON=7 -DUSE_POPCNT -DUSE_PREFETCH -DARCH=armv7 \
  -march=armv7-a -mfloat-abi=softfp -mfpu=neon -mthumb
build_abi x86_64 x86_64-linux-android29-clang++ \
  -DIS_64BIT -DUSE_SSE2 -DUSE_POPCNT -DUSE_PREFETCH -DARCH=x86_64 -msse2 -mpopcnt

echo "Stockfish 19 JNI libraries built in $OUT"
