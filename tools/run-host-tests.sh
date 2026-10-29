#!/usr/bin/env bash
# Runs every desktop test.  These are AUTOMATED DESKTOP tests - they do not prove anything about a phone.
#
#   tools/run-host-tests.sh              tracker + core + vision + engine (needs libstockfish for the host)
#   SF_VERSION=11 tools/run-host-tests.sh   engine tests against the legacy Stockfish 11 build (bridge test only)
#   NO_NET=1 tools/run-host-tests.sh        Stockfish 19 without network: handshake smoke test of the engine
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SF_VERSION="${SF_VERSION:-19}"
B="$ROOT/build/tests"; rm -rf "$B"; mkdir -p "$B/classes"
cd "$ROOT/tools/tests"
javac -nowarn -encoding UTF-8 -d "$B/classes" -sourcepath "$ROOT/app/java:shim:." TrackerTest.java CoreTest.java EngineTest.java VisionTest.java 2>&1 | grep -v JAVA_TOOL || true
FAILED=0
echo "######## TrackerTest"; java -cp "$B/classes" TrackerTest || FAILED=1
echo "######## CoreTest (move generator, perft)"; java -Dskip.engine=true -cp "$B/classes" CoreTest || FAILED=1
echo "######## VisionTest (synthetic screenshots, screen reader + confidence)"
if python3 -c "import PIL" 2>/dev/null; then
  python3 gen_boards.py "$B/boards" > /dev/null && java -cp "$B/classes" VisionTest "$B/boards" || FAILED=1
else
  echo "SKIPPED: python3 with Pillow is needed to generate the test screenshots (pip install pillow)"
fi
LIBDIR="$ROOT/build/host-sf$SF_VERSION${NO_NET:+-nonet}"
if [ ! -f "$LIBDIR/libstockfish.so" ]; then SF_VERSION=$SF_VERSION bash "$ROOT/tools/build-stockfish-host.sh" > /dev/null; fi
SMOKE=""; [ -n "$NO_NET" ] && SMOKE="-Dengine.smoke=true"
EXPECT=""; [ "$SF_VERSION" = "19" ] && [ -z "$NO_NET" ] && EXPECT="-Dexpect.engine=Stockfish\ 19"
echo "######## EngineTest (libstockfish: $LIBDIR)"
eval java -Djava.library.path="$LIBDIR" $SMOKE $EXPECT -cp "$B/classes" EngineTest || FAILED=1
exit $FAILED
