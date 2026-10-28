#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build/test-classes build/vision-test-classes build/test/boards

printf '\n== Java tracker and chess-core regression tests ==\n'
javac -encoding UTF-8 -d build/test-classes \
  app/java/com/chesshint/panel/Board.java \
  app/java/com/chesshint/panel/Chess.java \
  app/java/com/chesshint/panel/Track.java \
  app/java/com/chesshint/panel/UciEngine.java \
  tools/tests/TrackerRegressionTest.java \
  tools/tests/CoreTest.java
java -cp build/test-classes TrackerRegressionTest
if [[ "${RUN_STOCKFISH_INTEGRATION:-0}" == "1" ]]; then
  if [[ ! -f build/host/libstockfish.so ]]; then
    echo "ERROR: RUN_STOCKFISH_INTEGRATION=1 but build/host/libstockfish.so is missing; run tools/build-stockfish-host.sh first." >&2
    exit 2
  fi
  java -Djava.library.path=build/host -cp build/test-classes CoreTest
else
  java -cp build/test-classes CoreTest --skip-engine
  echo "NOTE: Native Stockfish UCI integration skipped (set RUN_STOCKFISH_INTEGRATION=1 after tools/build-stockfish-host.sh)."
fi

printf '\n== Synthetic screenshot/vision regression tests ==\n'
python3 tools/tests/gen_boards.py build/test/boards
javac -encoding UTF-8 -d build/vision-test-classes \
  tools/tests/android-stubs/android/graphics/Rect.java \
  tools/tests/android-stubs/android/graphics/Bitmap.java \
  app/java/com/chesshint/panel/Board.java \
  app/java/com/chesshint/panel/Chess.java \
  app/java/com/chesshint/panel/Track.java \
  app/java/com/chesshint/panel/Vision.java \
  tools/tests/VisionTest.java
java -Xmx2g -cp build/vision-test-classes VisionTest build/test/boards

printf '\nAll enabled desktop tests completed. Android device and native engine status are documented separately.\n'
