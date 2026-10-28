package com.chesshint.panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maintains the last trusted chess position from colour/occupancy observations.
 *
 * The screen reader deliberately does not assign a fresh piece type to each square on
 * every frame. Piece identities are carried forward by the legal-move model. A frame is
 * committed only when its board-wide occupancy pattern has one unambiguous legal
 * explanation from the last trusted position, or when a single low-confidence square is
 * consistent with the existing position. An unexplained/ambiguous frame never mutates the
 * position or move history.
 */
public class Track {

    private static final float LOW_CONFIDENCE = 0.28f;
    private static final int MAX_SEARCH_PLIES = 4;
    private static final int SEARCH_BUDGET = 200000;

    public Chess.Pos pos;
    public boolean whiteBottom;
    public int[] lastPattern = new int[64];
    public String lastInfo = "";
    public List<Chess.Move> history = new ArrayList<>();

    public Track(Chess.Pos pos, boolean whiteBottom) {
        if (pos == null) throw new IllegalArgumentException("position must not be null");
        this.pos = pos.copy();
        this.whiteBottom = whiteBottom;
        this.lastPattern = Chess.pattern(this.pos, whiteBottom);
    }

    public void reset(Chess.Pos p) {
        if (p == null) throw new IllegalArgumentException("position must not be null");
        pos = p.copy();
        history.clear();
        lastPattern = Chess.pattern(pos, whiteBottom);
        lastInfo = "";
    }

    public void setWhiteBottom(boolean wb) {
        whiteBottom = wb;
        lastPattern = Chess.pattern(pos, whiteBottom);
    }

    public static class Update {
        public boolean changed;       // a legal move sequence changed the trusted position
        public boolean ok;            // this frame was explained or safely corrected
        public boolean ambiguous;     // more than one viable legal explanation existed
        public List<Chess.Move> moves = new ArrayList<>();
        public int fixedSquares;
        public String info = "";
        /** Board pattern actually reconciled against the trusted position. */
        public int[] matchedPattern;
    }

    private static final class Match {
        final Chess.Pos result;
        final List<Chess.Move> moves;
        final int[] target;
        final int fixedSquares;

        Match(Chess.Pos result, List<Chess.Move> moves, int[] target, int fixedSquares) {
            this.result = result;
            this.moves = moves;
            this.target = target.clone();
            this.fixedSquares = fixedSquares;
        }
    }

    /** Feed one 64-square occupancy observation (0 empty, 1 white, 2 black). */
    public Update observe(int[] observed, float[] conf) {
        return observe(observed, conf, null, null, 0);
    }

    /**
     * Feed occupancy plus optional per-square piece identities and heuristic type confidence.
     * Type evidence is used only to disambiguate otherwise-legal whole-board transitions, such
     * as which promotion piece appeared on the last rank. It never independently rewrites a
     * stationary piece.
     */
    public Update observe(int[] observed, float[] conf, int[] pieceCodes, float[] pieceTypeConfidence) {
        return observe(observed, conf, pieceCodes, pieceTypeConfidence, 0);
    }

    /**
     * Like observe(...), but when expectedSide is WHITE or BLACK the candidate must arrive at
     * that side-to-move through actual legal plies. It is a constraint, never a request to flip
     * the turn metadata after the fact. Pass 0 when the caller has no reliable turn evidence.
     */
    public Update observe(int[] observed, float[] conf, int[] pieceCodes, float[] pieceTypeConfidence,
                          int expectedSide) {
        Update u = new Update();
        if (expectedSide != 0 && expectedSide != Chess.WHITE && expectedSide != Chess.BLACK) {
            u.info = "invalid expected side";
            lastInfo = "UNCERTAIN: invalid expected side";
            return u;
        }
        lastSearchAmbiguous = false;
        lastSearchIncomplete = false;
        lastSearchTurnMismatch = false;
        if (observed == null || observed.length != 64 || conf == null || conf.length != 64
                || ((pieceCodes == null) != (pieceTypeConfidence == null))
                || (pieceCodes != null && (pieceCodes.length != 64 || pieceTypeConfidence.length != 64))) {
            u.info = "invalid observation";
            lastInfo = "UNCERTAIN: invalid observation";
            return u;
        }
        for (int v : observed) {
            if (v < 0 || v > 2) {
                u.info = "invalid square value";
                lastInfo = "UNCERTAIN: invalid square value";
                return u;
            }
        }

        int[] current = Chess.pattern(pos, whiteBottom);
        u.matchedPattern = current.clone();
        if (Arrays.equals(current, observed)) {
            if (expectedSide != 0 && pos.side != expectedSide) {
                u.ambiguous = true;
                u.info = "unchanged board conflicts with expected side to move";
                lastInfo = "UNCERTAIN: " + u.info;
                return u;
            }
            u.ok = true;
            u.changed = false;
            u.info = "unchanged";
            lastPattern = current;
            lastInfo = "";
            return u;
        }
        u.changed = true;

        // A one-square disagreement with very low confidence is most often a transient
        // occupancy read error. Keep all 64 identities and the move history unchanged.
        int initialDiff = countDiff(current, observed);
        if (initialDiff == 1) {
            int diffAt = firstDiff(current, observed);
            if (diffAt >= 0 && saneConfidence(conf[diffAt]) < LOW_CONFIDENCE) {
                if (expectedSide != 0 && pos.side != expectedSide) {
                    u.ambiguous = true;
                    u.info = "low-confidence frame conflicts with expected side to move";
                    lastInfo = "UNCERTAIN: " + u.info;
                    return u;
                }
                u.ok = true;
                u.changed = false;
                u.fixedSquares = 1;
                u.matchedPattern = current.clone();
                u.info = "ignored one low-confidence square";
                lastPattern = current;
                lastInfo = u.info;
                return u;
            }
        }

        // First accept an exact, uniquely explained legal transition using the tracked side.
        Match exact = findMatch(pos, observed, 0, expectedSide, pieceCodes, pieceTypeConfidence);
        if (exact != null) return commit(u, exact);
        if (lastSearchTurnMismatch) {
            u.ambiguous = true;
            u.info = "legal transition conflicts with expected side to move";
            lastInfo = "UNCERTAIN: " + u.info;
            return u;
        }
        if (lastSearchAmbiguous) {
            u.ambiguous = true;
            u.info = "ambiguous legal transition";
            lastInfo = "UNCERTAIN: " + u.info;
            return u;
        }

        // Correct at most one low-confidence square, only when the correction plus the rest
        // of the whole-board position has a unique legal explanation. Try the least-confident
        // candidates, not arbitrary squares. Do not commit the first of several explanations.
        Integer[] order = new Integer[64];
        for (int i = 0; i < 64; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(saneConfidence(conf[a]), saneConfidence(conf[b])));
        List<Match> alternatives = new ArrayList<>();
        int tried = 0;
        for (int k = 0; k < 6 && k < 64 && tried < 6; k++) {
            int square = order[k];
            float c = saneConfidence(conf[square]);
            if (c >= LOW_CONFIDENCE) break;
            for (int value = 0; value <= 2 && tried < 6; value++) {
                if (value == observed[square]) continue;
                tried++;
                int[] corrected = set(observed, square, value);
                if (Arrays.equals(current, corrected)) {
                    if (expectedSide != 0 && pos.side != expectedSide) {
                        u.ambiguous = true;
                        u.info = "low-confidence correction conflicts with expected side to move";
                        lastInfo = "UNCERTAIN: " + u.info;
                        return u;
                    }
                    alternatives.add(new Match(pos.copy(), new ArrayList<Chess.Move>(), corrected, 1));
                    continue;
                }
                Match m = findMatch(pos, corrected, 1, expectedSide, pieceCodes, pieceTypeConfidence);
                if (m != null) alternatives.add(m);
                else if (lastSearchTurnMismatch) {
                    u.ambiguous = true;
                    u.info = "candidate transition conflicts with expected side to move";
                    lastInfo = "UNCERTAIN: " + u.info;
                    return u;
                } else if (lastSearchAmbiguous) {
                    u.ambiguous = true;
                    u.info = "ambiguous after low-confidence correction";
                    lastInfo = "UNCERTAIN: " + u.info;
                    return u;
                }
            }
        }

        if (alternatives.size() == 1) return commit(u, alternatives.get(0));
        if (alternatives.size() > 1) {
            u.ambiguous = true;
            u.info = "multiple low-confidence corrections fit";
            lastInfo = "UNCERTAIN: " + u.info;
            return u;
        }

        u.ok = false;
        u.info = "no unique legal explanation";
        lastInfo = "UNCERTAIN: " + u.info;
        return u;
    }

    // State of the most recent search; used only synchronously by observe().
    private boolean lastSearchAmbiguous;
    private boolean lastSearchIncomplete;
    private boolean lastSearchTurnMismatch;

    private Update commit(Update u, Match match) {
        pos = match.result.copy();
        history.addAll(match.moves);
        lastPattern = match.target.clone();
        u.ok = true;
        u.changed = !match.moves.isEmpty();
        u.fixedSquares = match.fixedSquares;
        u.moves = new ArrayList<>(match.moves);
        u.matchedPattern = match.target.clone();
        StringBuilder sb = new StringBuilder();
        for (Chess.Move move : match.moves) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(move.uci());
        }
        if (match.fixedSquares > 0) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("corrected ").append(match.fixedSquares).append(" low-confidence square");
        }
        u.info = sb.length() == 0 ? "position retained" : sb.toString();
        lastInfo = u.info;
        return u;
    }

    /**
     * Returns the unique shortest legal sequence that exactly explains target. Ambiguous
     * observations are explicitly rejected rather than taking search order as a guess.
     */
    private Match findMatch(Chess.Pos start, int[] target, int fixed, int expectedSide,
                            int[] pieceCodes, float[] pieceTypeConfidence) {
        lastSearchAmbiguous = false;
        lastSearchIncomplete = false;
        lastSearchTurnMismatch = false;
        Chess.Pos base = start.copy();
        int diff = countDiff(Chess.pattern(base, whiteBottom), target);
        if (diff == 0) {
            if (expectedSide != 0 && base.side != expectedSide) {
                lastSearchTurnMismatch = true;
                return null;
            }
            return new Match(base, new ArrayList<Chess.Move>(), target, fixed);
        }

        for (int depth = 1; depth <= MAX_SEARCH_PLIES; depth++) {
            if (diff > 4 * depth) continue;
            List<List<Chess.Move>> found = searchFrom(base, target, depth);
            // A search that hit its node/result cap cannot prove uniqueness. Never accept a
            // first-found candidate when unexplored branches may contain a second legal path.
            if (lastSearchIncomplete) {
                lastSearchAmbiguous = true;
                return null;
            }
            if (found.isEmpty()) continue;
            Chess.Pos result;
            List<Chess.Move> sequence;
            if (found.size() > 1) {
                Match resolved = disambiguate(base, target, found, fixed, pieceCodes, pieceTypeConfidence);
                if (resolved == null) {
                    lastSearchAmbiguous = true;
                    return null;
                }
                result = resolved.result;
                sequence = resolved.moves;
            } else {
                sequence = found.get(0);
                result = applySequence(base, sequence);
                // A second whole-position check protects against future changes to the search.
                if (!Arrays.equals(Chess.pattern(result, whiteBottom), target)) return null;
            }
            // The side-to-move follows from the actual legal move sequence. If that conflicts
            // with the caller's independently inferred turn, reject the candidate; never flip
            // side metadata or attempt to repair the inconsistency by changing piece identities.
            if (expectedSide != 0 && result.side != expectedSide) {
                lastSearchTurnMismatch = true;
                return null;
            }
            return new Match(result, sequence, target, fixed);
        }
        return null;
    }

    private Chess.Pos applySequence(Chess.Pos base, List<Chess.Move> seq) {
        Chess.Pos result = base.copy();
        for (Chess.Move move : seq) Chess.make(result, move);
        // The sequence is already made entirely of legal moves. Never sanitize afterward:
        // that routine can alter piece types and would violate the tracker's identity invariant.
        return result;
    }

    /** Resolve ambiguity only when visual piece-type evidence distinguishes legal outcomes. */
    private Match disambiguate(Chess.Pos base, int[] target, List<List<Chess.Move>> paths,
                               int fixed, int[] pieceCodes, float[] typeConfidence) {
        if (pieceCodes == null || typeConfidence == null || paths == null || paths.size() < 2
                || paths.size() >= 8) return null; // searchFrom caps its result list; a full list is truncated
        List<Chess.Pos> finals = new ArrayList<>();
        for (List<Chess.Move> path : paths) {
            Chess.Pos p = applySequence(base, path);
            if (!Arrays.equals(Chess.pattern(p, whiteBottom), target)) return null;
            finals.add(p);
        }

        boolean[] discriminating = new boolean[64];
        int evidenceSquares = 0;
        for (int screen = 0; screen < 64; screen++) {
            int chess = chessIdx(screen);
            int first = finals.get(0).cb[chess];
            boolean differs = false;
            for (int j = 1; j < finals.size(); j++) {
                if (finals.get(j).cb[chess] != first) { differs = true; break; }
            }
            if (differs && saneConfidence(typeConfidence[screen]) >= 0.68f
                    && pieceCodes[screen] >= 0 && pieceCodes[screen] <= 14) {
                discriminating[screen] = true;
                evidenceSquares++;
            }
        }
        if (evidenceSquares == 0) return null;

        List<Integer> survivors = new ArrayList<>();
        for (int candidate = 0; candidate < finals.size(); candidate++) {
            boolean matches = true;
            for (int screen = 0; screen < 64; screen++) {
                if (!discriminating[screen]) continue;
                int observedCode = pieceCodes[screen];
                int actualCode = finals.get(candidate).cb[chessIdx(screen)];
                if (observedCode != actualCode) { matches = false; break; }
            }
            if (matches) survivors.add(candidate);
        }
        if (survivors.size() != 1) return null;
        int selected = survivors.get(0);
        return new Match(finals.get(selected), paths.get(selected), target, fixed);
    }

    private static float saneConfidence(float c) {
        if (Float.isNaN(c) || Float.isInfinite(c)) return 1f;
        return Math.max(0f, Math.min(1f, c));
    }

    private static int firstDiff(int[] a, int[] b) {
        for (int i = 0; i < 64; i++) if (a[i] != b[i]) return i;
        return -1;
    }

    private static int countDiff(int[] a, int[] b) {
        int d = 0;
        for (int i = 0; i < 64; i++) if (a[i] != b[i]) d++;
        return d;
    }

    private static int[] set(int[] a, int idx, int value) {
        int[] b = a.clone();
        b[idx] = value;
        return b;
    }

    /** Searches for the shortest move sequence which reaches the complete observed colour pattern. */
    private List<List<Chess.Move>> searchFrom(Chess.Pos startPos, int[] target, int depth) {
        List<List<Chess.Move>> out = new ArrayList<>();
        int[] budget = new int[]{SEARCH_BUDGET};
        rec(startPos.copy(), target, depth, new ArrayList<Chess.Move>(), out, budget);
        lastSearchIncomplete = budget[0] <= 0 || out.size() >= 8;
        return out;
    }

    private int chessIdx(int screenIdx) {
        int f = Board.fileIdxOf(screenIdx, whiteBottom), r = Board.rankIdxOf(screenIdx, whiteBottom);
        return (7 - r) * 8 + f;
    }

    private void rec(Chess.Pos p, int[] target, int depth, List<Chess.Move> acc,
                     List<List<Chess.Move>> out, int[] budget) {
        if (out.size() >= 8 || budget[0] <= 0) return;
        int[] cur = Chess.pattern(p, whiteBottom);
        boolean[] need = new boolean[64];
        int diff = 0;
        for (int i = 0; i < 64; i++) {
            if (cur[i] != target[i]) {
                diff++;
                need[chessIdx(i)] = true;
            }
        }
        if (diff == 0) {
            out.add(new ArrayList<>(acc));
            return;
        }
        if (depth == 0 || diff > 4 * depth) return;
        for (Chess.Move move : Chess.legal(p)) {
            if (!need[move.from] && !need[move.to]) continue;
            budget[0]--;
            if (budget[0] <= 0) return;
            Chess.Pos q = p.copy();
            Chess.make(q, move);
            acc.add(move);
            rec(q, target, depth - 1, acc, out, budget);
            acc.remove(acc.size() - 1);
            if (out.size() >= 8) return;
        }
    }
}
