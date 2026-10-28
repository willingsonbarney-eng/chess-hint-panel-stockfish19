♞ Chess Hint Panel

A floating chess analysis panel for Android that displays move arrows, square highlights, and engine suggestions over a chessboard.

This repository contains the repaired ChessHintPanel source, Android build workflow, Stockfish integration scripts, documentation, and archived APKs.

«Build status: The GitHub Actions workflow successfully completed and produced the "ChessHintPanel-apk" artifact. Download the artifact from the repository's Actions page to obtain the APK build output.»

👥 Credits

- Repository owner and maintainer: "@willingsonbarney-eng" (https://github.com/willingsonbarney-eng)
- Repaired source and repository maintenance: "chess-hint-panel-stockfish19" (https://github.com/willingsonbarney-eng/chess-hint-panel-stockfish19)
- Chess engine: "Stockfish" (https://github.com/official-stockfish/Stockfish), an open-source chess engine distributed under GPLv3 or later.
- Other contributors: Credit belongs to the respective authors of the source code, libraries, and tools used by this project.

This repository's maintainer is not claiming authorship of third-party software. Original upstream authors should be credited according to their respective project licenses and notices.

✨ Features

- Move suggestions: Displays a suggested move using arrows and square markers.
- Offline engine analysis: Targets Stockfish 19, subject to a successful native build and device testing.
- Floating panel: Designed to work over compatible Android chess applications.
- Board recognition: Detects the board and reads piece positions from screen captures.
- Position tracking: Reconciles detected changes against legal chess moves to help prevent piece identities from changing unexpectedly.
- Customizable markers: Supports different marker styles, colors, and sizes.
- Settings and diagnostics: Includes configuration, troubleshooting, and log-sharing features.
- Android support: Uses Android screen capture and overlay permissions. Compatibility must be verified on the target device.

Fair-play notice: Use engine assistance for offline games, study, and analysis. Using outside assistance during online games may violate platform rules.

📥 Download and install

Latest successful build

1. Open the repository's "Actions page" (https://github.com/willingsonbarney-eng/chess-hint-panel-stockfish19/actions).
2. Open the successful Build APK workflow run.
3. Under Artifacts, select "ChessHintPanel-apk".
4. Extract the downloaded ZIP and locate the APK file.
5. Transfer or open the APK on your Android device and follow Android's installation prompts.

The artifact is a build output, not necessarily a single APK. Check the extracted filenames before installing.

Build from source

Follow these project guides:

- "Build instructions" (docs/BUILDING.md)
- "Installation guide" (docs/INSTALL.md)
- "Settings guide" (docs/SETTINGS.md)
- "Frequently asked questions" (docs/FAQ.md)

A successful workflow run confirms that the configured workflow completed. It does not, by itself, prove that every feature works correctly on every Android device.

📦 Archived APK versions

The following historical APK files were present in the uploaded source package. They are listed as archived builds, not as newly repaired releases.

Version| File
2.2| "apk/ChessHintPanel-v2.2.apk"
2.1| "apk/ChessHintPanel-v2.1.apk"
1.8| "apk/ChessHintPanel-v1.8.apk"
1.7| "apk/ChessHintPanel-v1.7.apk"
1.6| "apk/ChessHintPanel-v1.6.apk"
1.5| "apk/ChessHintPanel-v1.5.apk"
1.4| "apk/ChessHintPanel-v1.4.apk"
1.3| "apk/ChessHintPanel-v1.3.apk"
1.2| "apk/ChessHintPanel-v1.2.apk"
1.1| "apk/ChessHintPanel-v1.1.apk"
1.0| "apk/ChessHintPanel-v1.0.apk"

The uploaded package did not include "v1.9" or a historical "v2.3" APK. The successful GitHub Actions artifact should be treated separately from these archived files.

For detailed historical changes, see "CHANGELOG.md" (CHANGELOG.md).

🧪 Testing

Run the available desktop tests from the project root:

bash tools/test-desktop.sh

The repository also includes regression tests for chess logic, position tracking, and vision processing.

Tests performed on a desktop or with synthetic images do not guarantee identical behavior on a physical Android device. Native Stockfish integration, screen capture, overlay behavior, and piece recognition should be verified on the intended device.

🔒 Privacy

The app uses Android screen-capture and overlay capabilities. Review the source code and "privacy documentation" (docs/PRIVACY.md) to understand its permissions and data handling.

Do not assume that all builds have identical behavior; verify the source and permissions of the APK you install.

⚖️ Licenses

- Application source: see "LICENSE" (LICENSE).
- Stockfish engine: GPLv3 or later. See "STOCKFISH.md" (STOCKFISH.md) and the relevant upstream license.
- Third-party notices: see "THIRD_PARTY_LICENSES" (THIRD_PARTY_LICENSES).

Any redistributed build must comply with the applicable licenses and include the required notices and source-code information.

⭐ Contributing

Issues, reproducible bug reports, and improvements are welcome. When reporting a bug, include the Android version, device model, steps to reproduce, and relevant diagnostic logs. Remove personal or sensitive information from logs before sharing.

---

Maintained by "@willingsonbarney-eng" (https://github.com/willingsonbarney-eng).

Learn chess, analyze positions, and improve your understanding of the game. a release number.

Full history: ""CHANGELOG.md"" (CHANGELOG.md)

⚖️ About "8000 Elo"

No chess engine reaches 8000 Elo. Engine ratings depend on the rating list, hardware, time control, and testing conditions. Stockfish is among the strongest chess engines available, but its measured rating varies by test environment. The strength selector is intended to let users choose a weaker setting when available.

⚠️ Fair play

Engine assistance during online games may violate the rules of chess platforms and can lead to account restrictions. Use this application for learning, analysis and offline games, and follow the rules of the platform you are using.

📄 License

* App source: MIT — see "LICENSE" (LICENSE).
* Engine build target: official "Stockfish 19" (https://github.com/official-stockfish/Stockfish/tree/sf_19) — GPLv3-or-later. "tools/build-stockfish-android.sh" is intended to fetch and build the pinned source tag plus the upstream NNUE network. Redistribution of a resulting APK must comply with the applicable licenses and include the required notices and corresponding source for the exact engine and bridge build.

See ""STOCKFISH.md"" (STOCKFISH.md) and ""THIRD_PARTY_LICENSES"" (THIRD_PARTY_LICENSES) for license and provenance details.

<div align="center"><sub>Maintained by <a href="https://github.com/willingsonbarney-eng">willingsonbarney-eng</a>. Built for players who want to learn and analyze chess offline.</sub></div>Stockfish engine

This branch targets the official Stockfish 19 stable release ("sf_19"). See ""STOCKFISH.md"" (STOCKFISH.md) for provenance, GPLv3-or-later notices, the exact source tag, NNUE embedding, and build details.
