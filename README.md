<div align="center"><img src="docs/screenshots/hero.png" width="900" alt="Chess Hint Panel — arrow, arrow-only and square hint styles drawn over a chess app">♞ Chess Hint Panel

A floating chess coach for Android.

This repaired source targets official Stockfish 19 and improves board-state trust/reconciliation. A compiled v2.3 APK was not produced in the original repair environment because Android SDK/NDK tools were unavailable and the engine source/NNUE download was blocked. The project has since been built through GitHub Actions. Download the generated artifact from the repository's Actions page and test it on your device before relying on it. No root or paid analysis API is required; analysis is intended to run offline after a successful build.

""Build APK" (https://github.com/willingsonbarney-eng/chess-hint-panel-stockfish19/actions/workflows/build-apk.yml/badge.svg)" (../../actions/workflows/build-apk.yml)
"Android" (https://img.shields.io/badge/Android-8.0%20%E2%80%93%2015-3ddc84)
"Offline" (https://img.shields.io/badge/internet-not%20required-informational)
"License" (https://img.shields.io/badge/license-MIT%20(app)%20%2B%20GPLv3%20(engine)-yellow)

"🔧 Build the repaired APK" (docs/BUILDING.md)  ·  "📥 Install guide" (docs/INSTALL.md)  ·  "🎛 Settings" (docs/SETTINGS.md)  ·  "❓ FAQ" (docs/FAQ.md)  ·  "🔒 Privacy" (docs/PRIVACY.md)

</div>---

👥 Credits

- Repository owner and maintainer: "@willingsonbarney-eng" (https://github.com/willingsonbarney-eng)
- Repaired project repository: "Chess Hint Panel — Stockfish 19" (https://github.com/willingsonbarney-eng/chess-hint-panel-stockfish19)
- Chess engine: "Stockfish" (https://github.com/official-stockfish/Stockfish), licensed under GPLv3 or later.
- Original authors and contributors: Credit remains with the original authors and third-party projects according to their respective licenses and notices.

This repository contains repaired and maintained source code. The repository owner does not claim authorship of third-party code or assets.

---

📱 The app

Home| Settings — live preview| Floating panel
<img src="docs/screenshots/app-1-home.png" width="250">| <img src="docs/screenshots/app-3-settings.png" width="250">| <img src="docs/screenshots/app-4-panel.png" width="250">
Two permissions, one button, diagnostics with a shareable log| Pick the mark style, colours and size — the preview shows exactly what your board will look like| Tap the floating ♞ anywhere, drag it around, tiles for every action

Screen-capture troubleshooting| Fitting the board| Fixing the pieces
<img src="docs/screenshots/app-2-problem.png" width="250">| <img src="docs/screenshots/app-5-fit-board.png" width="250">| <img src="docs/screenshots/app-6-fix-pieces.png" width="250">
Reports capture issues, offers Retry screen reading and a Share log button| Drag the frame exactly over the board — corners to resize, middle to move| Joined a game mid-way? Confirm the exact position in the existing editor or use FEN

✨ Features

| 
🎯 Tells you the move| Green ring on the piece, amber ring on the target square, a fat arrow between them and a badge like "YOUR MOVE ♘ g1 → f3".
🧠 Real engine| Stockfish 19 is the pinned build target and is intended to be bundled into the APK after a successful native build for arm64 / armv7 / x86_64. Verify the engine on your device before use.
👁 Any chess app| chess.com, Lichess, Chess Play, offline games… it reads the screen like your eyes do. Recognition compatibility may vary with board themes and layouts.
🙋 Only your move| Bottom half of the board = you, top = enemy. While the opponent thinks it is designed to stay silent. Verify this behavior during testing.
🎨 Your marks| Arrow + rings · arrow only · rings only · filled squares — 5 colour sets, 3 sizes, optional text, engine line and labels.
⚙️ Auto mode| Designed to capture frames and provide hints automatically on your turns.
🛡 Diagnostics| Includes capture recovery and diagnostic logging to help investigate problems.
🔒 Privacy-focused design| Review the app permissions and source code to verify the data-handling behavior of the particular build you install.

📥 Install in 60 seconds

1. Open the repository's "GitHub Actions page" (../../actions/workflows/build-apk.yml).
2. Select a successful Build APK workflow run.
3. Under Artifacts, download "ChessHintPanel-apk".
4. Extract the downloaded ZIP and locate the generated APK.
5. Transfer the APK to your phone, tap it, allow installs from that source when Android prompts, then install.
6. Open the app:
   * ALLOW "DISPLAY OVER OTHER APPS" → toggle ON
   * START PANEL → Android asks "Start recording or casting?" → YES
7. A small ♞ HINT ball should appear. Open your chess game and tap it → SHOW MY MOVE.

«Your pieces must be at the bottom of the board — if not, tap ♞ → ME: WHITE/BLACK or set My side in Settings.»

The available controls and behavior may vary by build. Test the installed version before relying on it.

🎛 Make it look how you want

<div align="center">
<img src="docs/screenshots/shot-1-arrow-rings.png" width="200">
<img src="docs/screenshots/shot-2-arrow-only.png" width="200">
<img src="docs/screenshots/shot-3-squares.png" width="200">
</div>Group| Options
Marks| style (arrow+rings / arrow / rings / squares) · colours (green-amber, blue-pink, cyan-orange, white-red, lime-violet) · size (S/M/L) · move text · engine line · labels · board frame · status pill
Play| my side · strength (MAX, 2850, 2400, 1800, 1400) · thinking time (0.5–5 s) · auto hints · vibrate
Board| fit frame · auto detect · fix pieces · new game · bubble size

Every change is designed to be applied to the running panel and previewed on a board inside Settings ("details" (docs/SETTINGS.md)).

🧩 How it works

MediaProjection ─► ScreenGrab ─► Vision ─► Track ─► Stockfish 19 (JNI) ─► Markers ─► Overlay
                                   │          │
                    finds the board   keeps the piece TYPES exact by matching every
                    and the 64 squares   screen change to a legal move

* Vision — the board is found from grid-line contrast and checkerboard alternation, then each square is compared with its own four corners, so highlighted squares can be distinguished from pieces. Recognition performance may vary by board theme and layout.
* Track — the reader is designed not to guess piece types. The tracker keeps a trusted position and reconciles whole-board occupancy against legal chess transitions (including castling, en passant, and promotions). Ambiguous changes should leave the last trusted FEN untouched and mark the position uncertain. Starting mid-game requires confirming the board in the existing editor/FEN path rather than silently guessing piece identities.
* Engine — Stockfish uses the app's Android-native JNI integration path. Runtime compatibility still requires a built APK and device verification. The engine is intended to analyze your side's move.

More: ""docs/ARCHITECTURE.md"" (docs/ARCHITECTURE.md)

🧪 Tested

bash tools/test-desktop.sh

# For the optional native UCI integration suite (network + C++ compiler required):
bash tools/build-stockfish-host.sh
RUN_STOCKFISH_INTEGRATION=1 bash tools/test-desktop.sh

* Chess core — 20/20 desktop checks were reported passing, including known perft totals (startpos depth 4 = 197,281; Kiwipete depth 3 = 97,862), exact FEN serialization, and rejection of inconsistent side-to-move/check state.
* Tracker — 14/14 regression tests were reported passing, including stationary identity retention, low-confidence frames, legal moves, captures, castling, en passant, promotions, orientation, check-giving moves, ambiguous transitions, and transactional rejection.
* Vision — 27/27 synthetic screenshot tests were reported passing across six board styles/locations and a no-board negative case. These images are synthetic, not screenshots captured from a physical phone.
* Native engine — Stockfish UCI integration tests are included but were not run in the original repair environment because the required source/network fetch and Android SDK/NDK were unavailable. See ""REPAIR_REPORT.md"" (REPAIR_REPORT.md) for exact limitations.

A successful GitHub Actions workflow confirms that the configured build completed; it does not by itself verify every feature on a physical Android device.

📦 All versions

The following APK files were included in the uploaded project package. They are archived APKs and should not automatically be considered builds of the latest repaired source.

Version| APK| What changed
2.2| "apk/ChessHintPanel-v2.2.apk" (apk/ChessHintPanel-v2.2.apk)| Archived APK included in the uploaded project package
2.1| "apk/ChessHintPanel-v2.1.apk" (apk/ChessHintPanel-v2.1.apk)| Archived APK included in the uploaded project package
1.8| "apk/ChessHintPanel-v1.8.apk" (apk/ChessHintPanel-v1.8.apk)| Historical release
1.7| "apk/ChessHintPanel-v1.7.apk" (apk/ChessHintPanel-v1.7.apk)| Historical release
1.6| "apk/ChessHintPanel-v1.6.apk" (apk/ChessHintPanel-v1.6.apk)| Historical release
1.5| "apk/ChessHintPanel-v1.5.apk" (apk/ChessHintPanel-v1.5.apk)| Historical release
1.4| "apk/ChessHintPanel-v1.4.apk" (apk/ChessHintPanel-v1.4.apk)| Historical release
1.3| "apk/ChessHintPanel-v1.3.apk" (apk/ChessHintPanel-v1.3.apk)| Historical release
1.2| "apk/ChessHintPanel-v1.2.apk" (apk/ChessHintPanel-v1.2.apk)| Historical release
1.1| "apk/ChessHintPanel-v1.1.apk" (apk/ChessHintPanel-v1.1.apk)| Historical release
1.0| "apk/ChessHintPanel-v1.0.apk" (apk/ChessHintPanel-v1.0.apk)| First archived release

Latest GitHub Actions build

The latest successful workflow produces an artifact named "ChessHintPanel-apk".

1. Open "GitHub Actions" (../../actions).
2. Select the successful Build APK run.
3. Download "ChessHintPanel-apk" under Artifacts.
4. Extract the ZIP to locate the generated APK.

The Actions artifact is separate from the archived APKs listed above. Verify the APK's actual version from its build configuration before assigning it a release number.

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
