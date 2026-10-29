# The "piece identities change by themselves" bug: root cause and fix

## Symptom
Without any piece moving on the real board, the panel's idea of the position changed: a knight became a bishop,
a pawn became a queen, a square emptied, the hint belonged to a different piece, the side to move flipped.

## Root cause (not one line, but one design error)
The screen reader can reliably see only **"is there a piece on this square, and is it light or dark"**.
It can *not* reliably tell a bishop from a pawn from a screenshot, and it was never meant to. The old code
nevertheless let that unreliable shape classification **rewrite the position**:

1. `Vision.guessType` / `guessBoard` produced piece *types* from silhouettes, and when the silhouette result
   disagreed with the tracked position, `track.reset(guess)` replaced the tracked pieces by the guess.
2. `Chess.sanitize` silently "repaired" impossible positions by changing pieces (e.g. removing/adding kings, queens).
3. `isVisuallyValidMove` **vetoed legal moves** when the silhouette of the moved piece did not match - the veto
   triggered the reset in (1).
4. `Vision.detectWhiteBottom` was re-run during the game; a few mis-coloured squares flipped the orientation
   and set `track = null`, throwing the whole position away.
5. The hint code forced `usePos.side = userSide`, so a wrong side-to-move guess produced a hint for the
   wrong colour / a FEN that did not match the board.
6. The per-square confidence made *empty* squares look uncertain and *highlighted* squares look certain, so
   the thresholds were useless: every frame got through.
7. The engine wrapper (`stop` + `isready` + `best.clear()`) had a race: a late `bestmove` of an old search could be
   read as the answer for a newer position.

Because every one of these paths ran on **every captured frame**, frame noise (a highlight, an animation, an arrow,
anti-aliasing, a one-frame misread) became a change in the position.

## Fix (what the code does now)
* **The position is only ever changed by a legal move or by the user.** `Track` keeps the one authoritative position.
  A frame is reduced to "colour pattern + per-square confidence"; piece *types* are never read from pixels.
* **Debounce:** a change must be seen in `confirmFrames` consecutive frames before it is considered.
* **Confidence:** squares below the certainty level are *wildcards* (they cannot support or contradict a decision);
  a frame with too many uncertain squares is ignored (`PENDING`), never forced.
* **Legal-move reconciliation:** the shortest legal move sequence (1..4 plies; up to 6 in recovery) that explains the
  observed certain squares is searched; castling, en passant, promotion (with under-promotion search) are covered.
  If more than one explanation is equally good, the result is `AMBIGUOUS` and **nothing changes** (no forced guess).
  If nothing explains it: `UNEXPLAINED`, and after a few frames `LOST` - the panel stops hinting and offers FIX PIECES.
* **Orientation** is part of the tracked state; a flip is accepted only when the *whole* board matches the mirrored
  pattern, and it is applied to the display coordinates only, never to the pieces.
* **Whole-board validation** (`Chess.validate`): kings, pawn ranks, piece counts, side-not-to-move not in check,
  castling rights feasible - invalid positions are rejected with a reason, never silently repaired.
* **Take-backs, new game** are recognised from the snapshots/start position; **FIX PIECES** is the safe recovery path.
* **Synchronised state:** board, side to move, FEN, move history and the analysis all come from one `Track` snapshot
  with a `version` counter; the engine result is applied only if its FEN equals the current trusted FEN.
* **Engine:** results are tagged with the analysed FEN; cancel/restart/shutdown can no longer leak an old `bestmove`.
* **Vision confidence** was rewritten (`computeConfidence`): a square is "sure" only if it is clearly far from the
  decision threshold relative to the other squares of the same board; a piece washed out by a highlight is flagged
  as unsure instead of "empty".

## Limits that remain (honest)
Colour-only observation cannot distinguish some things; the tracker reports them as `AMBIGUOUS` instead of guessing:
* castling versus a king walk + rook move that produce the same colours;
* look-alike identity swaps after 4 or more unobserved plies (e.g. the app was in the background);
* the side to move across a gap of several unobserved plies (`sideKnown=false` until the next observed move);
* gaps of 3+ plies may report `AMBIGUOUS` / `LOST` and then need the user's **FIX PIECES** once.
