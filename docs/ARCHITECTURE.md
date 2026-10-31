# Architecture

```
┌───────────────────────────── app process ─────────────────────────────┐
│                                                                       │
│  MainActivity ──► OverlayService ──► WindowManager (TYPE_APPLICATION_OVERLAY)
│  SettingsActivity      │              ├── OverlayView   (hints, arrows, chip)
│                        │              ├── BubbleView    (draggable ♞)
│                        │              ├── PanelView     (tile menu)
│                        │              ├── CalibrationView / BoardEditorView
│                        │              └── MarkerPreviewView (Settings only)
│                        │
│                        ├── ScreenGrab   MediaProjection → VirtualDisplay → ImageReader
│                        ├── Vision       board detection + square classification
│                        ├── Track        the ONE authoritative position; accepts only legal moves
│                        └── UciEngine    JNI → libstockfish.so (Stockfish 19)
└───────────────────────────────────────────────────────────────────────┘
```

## Screen → position

1. **Detect.** A coarse search on a downscaled copy scores candidate board rectangles by
   (a) grid-line gradient contrast at the 7 internal lines and the perimeter and
   (b) colour alternation (neighbour squares differ, diagonal squares match). The best few
   candidates are re-aligned at ~600 px with a hill-climb over x, y and pitch.

2. **Classify.** For every square the reader compares the middle region with the square's
   **own four corner patches**, so it is independent of the board theme. Features:
   *ink fraction* (share of pixels that differ from those corners), *eroded ink* (the piece
   **body** — an outline ring or a move dot disappears under a 2–4 px erosion) and *maximum
   colour contrast*. A square counts as occupied when the eroded body survives and there is a
   strongly contrasting pixel, which is exactly what separates a piece from a highlight.
   Piece **colour** is the mean luminance of the surviving body.

3. **Confidence.** Every square also gets a confidence (`Vision.computeConfidence`): how far its evidence is from the
   decision threshold compared with the other squares of the same picture. Highlights, arrows, animations and noise
   produce *low* confidence, never a confidently wrong square (checked by `VisionTest` with degraded pictures).

4. **Track** (`Track.java`). The reader deliberately does **not** guess piece *types*. The tracker owns the real
   position; it starts from the standard position (or, when you join half way / the game was lost, a position built by
   `AutoSeed`, see below) and changes it only by **legal moves**:
   * a frame must be seen `confirmFrames` times in a row (debounce); low-confidence squares are wildcards;
   * the shortest legal move sequence (up to 4 plies, 6 in recovery) that explains the certain squares is searched,
     including castling, en passant, promotion and under-promotion;
   * if more than one explanation fits equally well the result is `AMBIGUOUS` and the position is **not** changed;
   * if nothing fits: `UNEXPLAINED`, then `LOST` - hints pause and `AutoSeed` re-reads the board (below);
   * orientation (which side is at the bottom) is part of the state, flips only on a whole-board match and only
     affects coordinates;
   * take-backs and new games are recognised from snapshots; every change bumps a `version` counter.
   The status values are UNCHANGED, PENDING, MOVED, REWOUND, NEW_GAME, ORIENTATION, AMBIGUOUS, UNEXPLAINED, LOST, INVALID.
   Details and limits: `docs/ROOT-CAUSE.md`.

5. **Hint.** `OverlayService` takes one snapshot of the tracker (board, side to move, FEN, history, version),
   asks the engine for exactly that FEN, and shows the answer only if the FEN is still the latest trusted one.

## Engine

* `tools/build-stockfish-android.sh` fetches the official **Stockfish 19** (`sf_19`, commit verified) and compiles it
  with the NDK, NNUE network embedded, into `libstockfish.so` for `arm64-v8a` (see `docs/ENGINE.md`).
* `native/jni_bridge.cpp` wires Stockfish's UCI loop to two custom stream buffers:
  Java pushes commands in, engine lines come back through a JNI callback; start / shutdown / restart are supported.
  Because it is a normal shared library loaded with `System.loadLibrary()`, no `exec()` is needed.
* `UciEngine.java`: `uciok`/`readyok` handshake, validated FEN, one search at a time, results tagged with the analysed FEN,
  cancel / restart / shutdown, strength limit through `UCI_LimitStrength` / `UCI_Elo`.
* The app asks for **the player's side to move**, so the hint is never the opponent's idea.

## Failure handling

* `CrashGuard` installs a default uncaught-exception handler, guards posted UI work and every
  canvas draw, and writes a short log (with device + Android version) that the Diagnostics
  card shows and copies.
* Screen-capture problems never kill the panel: it reports the real reason, keeps the bubble
  alive and offers *Retry screen reading*.
* Memory: engine hash 16-64 MB (by heap size), screenshots processed at ≤640 px on the board, decoded bitmaps
  recycled, `largeHeap` enabled as a safety net.

## Build without Gradle

`tools/build-apk.sh` runs `aapt2 compile/link` → `javac` (against `android.jar`) → `d8`, and
`tools/package-apk.sh` adds the `.dex` + native libs, `zipalign`s and signs with
`apksigner`. The CI workflow does the same on every push.

## Automatic position (replaces the manual FIX PIECES editor)
The manual editor was removed in v2.3. The position is now live and automatic:

1. **Start position (exact).** A standard start position, or a few plies after it, is recognised from the colour pattern alone.
   At that moment `PieceTemplates` also learns what *this app's* pieces look like (all twelve piece types are on the board),
   and keeps learning from positions the tracker follows. The templates are saved in the app's settings.
2. **Following the game (exact).** From then on every change is accepted only as a legal move (`Track`).
3. **Joining half way, or a lost game (`AutoSeed`).** When there is no trusted position, or the tracker reports `LOST`:
   * pieces the tracker still knows stay exactly what they were while the square shows the same colour;
   * other pieces are read with the learned templates - but only if the templates first read the *known* pieces correctly;
   * anything left is an **estimate** from the piece silhouette (`Vision.guessBoard`, the weakest source). An estimate
     is shown as "(estimate)" in Diagnostics, is never used to learn templates, and is replaced as soon as the game contradicts it
     (the tracker then reports `LOST` and the board is read again);
   * the picture must be clear on all 64 squares and the result must be a legal position, otherwise nothing is built and it tries again on the next frame.
4. A trusted position that is being followed is **never** overwritten by this reader.

Limits (honest): the very first half-way join in a new app, with no templates yet, can only be an estimate - the silhouette
reader is not reliable (on the synthetic test boards it got 27 of 32 pieces right mid-game, and an endgame wrong). Starting the
panel at the beginning of any game once lets it learn the pieces, after which a half-way join is read with the templates.
FIT BOARD, FLIP (ME: WHITE/BLACK) and NEW GAME stay available.

