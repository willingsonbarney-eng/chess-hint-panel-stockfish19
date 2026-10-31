package com.chesshint.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * Learns what THIS chess app's pieces look like, from positions the tracker already trusts
 * (the standard start position shows all six piece types of both colours, and every position
 * that is followed move by move adds more samples).  It is used only to READ piece types when
 * the app has no history to rely on (the game was joined half way, or the tracker lost the game).
 *
 * It never changes a trusted position: it only proposes the pieces for a position that does not
 * exist yet, and it says how sure it is.  Unsure -> it reports nothing instead of guessing.
 */
public final class PieceTemplates {

    private static final int FEATURES = 11;
    private static final int MAX_PER_PIECE = 12;
    /** a piece type needs this many samples (per colour) before templates are used */
    private static final int MIN_SAMPLES = 1;
    /** the best piece must be this much closer than the best DIFFERENT piece (ratio of distances) */
    private static final float MARGIN = 1.35f;
    /** and not be farther than this from its best sample (a stranger is not a piece we know) */
    private static final float MAX_DIST = 0.35f;

    private final List<float[]>[] samples;       // index = piece code 1..14
    private int version;

    @SuppressWarnings("unchecked")
    public PieceTemplates() {
        samples = new List[15];
        for (int i = 0; i < 15; i++) samples[i] = new ArrayList<>();
    }

    public synchronized int version() { return version; }

    public synchronized void clear() {
        for (List<float[]> l : samples) l.clear();
        version++;
    }

    /** features that do not depend on the square's colour or the board size */
    static float[] features(Vision.Result res, int idx) {
        float[] f = new float[FEATURES];
        float[] raw = res.bands[idx];
        float max = 1e-4f;
        for (int b = 0; b < 8; b++) if (raw[b] > max) max = raw[b];
        f[0] = res.pieceHeight[idx] * 3f;
        f[1] = res.asym[idx] * 4f;
        f[2] = res.inkFrac[idx] * 2f;
        for (int b = 0; b < 8; b++) f[3 + b] = raw[b] / max;
        return f;
    }

    private static float dist(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < FEATURES; i++) { float d = a[i] - b[i]; s += d * d; }
        return (float) Math.sqrt(s / FEATURES);
    }

    /**
     * Learn from a picture whose position is known: every occupied square whose colour agrees with
     * the position contributes one sample of that piece.  Nothing is learned when the two disagree anywhere.
     */
    public synchronized boolean learn(Vision.Result res, Chess.Pos pos, boolean whiteBottom) {
        if (res == null || !res.ok || pos == null) return false;
        Board b = Chess.toBoard(pos, whiteBottom);
        for (int i = 0; i < 64; i++) {                       // all-or-nothing: the picture must match the position
            int p = b.s[i];
            int c = res.colorPat[i];
            if (p == 0 ? c != 0 : (Board.isWhite(p) ? c != 1 : c != 2)) return false;
        }
        boolean changed = false;
        for (int i = 0; i < 64; i++) {
            int p = b.s[i];
            if (p == 0 || res.conf[i] < Track.CERTAIN_DEFAULT) continue;
            List<float[]> l = samples[p];
            float[] f = features(res, i);
            boolean dup = false;
            for (float[] o : l) if (dist(o, f) < 0.02f) { dup = true; break; }
            if (dup) continue;
            if (l.size() >= MAX_PER_PIECE) l.remove(0);
            l.add(f);
            changed = true;
        }
        if (changed) version++;
        return changed;
    }

    /** true when every piece type of both colours has been seen (so the whole set can be read) */
    public synchronized boolean ready() {
        for (int p = 1; p <= 14; p++) {
            if (p == 7 || p == 8) continue;
            if (samples[p].size() < MIN_SAMPLES) return false;
        }
        return true;
    }

    /**
     * @return the piece code at square idx (colour taken from the colour pattern), or 0 when the templates
     *         are not sure.  `out` (length 2, optional) receives {best distance, margin ratio}.
     */
    public synchronized int classify(Vision.Result res, int idx, float[] out) {
        int col = res.colorPat[idx];
        if (col == 0) return 0;
        int lo = col == 1 ? 1 : 9, hi = col == 1 ? 6 : 14;
        float[] f = features(res, idx);
        float best = Float.MAX_VALUE, second = Float.MAX_VALUE;
        int bestP = 0;
        for (int p = lo; p <= hi; p++) {
            float d = Float.MAX_VALUE;
            for (float[] s : samples[p]) d = Math.min(d, dist(s, f));
            if (d < best) { second = best; best = d; bestP = p; }
            else if (d < second) second = d;
        }
        if (out != null && out.length >= 2) { out[0] = best; out[1] = second / Math.max(best, 1e-4f); }
        if (bestP == 0 || best > MAX_DIST) return 0;
        if (second < Float.MAX_VALUE && second < best * MARGIN) return 0;
        return bestP;
    }

    /** compact text form (kept in the app's settings so the pieces are known the next time the panel starts) */
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder("v1");
        for (int p = 1; p <= 14; p++) {
            for (float[] f : samples[p]) {
                sb.append(';').append(p);
                for (float v : f) sb.append(',').append(Math.round(v * 10000f) / 10000f);
            }
        }
        return sb.toString();
    }

    /** @return false (and nothing loaded) when the text is not something serialize() wrote */
    public synchronized boolean load(String text) {
        if (text == null || !text.startsWith("v1")) return false;
        List<float[]>[] tmp = newLists();
        try {
            String[] parts = text.split(";");
            for (int k = 1; k < parts.length; k++) {
                String[] t = parts[k].split(",");
                if (t.length != FEATURES + 1) return false;
                int p = Integer.parseInt(t[0]);
                if (p < 1 || p > 14 || p == 7 || p == 8) return false;
                float[] f = new float[FEATURES];
                for (int i = 0; i < FEATURES; i++) {
                    f[i] = Float.parseFloat(t[i + 1]);
                    if (Float.isNaN(f[i]) || Float.isInfinite(f[i])) return false;
                }
                if (tmp[p].size() < MAX_PER_PIECE) tmp[p].add(f);
            }
        } catch (RuntimeException e) {
            return false;
        }
        for (int p = 0; p < 15; p++) { samples[p].clear(); samples[p].addAll(tmp[p]); }
        version++;
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<float[]>[] newLists() {
        List<float[]>[] l = new List[15];
        for (int i = 0; i < 15; i++) l[i] = new ArrayList<>();
        return l;
    }

    /**
     * Reads a whole board from the picture using the learned templates.
     *
     * @return the board, or null when any piece could not be read with confidence
     */
    public Board read(Vision.Result res) {
        if (res == null || !res.ok || !ready()) return null;
        Board out = new Board();
        for (int i = 0; i < 64; i++) {
            if (res.colorPat[i] == 0) continue;
            if (res.conf[i] < Track.CERTAIN_DEFAULT) return null;
            int p = classify(res, i, null);
            if (p == 0) return null;
            out.s[i] = (byte) p;
        }
        return out;
    }
}
