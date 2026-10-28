# ChessHintPanel repair report (source build: v2.3)

## Delivery status

This ZIP contains the complete edited project source, resources, documented build scripts, regression tests, and the full GPL-3.0 license text. **It does not contain a newly built v2.3 APK or a compiled Stockfish 19 library.** The Android SDK/build-tools/NDK were not installed in the repair container, and outbound DNS for `github.com` failed when the official Stockfish source/NNUE fetch was attempted. The historical APK files in `apk/` are unchanged legacy releases and are not the repaired build.

## Root cause found in the original source

The identity changes were caused by an unsafe fallback and state-repair combination rather than by a single frame delay:

1. `OverlayService.syncTrackerWithVision()` compared a tracked correction against each raw square observation and, if they differed, fell back to a fresh `Vision.guessBoard()` result. That classifier estimates piece identity from silhouette/pattern features on each frame; a transient noisy frame could therefore replace known stationary-piece identities with speculative ones. Other hint paths performed similar fresh-guess fallbacks and could send that guessed FEN to the engine.
2. The overlay force-set `track.pos.side` to a target turn after a recognized move, then called `Chess.sanitize()`. The sanitizer repairs inconsistent positions by altering piece types. For a check-giving move, overwriting the real side-to-move could create a false check/turn inconsistency and cause the checking piece to be relabelled. This matches the reported symptom of stationary identities changing between frames.
3. The old tracker also accepted an opposite-side fallback by flipping the side, clearing en-passant information, and sanitizing the position. It treated the first bounded-search result as enough, even if the search was capped or multiple legal explanations existed. A follow-up audit found `Chess.Pos.fen()` also called that sanitizer on a copy before serialization, so even if the tracked board was intact, the FEN sent to Stockfish could relabel pieces. The sanitizer has now been removed from the trusted-state/FEN path; the exact board is serialized and invalid positions are rejected by an engine preflight validator.

## Changes made

- `Track.java`: preserve the last trusted state across uncertain frames; reconcile whole-board observations through legal move sequences; support captures, castling, en passant, and promotion; accept a transition only when a unique legal explanation is found within the search budget; reject capped/ambiguous/impossible observations without mutating the trusted FEN/history; constrain expected side-to-move rather than forcibly changing it after the move.
- `Vision.java`: carry piece-code and type-confidence data from the classifier, explicitly treating its heuristic score as an uncalibrated margin rather than a reliable probability.
- `OverlayService.java`: remove live per-frame silhouette guesses as fallback FENs; do not call Stockfish when the observed position is uncertain; preserve side-to-move and piece types; add a recovery/confirmation path for an arbitrary mid-game position using the existing board/FEN editing flow; ensure engine shutdown is called by the overlay lifecycle.
- `UciEngine.java` and `native/jni_bridge.cpp`: implement the Stockfish 19 `sf_19` UCI/JNI API path, UCI `uciok`/`readyok` checks, serialized searches, stale-search draining, cancellation/shutdown/restart handling, and limited mobile defaults. These native paths are source changes, **not runtime-verified here**.
- Build scripts and workflows: pin the official `sf_19` source tag, use Android NDK r27c or newer with API-29 target compilers, fetch NNUE through Stockfish's upstream hash-checking network helper, and embed it in the native shared library at build time.
- Added tracker/core/screenshot tests, build guidance, this report, Stockfish provenance notes, and a full GPL-3.0 license text.

The implementation intentionally does not infer arbitrary mid-game piece types from a single screenshot when the image classifier cannot establish them. Such positions should be confirmed in the existing board editor/FEN workflow. The visual board editor does not know historical castling rights, so it clears them rather than guessing; use an explicit FEN to retain known castling rights and en-passant metadata. This avoids silently inventing a position for Stockfish.

## Regression results actually run

Command:

```sh
bash tools/test-desktop.sh
```

| Suite | Result | Coverage / limitation |
|---|---:|---|
| Tracker regression | 14 passed, 0 failed | Stationary/noisy frames, low confidence, legal moves, multi-ply recovery, orientation, captures, castling, en passant, promotion, check-giving moves, turn mismatch, impossible transitions, ambiguity/rejection behavior |
| Chess core | 20 passed, 0 failed | Legal generation/known perft counts, move sequences, catch-up, ambiguity handling, exact FEN serialization and strict position preflight; invoked with `--skip-engine` |
| Synthetic screenshot/vision | 27 passed, 0 failed | Synthetic boards in six styles/positions and a no-board negative case |
| Total enabled desktop checks | **61 passed, 0 failed** | Host-side tests only |

Additional checks and blockers:

- `bash -n` for the build/test shell scripts and XML parsing of the Android manifest are expected preflight checks (see the build instructions); these do not replace an Android build.
- Attempted `bash tools/build-apk.sh`; blocked because `$HOME/Android/Sdk/build-tools/34.0.0/aapt2` is not present. No Android SDK `android.jar`, `aapt2`, `d8`, or `adb` was available in the environment.
- Attempted `bash tools/build-stockfish-host.sh`; blocked because this environment could not resolve `github.com` while fetching the official source. Consequently native Stockfish compilation, version reporting, UCI handshake, `bestmove`, NNUE loading, and native lifecycle tests were **not run**.
- No emulator or physical Android device was available. Android 15 permissions, overlay behavior, real screenshots, ARM64 loading/performance, and thermal behavior were **not device-tested**.

## Stockfish build target and source licensing

- Official stable release used as build target: **Stockfish 19**, tag `sf_19`, upstream release commit `edb0d9d` (official release date 2026-09-05).
- Official source tag: https://github.com/official-stockfish/Stockfish/tree/sf_19
- Official release announcement: https://stockfishchess.org/blog/2026/stockfish-19/
- Android build requirement: upstream's documented Android NDK path requires r27c or newer; this project's script uses Android API 29 compiler targets and attempts `arm64-v8a`, `armeabi-v7a`, and `x86_64` outputs. The requested phone target is `arm64-v8a` in the build script; physical device ABI was not verified in this run.
- GPL: the full GPL-3.0 text is in `THIRD_PARTY_LICENSES/GPL-3.0.txt`. The project ZIP does not include Stockfish source or NNUE because network access failed. For distribution of a built APK/native library, include or otherwise provide the corresponding source and notices for the exact Stockfish source, NNUE data, and modified JNI bridge in compliance with GPLv3-or-later.

## Build on a machine with the required tools

See `docs/BUILDING.md`. In brief: install JDK 17+, Android SDK platform 34/build-tools 34.0.0, Android NDK r27c+, curl/tar/hash utilities, and Python 3 + Pillow; provide network access for the official source and NNUE fetch; then run:

```sh
bash tools/test-desktop.sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c
bash tools/build-stockfish-android.sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
bash tools/build-apk.sh
bash tools/package-apk.sh
```

The expected output is `build/out/ChessHintPanel-v2.3.apk`, but it must be built and tested on that machine before being treated as a working APK. For the native engine handshake and search regression suite, also build the host library with `bash tools/build-stockfish-host.sh` and run `RUN_STOCKFISH_INTEGRATION=1 bash tools/test-desktop.sh`.

## Install on a Realme 11 4G / Android 15 after building

1. Transfer the newly built `build/out/ChessHintPanel-v2.3.apk` to the phone and install it. Android may ask you to allow this file manager/browser to install unknown apps.
2. Open ChessHintPanel and grant **Display over other apps** when prompted.
3. Start screen capture using the app's own prompt and accept Android's MediaProjection confirmation.
4. Open the chess game and use the existing board-frame selector to outline the board. Set/flip orientation as needed.
5. If starting from an arbitrary mid-game position and the tracker marks it uncertain, use the existing **Fix Position** / board editor or FEN workflow to confirm the pieces and turn. Analysis remains withheld while the current position is uncertain.
6. Verify the in-app engine diagnostic reports Stockfish 19 and test a legal best move before relying on the overlay. This validation must be performed on the real device; it was not possible in this environment.

## File change inventory

Changed existing files:

- `.github/workflows/build.yml`, `.github/workflows/build-apk.yml`
- `CHANGELOG.md`, `LICENSE`, `README.md`
- `app/AndroidManifest.xml`
- `app/java/com/chesshint/panel/Chess.java`, `MainActivity.java`, `OverlayService.java`, `SettingsActivity.java`, `Track.java`, `UciEngine.java`, `Vision.java`
- `docs/ARCHITECTURE.md`, `docs/BUILDING.md`, `docs/INSTALL.md`
- `native/jni_bridge.cpp`
- `tools/build-apk.sh`, `tools/build-stockfish-android.sh`, `tools/build-stockfish-host.sh`, `tools/package-apk.sh`
- `tools/tests/CoreTest.java`, `tools/tests/VisionTest.java`, `tools/tests/TrackerRegressionTest.java`, `tools/tests/gen_boards.py`

Added files:

- `REPAIR_REPORT.md`, `STOCKFISH.md`, `THIRD_PARTY_LICENSES/GPL-3.0.txt`
- `tools/test-desktop.sh`
- `tools/tests/android-stubs/android/graphics/Bitmap.java`
- `tools/tests/android-stubs/android/graphics/Rect.java`

Historical APK files under `apk/` and other existing app resources were preserved without modification.
