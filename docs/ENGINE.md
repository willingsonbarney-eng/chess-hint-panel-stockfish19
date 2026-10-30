# The chess engine: Stockfish 19

| | |
|---|---|
| Engine | **Stockfish 19** - official release, git tag `sf_19` |
| Official source | https://github.com/official-stockfish/Stockfish (the project's own repository; releases are announced on https://stockfishchess.org) |
| Commit | `edb0d9db6731067ec50ce619ff372b463bc4dd5d` (checked by `tools/stockfish-common.sh` before every build; any other commit is refused) |
| Language / standard | C++17 |
| Evaluation | NNUE network embedded in the library (`nn-1a298aa575a0.nnue`, name = first 12 hex digits of its SHA-256, verified by the build) |
| ABI | `arm64-v8a` only - the Realme 11 4G (MediaTek Helio G99, ARM64) and every current Android phone |
| CPU flavour | `SF_ARCH=armv8` (default: NEON, runs on every ARM64 phone). `SF_ARCH=armv8-dotprod` is optional: faster on CPUs with the ARMv8.2 dot-product instructions (the Helio G99 has them), but it would crash on a phone without them, so it is not the default. |
| Toolchain | Android NDK r27c or newer (Stockfish's own requirement for Android builds), minimum API 26 |
| Packaging | `lib/arm64-v8a/libstockfish.so`, stored uncompressed in the APK (`extractNativeLibs="false"`), 16 KB page aligned (`-z max-page-size=16384`, OK for Android 15 devices with 16 KB pages) |
| How it runs | loaded with `System.loadLibrary` and run on its own thread inside the app (`native/jni_bridge.cpp`); no executable is ever started from disk, nothing is downloaded at run time |
| Licence | GNU GPL v3 - `LICENSE-STOCKFISH-GPLv3.txt` and `STOCKFISH-AUTHORS.txt` are packaged in the APK (assets/) |
| Source offer | `docs/SOURCE-OFFER.txt` (also packaged in the APK) |

The exact version, compiler, NDK, flags, network hash and library hash of **a particular APK** are written
by the build into `assets/engine-build-info.txt` inside that APK. Open the APK as a ZIP to read it; the
app's **Diagnostics** card also shows the engine's own `id name` line (it must read `Stockfish 19`).

## Why Stockfish 19 was chosen
Stockfish 19 (tag `sf_19`) is the most recent stable official release; it was checked on the official
GitHub repository at the time of this work. Its Makefile supports Android natively (`COMP=ndk`).
The previous app version used Stockfish 11 (2020, classical evaluation, no neural network).

## How the app uses the engine (`UciEngine.java`)
* Start: `uci` → waits for `uciok` → `isready` → waits for `readyok`. Failure to reach both is reported, never hidden.
* Defaults (`Tune.java`): `Threads` = min(4, cores/2) (leaves the little cores alone), `Hash` 16 / 32 / 64 MB chosen from the app's Java heap limit, `Ponder` off, move time from Settings (`go movetime`), strength limit only if set in Settings (`UCI_LimitStrength` / `UCI_Elo`).
* One analysis: the FEN is validated first (`Chess.validate`; garbage / impossible / game-over positions are rejected without touching the engine) → any running search is stopped → `isready`/`readyok` (so nothing sent earlier is still pending) → `position fen <FEN>` → `go movetime N`. `ucinewgame` is sent on *New game*.
* The result object carries the FEN that was analysed and a caller tag. The app discards a result whose FEN is no longer the latest trusted board, so an old `bestmove` can never be shown for a new position.
* `cancel()` sends `stop` and the pending `bestmove` is consumed and marked cancelled. A late `bestmove` can never be mistaken for the next search's answer.
* `restart()` / `shutdown()` stop the engine thread cleanly (`quit`, with an end-of-input fallback); `OverlayService.onDestroy` shuts it down.

## Rebuilding the engine yourself
```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c      # or newer
tools/build-stockfish-android.sh                       # needs access to github.com for the source and the network file
# -> build/jnilibs/arm64-v8a/libstockfish.so  +  build/jnilibs/engine-build-info.txt
```
Desktop build of the same bridge for the test-suite: `tools/build-stockfish-host.sh` (`SF_VERSION=11` builds the
old Stockfish 11 used only to test the bridge code itself; it is never packaged).

## How Stockfish 19 was verified
* The network file `nn-1a298aa575a0.nnue` is served by the official `official-stockfish/networks` repository
  (`github.com/official-stockfish/networks`, LFS media); `scripts/net.sh` / `tools/stockfish-common.sh` check that the
  first 12 hex digits of its SHA-256 equal the digits in its name before it is embedded.
* With it, the real Stockfish 19 was compiled for the desktop through the same `native/jni_bridge.cpp` and
  `UciEngine` that the phone uses, and `tools/tests/EngineTest.java` passed (41 checks, repeated runs). That proves
  the engine layer and bridge on a desktop CPU. The ARM64 library itself was built with NDK r27c and inspected
  (aarch64, 16 KB aligned, JNI exports present), but it has **not been executed** on an ARM device: no emulator or phone
  test has been done. See `docs/TESTING.md`.
