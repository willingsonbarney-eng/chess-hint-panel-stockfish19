# Building

## Requirements
* JDK 17
* Android SDK: build-tools 34.0.0 + `platforms;android-34`
* Android NDK **r27c (27.2.12479018) or newer** - only to build the engine (Stockfish 19's own requirement for Android)
* Python 3 + Pillow, g++ (only for the desktop tests)
* Network access to github.com (Stockfish source + its NNUE network) while building the engine

## 1. Engine (Stockfish 19, ARM64)
```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c
tools/build-stockfish-android.sh      # fetches official sf_19, verifies the commit and the network hash
```
Output: `build/jnilibs/arm64-v8a/libstockfish.so` and `build/jnilibs/engine-build-info.txt`.
Optional: `SF_ARCH=armv8-dotprod` for CPUs with ARMv8.2 dot-product (faster, but crashes on CPUs without it).
See `docs/ENGINE.md`.

## 2. App
```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
tools/build-apk.sh       # aapt2 + javac + d8
tools/package-apk.sh     # adds the engine, licence + source offer, aligns, signs (creates a keystore on first run)
tools/check-apk.sh build/out/ChessHintPanel-v2.3.apk
```
Output: `build/out/ChessHintPanel-v2.3.apk` (about 100 MB: the Stockfish 19 network is inside the engine library).
The first run creates a signing key in `~/.signing/`. Keep it: only APKs signed with the same key can be installed
over each other.

## 3. Tests (desktop JVM)
```bash
tools/run-host-tests.sh                    # tracker, move generator, screen reader, REAL Stockfish 19 engine tests
SF_VERSION=11 tools/run-host-tests.sh      # bridge test against the legacy Stockfish 11 (no network file needed)
NO_NET=1 tools/run-host-tests.sh           # Stockfish 19 without network: handshake only
```
See `docs/TESTING.md` for what these results do and do not prove.

## CI
`.github/workflows/build.yml` runs the desktop tests (real Stockfish 19), builds the ARM64 engine and the APK,
runs `tools/check-apk.sh`, and uploads the APK + reports as workflow artifacts on every push / pull request.
`.github/workflows/build-apk.yml` does the same for version tags and attaches the APK to a GitHub release.
To keep one signing identity across releases, set the secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASS`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASS`.

### No Android SDK/NDK on your machine?
Fork/push the project to GitHub, open **Actions → Test and build APK → Run workflow**, and download the
`ChessHintPanel-apk` artifact when it finishes.
