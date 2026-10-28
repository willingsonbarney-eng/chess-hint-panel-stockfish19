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
│                        ├── Track        legal-move search that keeps types exact
│                        └── UciEngine    JNI → libstockfish.so (Stockfish 19 build target)
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

3. **Track.** Piece identities are carried forward from the last trusted position; a new
   silhouette classification never overwrites a stationary piece by itself. The tracker searches
   legal whole-board transitions, checks occupancy plus optional confident type evidence, and
   handles captures, castling, en passant, and promotion. A single low-confidence square may be
   ignored/corrected only when the full position has a unique legal explanation. Search-budget
   exhaustion, multiple valid explanations, and impossible transitions are rejected transactionally:
   the trusted FEN and move history stay unchanged and analysis is paused. Joining an arbitrary
   mid-game position requires explicit confirmation in the existing board editor/FEN path, because
   this heuristic silhouette classifier cannot reliably infer all piece identities and side-to-move
   from one screenshot alone.

## Engine

* `tools/build-stockfish-android.sh` fetches the official **Stockfish 19 (`sf_19`)** source and
  the upstream hash-validated NNUE network, then compiles `libstockfish.so` for `arm64-v8a`,
  `armeabi-v7a`, and `x86_64`. The NNUE network is embedded in the library at build time.
* `native/jni_bridge.cpp` wires Stockfish 19's `Stockfish::UCIEngine` API to custom stream buffers:
  Java pushes commands in, engine lines come back through a JNI callback. Because it is a normal
  shared library loaded with `System.loadLibrary()`, no `exec()` is needed.
* The app always asks for **the player's side to move** (the FEN is written with that side to
  move), so the hint is never the opponent's idea. Strength can be limited with Stockfish's
  own `UCI_LimitStrength` / `UCI_Elo` options.

## Failure handling

* `CrashGuard` installs a default uncaught-exception handler, guards posted UI work and every
  canvas draw, and writes a short log (with device + Android version) that the Diagnostics
  card shows and copies.
* Screen-capture problems never kill the panel: it reports the real reason, keeps the bubble
  alive and offers *Retry screen reading*.
* Memory: engine hash 16 MB, screenshots processed at ≤640 px on the board, decoded bitmaps
  recycled, `largeHeap` enabled as a safety net.

## Build without Gradle

`tools/build-apk.sh` runs `aapt2 compile/link` → `javac` (against `android.jar`) → `d8`, and
`tools/package-apk.sh` adds the `.dex` + native libs, `zipalign`s and signs with
`apksigner`. The CI workflow uses Android NDK r27c or newer for the Stockfish 19 Android build.
