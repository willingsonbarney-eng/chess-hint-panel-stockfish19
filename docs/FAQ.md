# FAQ

**Does it work with any chess app?**
Yes. It never talks to the chess app; it reads the screen with Android's own screen-capture
API and finds the board like a human eye would. Any theme, any app, online or offline.

**Will it tell me the opponent's move?**
No. The bottom half of the board is treated as *you*; the engine is asked only for your
side's move. While it is the enemy's turn the panel shows "Waiting for the enemy…".

**Is there a FIX PIECES button?**
No - it was removed. The screen reader can see *that* a square has a piece and *which colour* it is, but not reliably
whether it is a bishop or a pawn. So the panel follows every move exactly (a change is accepted only if it is a legal
move) and, if it loses the game or you join in the middle, it **reads the board again by itself**: it keeps the pieces it
is still sure of, reads the others with the shapes it learned from this app's pieces at the start of earlier games, and
only as a last resort estimates from the silhouette (shown as "estimate" in Diagnostics). For the best result start the
panel at the beginning of a game once so it can learn your app's pieces. If an estimate is wrong the next moves do not
fit it and the board is read again. **NEW GAME** and **FIT BOARD** are still there.

**Is 8000 Elo possible?**
No engine on earth reaches 8000. The strongest measured engines are ~3600 and Stockfish at
full strength is exactly that. The panel gives you MAX (superhuman) and lets you *weaken* it
if you want a fair game.

**Does it need internet?**
No. The app has no internet permission at all. The engine (Stockfish 19) is inside the APK.

**Battery?**
Auto mode captures a frame once a second and only thinks when it is your turn. Turn Auto off
(or STOP the panel) when you do not need it.

**Is it allowed by the chess sites?**
Using engine assistance during online games breaks the rules of every chess platform and can
get your account banned. This tool is meant for **analysis, learning, and offline games** —
use it responsibly.

**Where is the crash log?**
App → DIAGNOSTICS → *Copy*.

**Which engine is it, and what licence?**
Official Stockfish 19 (GPLv3), see `docs/ENGINE.md`. The licence text and a written source offer are inside the APK
and in `docs/SOURCE-OFFER.txt`.
