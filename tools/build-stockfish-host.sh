#!/usr/bin/env bash
# Build the same Stockfish 19 JNI bridge for host-side UCI integration tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SF_WORK_DIR:-$ROOT/build/sf}"
SRC="$WORK/Stockfish-sf_19/src"
OUT="$ROOT/build/host"
mkdir -p "$WORK" "$OUT"
if [[ ! -d "$SRC" ]]; then
  ARCHIVE="$WORK/stockfish-sf19.tar.gz"
  command -v curl >/dev/null || { echo "ERROR: curl is required to download official Stockfish 19." >&2; exit 1; }
  curl --fail --location --retry 2 --output "$ARCHIVE" \
    https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_19.tar.gz
  tar -xzf "$ARCHIVE" -C "$WORK"
fi
[[ -f "$SRC/uci.cpp" ]] || { echo "ERROR: incomplete official Stockfish sf_19 tree." >&2; exit 1; }
(cd "$SRC" && sh ../scripts/net.sh 1)
JH="${JAVA_HOME:-}"
if [[ -z "$JH" ]]; then JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"; fi
INC="-I$JH/include -I$JH/include/linux"
SRCS=(attacks benchmark bitboard evaluate misc movegen movepick position search thread timeman tt uci ucioption tune syzygy/tbprobe nnue/nnue_accumulator nnue/nnue_misc nnue/network nnue/features/half_ka_v2_hm nnue/features/full_threats nnue/features/pp_3wide engine score memory)
FLAGS="-std=c++17 -O2 -DNDEBUG -fPIC -fno-exceptions -fno-rtti -I$SRC $INC"
MK="$OUT/obj"; mkdir -p "$MK"
for s in "${SRCS[@]}"; do (cd "$SRC" && g++ $FLAGS -c "$s.cpp" -o "$MK/$(basename "$s").o"); done
g++ $FLAGS -fexceptions -c "$ROOT/native/jni_bridge.cpp" -o "$MK/jni_bridge.o"
OBJS=(); for s in "${SRCS[@]}"; do OBJS+=("$MK/$(basename "$s").o"); done
g++ -shared "${OBJS[@]}" "$MK/jni_bridge.o" -pthread -o "$OUT/libstockfish.so"
ls -lh "$OUT/libstockfish.so"
