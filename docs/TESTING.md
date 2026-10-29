# Testing - what was run, where, and what it proves

Four kinds of result are kept **strictly apart**. A result of one kind is never offered as proof of another.

| Kind | Where it runs | What it can prove | What it can NOT prove |
|---|---|---|---|
| **A. Automated desktop tests** | a Linux JVM (`tools/run-host-tests.sh`) | tracker logic, move generator, screen-reader logic on synthetic screenshots, the engine layer + JNI bridge against a real engine library compiled for the desktop | anything about an Android phone, the Android APIs (MediaProjection, overlay), the ARM64 library, performance on the phone |
| **B. Build checks** | CI (`.github/workflows/build.yml`) / your machine with the Android SDK+NDK | the ARM64 library and the APK can be built; the APK has the right files, permissions, alignment, signature (`tools/check-apk.sh`) | that the app starts or works |
| **C. Emulator test** | an Android emulator | the app starts, permissions screens, overlay | the ARM64 engine on a real CPU, real chess apps |
| **D. Physical device test** | the Realme 11 4G / Android 15 | everything | - |

## A. Automated desktop tests
Run: `tools/run-host-tests.sh` (needs a JDK, g++, Python 3 + Pillow, and access to GitHub for the Stockfish source and network file).

| Suite | Checks | What it covers |
|---|---|---|
| `TrackerTest` | 117 | identical frames never change anything; low-confidence frames; single legal moves; captures; castling (both sides, both colours); en passant; promotion (and under-promotion); both orientations and orientation flip; non-start positions; impossible transitions are refused; wildcard squares; ambiguous explanations are *not* guessed; recovery (LOST → FIX PIECES); take-back; new game; multi-ply gaps; identical-colour pictures from different pieces are never guessed; randomised games with noise, dropped frames and glitches (a wrong position may never be reported as trusted); speed |
| `CoreTest` | 15 | move generator against published perft numbers; the tracker following a short game from colour patterns alone |
| `VisionTest` | 57 | board detection + square reading on synthetic screenshots of different themes/sizes (start position, mid-game, endgame, dark theme, busy background, promotion, no board); confidence calibration; **degraded pictures** (noise, translucent highlights over squares): no wrong square may be trusted |
| `EngineTest` | 36 (Stockfish 11 test engine) / 7 (Stockfish 19, handshake only) / full with SF19 in CI | `uci`/`uciok`/`readyok`; engine name; legal `bestmove` for several positions incl. promotion, castling, en passant; forced mate / only-move positions prove the answer belongs to *its* position; 24 alternating analyses never mix answers; invalid FEN rejected; cancel; newer request after cancel; `ucinewgame`; restart; 3 × shutdown+start; analysis after shutdown |

### Results obtained while repairing the project (sandbox: Linux, no Android SDK/NDK, no access to the Stockfish network file)

| Run | Result |
|---|---|
| `TrackerTest` | 117 passed, 0 failed |
| `CoreTest` | 15 passed, 0 failed |
| `VisionTest` | 57 passed, 0 failed |
| `EngineTest` against the legacy Stockfish 11 build (tests the bridge/engine layer) | 36 passed, 0 failed |
| `EngineTest` against Stockfish 19 built **without** its network (`NO_NET=1`) | 7 passed, 0 failed - proves only: library loads, `uciok`, `readyok`, `id name Stockfish 19`, restart/shutdown cycle |
| `EngineTest` against Stockfish 19 **with** its network (real searches, `-Dexpect.engine="Stockfish 19"`) | **NOT RUN here** - the network file could not be downloaded in the sandbox. Runs in CI. |

Not run anywhere yet: B (APK build, `check-apk.sh` on a real APK), C, D.
`OverlayService.java` (the integration of tracker, engine and overlay) could not be compiled against `android.jar`
here; it was compiled with the Android classes missing and only errors that concern the project's own classes were
checked. The CI build (B) is the first real compile of it.

## B. Build checks (CI)
`.github/workflows/build.yml`: `tests` job (A, with the real Stockfish 19 and `-Dexpect.engine`) → `apk` job:
native build for ARM64 → `tools/build-apk.sh` → `tools/package-apk.sh` → `tools/check-apk.sh`.
`check-apk.sh` verifies: `classes.dex`; only `lib/arm64-v8a/libstockfish.so`; the library is **stored** (uncompressed);
the library hash equals the one in `engine-build-info.txt`; ELF is aarch64; segments are 16 KB aligned;
GPL licence, authors, source offer and build info packaged; package name, minSdk 26, the four declared permissions
(`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`), **no**
`INTERNET` permission; `zipalign` and `apksigner verify`.

## C/D. Emulator and physical device - manual checklist
Nobody has run these for this repair. Please run them on the phone and report what you see:

1. Install the APK; open the app; Diagnostics card says `engine: Stockfish 19` (not "not running").
2. Grant *Display over other apps*; **START PANEL** → *Start recording or casting?* → **Start now** (whole screen). The ♞ bubble appears.
3. Open a chess app with the start position; fit the board frame by dragging; **SHOW MY MOVE** gives a legal move for your side.
4. Leave the screen untouched for 2 minutes with AUTO on: pieces / FEN must not change (this is the reported bug).
5. Play 10 moves in both orientations; the panel follows every move; captures, castling, en passant, promotion.
6. Switch to another app and back; rotate; screen off/on; the panel recovers or asks for FIX PIECES - it never shows a wrong position as certain.
7. Stop the panel; the engine process thread ends (no battery drain).
