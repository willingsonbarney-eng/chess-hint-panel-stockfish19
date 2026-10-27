#!/usr/bin/env bash
# Builds the same Stockfish 19 JNI library for desktop engine tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SF_WORK_DIR:-$ROOT/build/sf}"
ARCHIVE="$WORK/stockfish-sf19.tar.gz"
SRC="$WORK/Stockfish-sf_19/src"
OUT="$ROOT/build/host"
mkdir -p "$WORK" "$OUT"

if [ ! -f "$SRC/Makefile" ] || [ ! -f "$SRC/uci.h" ]; then
  echo "== fetching official Stockfish 19 source =="
  rm -rf "$WORK/Stockfish-sf_19"
  curl --fail --location --retry 3 --silent --show-error \
    -o "$ARCHIVE" https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_19.tar.gz
  tar xzf "$ARCHIVE" -C "$WORK"
fi
( cd "$SRC" && sh ../scripts/net.sh )

JH="${JAVA_HOME:-}"
if [ -z "$JH" ] && command -v javac >/dev/null 2>&1; then
  JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
[ -n "$JH" ] || { echo "Set JAVA_HOME to a JDK so jni.h is available." >&2; exit 1; }
case "$(uname -s)" in
  Darwin) JNI_OS=darwin ;;
  *) JNI_OS=linux ;;
esac

CXX="${CXX:-g++}"
SRCS=(
  attacks bitboard benchmark evaluate misc movegen movepick position search thread timeman tt
  uci ucioption tune syzygy/tbprobe nnue/nnue_accumulator nnue/nnue_misc nnue/network
  nnue/features/half_ka_v2_hm nnue/features/full_threats nnue/features/pp_3wide
  engine score memory
)
FLAGS=(-std=c++17 -O1 -DNDEBUG -fno-exceptions -fPIC -pthread -DIS_64BIT \
  -I"$SRC" -I"$JH/include" -I"$JH/include/$JNI_OS")
MK="$OUT/obj"
mkdir -p "$MK"
JOBS="${BUILD_JOBS:-$(nproc 2>/dev/null || echo 2)}"
[ "$JOBS" -gt 0 ] || JOBS=2
running=0
for s in "${SRCS[@]}"; do
  obj="$MK/$(basename "$s").o"
  (
    cd "$SRC"
    "$CXX" "${FLAGS[@]}" -c "$SRC/$s.cpp" -o "$obj"
  ) &
  running=$((running + 1))
  if [ "$running" -ge "$JOBS" ]; then
    wait -n
    running=$((running - 1))
  fi
done
wait
(
  cd "$SRC"
  "$CXX" "${FLAGS[@]}" -c "$ROOT/native/jni_bridge.cpp" -o "$MK/jni_bridge.o"
)
objects=()
for s in "${SRCS[@]}"; do objects+=("$MK/$(basename "$s").o"); done
"$CXX" -shared -pthread "${FLAGS[@]}" "${objects[@]}" "$MK/jni_bridge.o" \
  -o "$OUT/libstockfish.so"
ls -lh "$OUT/libstockfish.so"
