# Install & first run - Realme 11 4G (Android 15)

The APK is for **ARM64** phones (the Realme 11 4G is one). It is about 100 MB because the chess engine
(Stockfish 19 with its evaluation network) is inside. It needs no internet and asks for no network permission.

## 1. Get the APK
Download `ChessHintPanel-v2.3.apk` from the GitHub release / the CI artifact (see `docs/BUILDING.md`
if you have to build it yourself). Check that the file size is roughly 100 MB.

## 2. Install
1. Copy the APK to the phone and tap it (Files / Downloads).
2. Android asks to allow installs from this source (for the app you opened it with, e.g. Files or Chrome):
   **Settings → Allow from this source**, go back, tap **Install**.
3. If Play Protect says the app is unknown: **More details → Install anyway** (the APK is signed with the project's own key, not by Google Play).
4. If you had an older version signed with a different key, uninstall it first (Android refuses to update across keys).

## 3. Give the two permissions
| Step | What to tap | What it is for |
|---|---|---|
| 1 | **ALLOW "DISPLAY OVER OTHER APPS"** → switch the toggle **ON** | lets the panel float above your chess app |
| 2 | **START PANEL** → *"Start recording or casting?"* (Android 15 may say *"Share your screen"*) → **Start now / Entire screen** | lets the panel see the board. It is Android's standard screen-capture API; nothing leaves the phone |
| 3 | If asked: allow **notifications** | the capture service needs its small status notification |

A small **♞ HINT** button appears - drag it wherever you like.

### Realme UI (ColorOS-based) settings that keep the panel alive
* Settings → Apps → App management → **Chess Hint Panel** → *Battery usage* → **Allow background activity** / *Unrestricted*.
* Same page → enable **Auto-launch** if offered.
* Lock the app in the recent-apps list (long-press its card → lock) so the system cleaner does not kill it.

## 4. First hint
1. Open your chess game. **Your pieces must be at the bottom** (use ♞ → ME: WHITE/BLACK if not, or set *My side* in Settings).
2. Tap **♞ → SHOW MY MOVE**.
3. The first time, if the panel cannot find the board automatically: tap **FIT BOARD**, drag the frame exactly onto the board, **SAVE**.
4. Joining a game in the middle works automatically: the panel reads the position from the screen (best if you started the panel at
   the start of any game once before, so it learned your app's pieces). If it is wrong, the next moves will not fit and it re-reads the board.
5. From then on the panel follows the game by itself - turn **AUTO** on for a hint on every one of your turns.

## How it behaves now (v2.3)
* The panel never "re-reads" the pieces from pictures. It follows the game move by move and only accepts a change that is a **legal move**.
* If it is not sure (animation, a highlight, a missed move) it shows a *waiting / not sure* state instead of guessing.
  If it lost the game it says so and reads the board again by itself (**NEW GAME** at the start of a game also works).

## Troubleshooting

| Symptom | Fix |
|---|---|
| *"engine: not running"* in Diagnostics | Reinstall; send the Diagnostics text (App → **DIAGNOSTICS → Copy**) with the phone model. |
| *"Screen reading failed"* | Tap **Retry screen reading** in the app. Check the battery settings above. |
| *"No chess board on screen"* | The board is not visible or drawn at an unusual size - tap **FIT BOARD** once. |
| Arrow on the wrong squares | Board frame drifted → **FIT BOARD**; or the orientation is wrong → ♞ → ME: WHITE/BLACK. |
| *"Board not sure / out of sync"* | A move was missed while the screen was off → it re-reads the board by itself; **NEW GAME** at the start of a game. |
| Nothing appears over the chess app | The overlay permission is off (Step 1). |
| Panel stops by itself | Battery → **Unrestricted** (see above). |
| Hints too slow | Settings → *Thinking time* → 0.5 s. |
| Anything else | App → **DIAGNOSTICS → Copy** and open an issue with that text. |
