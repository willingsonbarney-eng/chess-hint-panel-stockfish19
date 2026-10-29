#!/usr/bin/env bash
# Desktop (Linux) build of the exact same JNI bridge + engine - used by tools/tests/EngineTest.
#
#   SF_VERSION=19  (default) official Stockfish 19 with its NNUE network (downloaded + verified)
#   SF_VERSION=11  legacy Stockfish 11, only to test the bridge where the network is unreachable
#   NO_NET=1       Stockfish 19 WITHOUT the embedded network: the library loads and answers the
#                  UCI handshake, but cannot search - a link/handshake smoke test, nothing more
set -e
source "$(dirname "$0")/stockfish-common.sh"
OUT="$ROOT/build/host-sf$SF_VERSION${NO_NET:+-nonet}"; mkdir -p "$OUT"
sf_fetch_source
EXTRA=""
if [ "$SF_VERSION" = "19" ]; then
  if [ -n "$NO_NET" ]; then EXTRA="-DNNUE_EMBEDDING_OFF"; else sf_fetch_net; fi
  STD="-std=c++17"; ARCHF="-DIS_64BIT -DUSE_POPCNT -DUSE_SSE41 -DUSE_SSSE3 -DUSE_SSE2 -msse4.1 -mssse3 -mpopcnt"
else
  STD="-std=c++11"; ARCHF="-DIS_64BIT"; EXTRA="-DSF_LEGACY_11"
fi
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
INC="-I$JH/include -I$JH/include/linux"
FLAGS="$STD -O2 -DNDEBUG -fPIC -fno-exceptions -w -pthread $ARCHF $EXTRA -I$SF_SRC $INC"
MK="$OUT/obj"; rm -rf "$MK"; mkdir -p "$MK"
OBJS=""
for s in $(sf_sources); do
  o="$MK/$(echo "$s" | tr / _).o"; OBJS="$OBJS $o"
  echo "g++ $FLAGS -c $SF_SRC/$s.cpp -o $o"
done | xargs -P "$(nproc 2>/dev/null || echo 2)" -I{} bash -c '{}'
OBJS=""; for s in $(sf_sources); do OBJS="$OBJS $MK/$(echo "$s" | tr / _).o"; done
g++ $FLAGS -c "$ROOT/native/jni_bridge.cpp" -o "$MK/jni_bridge.o"
g++ -shared $FLAGS $OBJS "$MK/jni_bridge.o" -o "$OUT/libstockfish.so"
ls -la "$OUT/libstockfish.so"
echo "$OUT"
