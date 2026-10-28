# Building ChessHintPanel

## Requirements

- JDK 17 or newer
- Android SDK with `platforms;android-34` and build-tools `34.0.0`
- Android NDK r27c or newer for the native engine build
- `curl`, `tar`, and `sha256sum` for fetching upstream source and the exact Stockfish NNUE network
- Python 3 and Pillow for the synthetic screenshot tests
- Network access for the first Stockfish source/network fetch; the built app itself runs analysis locally/offline

## 1. Build the bundled Stockfish 19 engine

Stockfish 19 is pinned to the official `sf_19` source tag. The script downloads the official source archive, calls the upstream network helper (including its SHA-256 validation), compiles the engine into a JNI shared library, and embeds the NNUE network at build time.

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c
# Optional if you do not want the work/source cache under this checkout:
# export SF_WORK_DIR=/path/to/build-cache/stockfish
bash tools/build-stockfish-android.sh
```

The script builds `arm64-v8a` (the primary target for a 64-bit Realme 11 4G), plus `armeabi-v7a` and `x86_64` for existing supported device/emulator configurations. Output: `build/jnilibs/libstockfish.so.<abi>`.

## 2. Build and package the APK

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
bash tools/build-apk.sh
bash tools/package-apk.sh
```

The package step copies the matching ABI library into the APK, aligns it, and signs it (creates a local debug/test keystore when needed). Output: `build/out/ChessHintPanel-<version>.apk`.

## 3. Desktop regression tests

```bash
bash tools/test-desktop.sh
```

The desktop suite runs the pure-Java chess move generator/tracker tests and the synthetic screenshot detector tests. It does **not** validate Android screen-capture permissions, the floating overlay, hardware-specific ARM behavior, or Stockfish UCI unless a host engine is available and `RUN_STOCKFISH_INTEGRATION=1` is set.

For the native UCI integration test (requires the same official Stockfish source, network, and host compiler):

```bash
bash tools/build-stockfish-host.sh
RUN_STOCKFISH_INTEGRATION=1 bash tools/test-desktop.sh
```

## 4. Physical-device verification

Install `build/out/ChessHintPanel-<version>.apk` on an Android 15 test device, grant display-over-other-apps and screen-capture permissions, confirm board selection/orientation, and run the engine version/UCI status from the in-app diagnostic/log UI. A desktop test is not a substitute for this physical-device test.

## Reproducibility and license

The exact Stockfish source tag, network embedding process, ABI flags, and GPLv3/source-distribution notes are in [`STOCKFISH.md`](../STOCKFISH.md). A build requires downloading the official, hash-validated NNUE network. The app does not need network access after a successful build because the network is compiled into `libstockfish.so`.
