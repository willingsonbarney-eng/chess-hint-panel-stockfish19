# Stockfish engine provenance and license

- **Selected stable release:** Stockfish 19
- **Official upstream repository:** https://github.com/official-stockfish/Stockfish
- **Exact upstream source tag:** `sf_19`
- **Official release announcement:** https://stockfishchess.org/blog/2026/stockfish-19/
- **Build integration:** `tools/build-stockfish-android.sh` compiles the official source units (excluding upstream `main.cpp`) with the app's JNI stream bridge in `native/jni_bridge.cpp`. The bridge uses Stockfish 19's `Stockfish::UCIEngine` API. It is not compatible with the old Stockfish 11 global `UCI::loop` API.
- **Android targets:** arm64-v8a is the required target for the requested ARM64 handset; the script also builds armeabi-v7a and x86_64 compatibility libraries. NDK r27c or newer is required by the official Stockfish 19 makefile's Android build notes. The supplied script uses Android API 29 NDK compiler targets and embeds the NNUE network into each shared library at compile time.
- **Runtime:** the engine runs locally inside `libstockfish.so` through UCI. After installation, analysis does not need a network connection or an external engine app. Network access is required only when building from a clean source tree so the official Stockfish source and its NNUE file can be downloaded.
- **Version validation:** `UciEngine.start()` requires a UCI `id name` containing `Stockfish 19` and waits for both `uciok` and `readyok`. The engine regression test checks the version and a legal bestmove when a host JNI library can be built.

## GPL-3.0-or-later

Stockfish is free software licensed under the GNU General Public License version 3 or (at your option) any later version. The complete GPLv3 license text is in [`THIRD_PARTY_LICENSES/GPL-3.0.txt`](THIRD_PARTY_LICENSES/GPL-3.0.txt). Stockfish source code is available at the official `sf_19` tag: https://github.com/official-stockfish/Stockfish/tree/sf_19 . The build scripts fetch that exact tag and compile it locally. The built engine is a combined work containing the JNI bridge; anyone redistributing a compiled APK/shared library must comply with GPLv3, including providing corresponding source for the exact engine and modifications and preserving applicable notices. This uploaded project ZIP does not include upstream Stockfish source or NNUE weights because the build environment used for this repair had no network access; the documented tag/build script is the source retrieval path.
