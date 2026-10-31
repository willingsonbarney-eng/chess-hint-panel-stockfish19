package com.chesshint.panel;

/**
 * Builds a position from the screen when the app has none it can trust: the game was joined half
 * way, or the tracker lost it.  This replaces the old manual "FIX PIECES" step.
 *
 * What it may do, in this order of reliability:
 *   1. pieces the tracker already knows stay exactly what they were while the square still shows the same colour;
 *   2. other pieces are read with the templates learned from this app's own pieces ({@link PieceTemplates}),
 *      but only after the templates have proved themselves on the known pieces;
 *   3. whatever is left is an ESTIMATE from the piece silhouettes ({@link Vision#guessBoard}), which is the
 *      weakest source - the result says so, and the caller must treat it as provisional.
 *
 * The result is a proposal for a position that does not exist yet; it NEVER overwrites a trusted position
 * that is still being followed (the caller only asks when there is none, or when the game was lost).
 */
public final class AutoSeed {
    private AutoSeed() { }

    public static final int SRC_TRUSTED = 0;    // every piece kept from the tracked position
    public static final int SRC_LEARNED = 1;    // pieces read with templates learned from this app
    public static final int SRC_ESTIMATED = 2;  // at least one piece was guessed from its silhouette

    public static final class Result {
        public Chess.Pos pos;                   // null when nothing could be built
        public int source;
        public String problem = "";
        public int kept, learned, guessed;
        public boolean ok() { return pos != null; }
    }

    public static Result seed(Vision.Result res, boolean whiteBottom, Track old, PieceTemplates tpl) {
        Result out = new Result();
        if (res == null || !res.ok) { out.problem = "no clear picture"; return out; }
        // the colour picture itself must be certain everywhere - the pieces are only as good as it is
        for (int i = 0; i < 64; i++) {
            if (res.conf[i] < Track.CERTAIN_DEFAULT) { out.problem = "picture not clear yet"; return out; }
        }
        Board trusted = old != null ? Chess.toBoard(old.pos, whiteBottom) : null;

        // are the templates good enough for THIS picture?  they have to read the known pieces right first
        boolean tplOk = tpl != null && tpl.ready();
        if (tplOk && trusted != null) {
            int same = 0, right = 0;
            for (int i = 0; i < 64; i++) {
                int c = res.colorPat[i], tp = trusted.s[i];
                if (c == 0 || tp == 0 || (c == 1) != Board.isWhite(tp)) continue;
                same++;
                if (tpl.classify(res, i, null) == tp) right++;
            }
            if (same >= 6 && right * 10 < same * 9) tplOk = false;     // other theme / other pieces: do not use them
        }

        Board guess = null;
        Board b = new Board();
        for (int i = 0; i < 64; i++) {
            int c = res.colorPat[i];
            if (c == 0) continue;
            int tp = trusted == null ? 0 : trusted.s[i];
            if (tp != 0 && (c == 1) == Board.isWhite(tp)) { b.s[i] = (byte) tp; out.kept++; continue; }
            int lp = tplOk ? tpl.classify(res, i, null) : 0;
            if (lp != 0) { b.s[i] = (byte) lp; out.learned++; continue; }
            if (guess == null) guess = Vision.guessBoard(res, whiteBottom);
            int gp = guess.s[i];
            if (gp == 0 || (c == 1) != Board.isWhite(gp)) { out.problem = "cannot read all pieces"; return out; }
            b.s[i] = (byte) gp;
            out.guessed++;
        }
        out.source = out.guessed > 0 ? SRC_ESTIMATED : (out.learned > 0 ? SRC_LEARNED : SRC_TRUSTED);

        Chess.Pos p = Chess.fromBoard(b, whiteBottom, Chess.WHITE);
        // castling rights are unknown without the history: keep only what the old position still allows
        p.castling = trusted != null ? Chess.feasibleCastling(p.cb, old.pos.castling) : "-";
        String err = Chess.validate(p);
        if (!err.isEmpty()) {                         // the tempo is unknown: either side may be to move
            p.side = Chess.BLACK;
            String err2 = Chess.validate(p);
            if (!err2.isEmpty()) { out.problem = "not a legal position (" + err + ")"; return out; }
        }
        out.pos = p;
        return out;
    }
}
