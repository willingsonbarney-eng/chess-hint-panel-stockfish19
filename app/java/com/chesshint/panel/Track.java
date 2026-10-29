package com.chesshint.panel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The trusted position of the game that is being watched.
 *
 * WHY THIS CLASS EXISTS
 * ---------------------
 * The screen reader can only see "empty / white piece / black piece" per square (plus a
 * confidence).  Piece TYPES can therefore only come from following the game: we start from a
 * position we KNOW (the standard start position, or one the user confirmed) and after every
 * change on screen we look for the legal move(s) that explain exactly what was seen.
 *
 * The old implementation fell back to "guessing" piece types from silhouettes whenever the
 * explanation failed, replaced the tracked position with that guess and silently rewrote
 * pieces to make the position legal.  Every one of those paths changed the identity of
 * pieces that had not moved.  This implementation has exactly ONE way of changing a piece's
 * identity: a legal move (or promotion) that is the unique explanation of the observation.
 *
 * Rules:
 *  - The trusted position never changes on a single frame: a change must be seen on
 *    {@link #confirmFrames} consecutive observations (low confidence squares are merged
 *    across frames, the more certain reading wins).
 *  - Squares with low confidence are wildcards - they can neither cause nor block a change.
 *  - Candidate positions are generated from the previous trusted position with legal moves
 *    only (captures, castling, en passant, promotions; up to {@link #maxPlies} plies, both
 *    sides), never square by square.
 *  - More than one explanation = AMBIGUOUS: the trusted position is kept and we wait.
 *  - No explanation = UNEXPLAINED; after {@link #lostFrames} stable frames the tracker is
 *    marked {@link #lost}.  The trusted position is still kept (it is never corrupted) and
 *    the caller must stop analysing until it is re-synchronised (explained move, take-back,
 *    new game or the user fixing the pieces).
 */
public class Track {

    public enum Status {
        /** nothing to do: the screen agrees with the trusted position */
        UNCHANGED,
        /** a change was seen but has not been confirmed yet (or the frame was unusable) */
        PENDING,
        /** one legal explanation found, trusted position advanced */
        MOVED,
        /** the screen shows an earlier trusted position again (take-back) */
        REWOUND,
        /** the standard start position is on screen again */
        NEW_GAME,
        /** the board was turned around, same position */
        ORIENTATION,
        /** several different positions explain the observation - trusted position kept */
        AMBIGUOUS,
        /** no legal explanation (yet) - trusted position kept */
        UNEXPLAINED,
        /** unexplained / ambiguous for too long - needs the user (or a new game) */
        LOST,
        /** the caller passed something unusable */
        INVALID
    }

    // ------------------------------------------------------------------ tunables
    /** identical (on the certain squares) observations needed before a change is committed */
    public int confirmFrames = 2;
    /** committed-but-unexplained observations before the tracker reports LOST */
    public int lostFrames = 3;
    /** squares with conf below this are wildcards */
    public static final float CERTAIN_DEFAULT = 0.28f;
    public float certain = CERTAIN_DEFAULT;
    /** max plies (half moves) explained in one go during normal operation */
    public int maxPlies = 4;
    /** max plies when recovering after repeated failures */
    public int recoveryPlies = 6;
    /** legal() calls allowed per search */
    public int nodeBudget = 100000;
    /** extra nodes for the "is the side to move really known?" check */
    public int tempoBudget = 30000;
    public static boolean DEBUG = false;
    /** frames with more uncertain squares than this carry no information */
    public int poorFrameUncertain = 20;
    /** how many earlier trusted positions are remembered for take-backs */
    public int snapshotDepth = 48;

    // -------------------------------------------------------------------- state
    /** last trusted position (never replaced by anything but a legal explanation or the user) */
    public Chess.Pos pos;
    public boolean whiteBottom;
    /** false when the side to move was only assumed (position typed in by the user) */
    public boolean sideKnown = true;
    /** true while pos is a validated, trusted position */
    private boolean trusted;
    /** bumped whenever the trusted position changes - lets consumers drop stale results */
    public long version;
    public boolean lost;
    public String lostReason = "";
    public int[] lastPattern = new int[64];
    public String lastInfo = "";
    public List<Chess.Move> history = new ArrayList<>();
    /** chess-order squares holding a piece whose type was assumed (promotion: queen) */
    public boolean[] assumed = new boolean[64];

    private static class Snapshot {
        Chess.Pos pos; boolean[] assumed; int histSize; boolean sideKnown;
    }
    private final ArrayDeque<Snapshot> past = new ArrayDeque<>();

    // memo of the last failed deep search: the same frame is not searched again
    private long memoSig; private long memoVersion = -1; private Status memoStatus; private String memoWhy;

    // pending (not yet committed) observation
    private int[] pendPat;
    private float[] pendConf;
    private int pendCount;
    private int failCount;
    /** unexplained observations in a row, even when the screen keeps changing */
    private int failStreak;

    public Track(Chess.Pos pos, boolean whiteBottom) {
        this.whiteBottom = whiteBottom;
        this.pos = pos.copy();
        this.trusted = Chess.validate(this.pos).isEmpty();
        this.lastPattern = Chess.pattern(this.pos, whiteBottom);
    }

    public boolean isTrusted() { return trusted; }

    /** the standard starting position, white to move */
    public static Chess.Pos startPosition() {
        return Chess.fromBoard(Board.starting(true), true, Chess.WHITE);
    }

    /** trusted by the caller (new game, verified start position): side to move is known */
    public String reset(Chess.Pos p) { return resetInternal(p, true); }

    /** a position the user typed in: the side to move was only assumed */
    public String resetUser(Chess.Pos p) { return resetInternal(p, false); }

    /** @return "" when accepted, otherwise why the position was refused (state is untouched then) */
    private String resetInternal(Chess.Pos p, boolean sideIsKnown) {
        String err = Chess.validate(p);
        if (!err.isEmpty()) return err;
        pos = p.copy();
        trusted = true;
        sideKnown = sideIsKnown;
        history.clear();
        past.clear();
        assumed = new boolean[64];
        lastPattern = Chess.pattern(pos, whiteBottom);
        lastInfo = "";
        lost = false;
        lostReason = "";
        clearPending();
        version++;
        return "";
    }

    /** the board is shown from the other side now - the position itself does not change */
    public void setWhiteBottom(boolean wb) {
        if (wb == whiteBottom) return;
        whiteBottom = wb;
        lastPattern = Chess.pattern(pos, whiteBottom);
        clearPending();
    }

    private void clearPending() {
        pendPat = null;
        pendConf = null;
        pendCount = 0;
        failCount = 0;
        failStreak = 0;
    }

    // ===================================================================== result

    public static class Update {
        public Status status = Status.UNCHANGED;
        public boolean changed;      // the trusted position changed in this call
        public boolean ok;           // trusted position agrees with the screen
        public boolean ambiguous;
        public List<Chess.Move> moves = new ArrayList<>();
        public int fixedSquares;     // always 0 now (kept for old callers): squares are never "fixed"
        public boolean promotionAssumed;
        public long version;
        public String info = "";
    }

    // ================================================================== observing

    /** feed one observation (colour pattern in screen order + certainty per square) */
    public Update observe(int[] observed, float[] conf) {
        Update u = new Update();
        u.version = version;
        if (!trusted || observed == null || observed.length != 64) {
            u.status = Status.INVALID;
            u.info = trusted ? "bad observation" : "no trusted position";
            return u;
        }
        float[] cf = conf;
        if (cf == null || cf.length != 64) {
            cf = new float[64];
            Arrays.fill(cf, 1f);
        }
        boolean[] unc = new boolean[64];
        int uncertainCount = 0, white = 0, black = 0;
        for (int i = 0; i < 64; i++) {
            unc[i] = cf[i] < certain;
            if (unc[i]) { uncertainCount++; continue; }
            if (observed[i] == 1) white++; else if (observed[i] == 2) black++;
        }
        // frames that cannot be a chess board carry no information: ignore, change nothing
        if (white + black < 2 || white > 16 || black > 16 || uncertainCount > poorFrameUncertain) {      // counted on the certain squares only
            u.status = Status.PENDING;
            u.info = "unusable frame";
            return u;
        }

        int[] cur = Chess.pattern(pos, whiteBottom);
        if (matches(cur, observed, unc)) {
            clearPending();
            lastPattern = cur;
            lost = false;
            lostReason = "";
            u.status = Status.UNCHANGED;
            u.ok = true;
            return u;
        }

        // ---- debounce: the same change must be seen again before anything is committed
        if (pendPat != null && agrees(pendPat, pendConf, observed, cf)) {
            mergeInto(pendPat, pendConf, observed, cf);
            pendCount++;
        } else {
            pendPat = observed.clone();
            pendConf = cf.clone();
            pendCount = 1;
            failCount = 0;
        }
        if (pendCount < confirmFrames) {
            u.status = Status.PENDING;
            u.info = "waiting for the next frame";
            return u;
        }
        return resolve(u);
    }

    /**
     * For callers that already know the frame is stable (two identical capture signatures, or a
     * capture that waited for the screen to settle): counts as the confirmation frames.
     */
    public Update observeStable(int[] observed, float[] conf) {
        Update u = null;
        for (int i = 0; i < Math.max(1, confirmFrames); i++) {
            u = observe(observed, conf);
            if (u.status != Status.PENDING) break;
        }
        return u;
    }

    /** does the trusted position agree with this picture on every certain square? */
    public boolean matches(int[] observed, float[] conf) {
        if (observed == null || observed.length != 64) return false;
        boolean[] unc = new boolean[64];
        for (int i = 0; i < 64; i++) unc[i] = conf == null || conf.length != 64 || conf[i] < certain;
        return matches(Chess.pattern(pos, whiteBottom), observed, unc);
    }

    // -------------------------------------------------------------------- resolving

    private Update resolve(Update u) {
        int[] obs = pendPat.clone();
        float[] cf = pendConf.clone();
        boolean[] unc = new boolean[64];
        long sig = 1469598103934665603L;
        for (int i = 0; i < 64; i++) {
            unc[i] = cf[i] < certain;
            sig = (sig ^ (unc[i] ? 3 : obs[i])) * 1099511628211L;
        }
        sig ^= whiteBottom ? 1 : 2;

        boolean deep = failCount >= 1;           // after the first failure look further
        int plies = deep ? recoveryPlies : maxPlies;
        if (deep && memoVersion == version && memoSig == sig && memoStatus != null) {
            return fail(u, memoStatus, memoWhy);      // same frame, same trusted position, same answer
        }

        // 1. legal moves from the trusted position, board shown the way we think it is
        Outcome o = explain(pos, whiteBottom, obs, unc, cf, plies, false);
        if (o.kind == Kind.FOUND) return commitMoves(u, o, whiteBottom, Status.MOVED);
        if (o.kind == Kind.AMBIGUOUS) return failMemo(u, Status.AMBIGUOUS, "several moves fit what is on screen", deep, sig);

        // 2. standard start position on screen again = new game
        Status r = tryNewGame(u, obs, unc);
        if (r != null) return u;

        // 3. same position shown from the other side (some apps turn the board)
        int[] flippedCur = Chess.pattern(pos, !whiteBottom);
        if (matches(flippedCur, obs, unc)) {
            whiteBottom = !whiteBottom;
            lastPattern = flippedCur;
            clearPending();
            lost = false;
            lostReason = "";
            u.status = Status.ORIENTATION;
            u.ok = true;
            u.info = "board turned around";
            u.version = version;
            return u;
        }
        Outcome of = explain(pos, !whiteBottom, obs, unc, cf, plies, false);
        if (of.kind == Kind.FOUND) return commitMoves(u, of, !whiteBottom, Status.MOVED);
        if (of.kind == Kind.AMBIGUOUS) return failMemo(u, Status.AMBIGUOUS, "several moves fit what is on screen", deep, sig);

        // 4. take-back: an earlier trusted position is on screen again
        if (tryRewind(u, obs, unc)) return u;

        // 5. an under-promotion (rook / bishop / knight) inside the explanation
        if (promotionPossible(plies)) {
            Outcome ou = explain(pos, whiteBottom, obs, unc, cf, plies, true);
            if (ou.kind == Kind.FOUND) return commitMoves(u, ou, whiteBottom, Status.MOVED);
        }

        return failMemo(u, Status.UNEXPLAINED, "no legal move explains the board", deep, sig);
    }

    private Update failMemo(Update u, Status s, String why, boolean deep, long sig) {
        if (deep) { memoSig = sig; memoVersion = version; memoStatus = s; memoWhy = why; }
        return fail(u, s, why);
    }

    /** can a pawn reach the last rank within the plies we search? */
    private boolean promotionPossible(int plies) {
        int moves = (plies + 1) / 2;
        for (int i = 0; i < 64; i++) {
            int rankFromTop = i >> 3;                  // chess order: 0 = rank 8
            if (pos.cb[i] == 1 && rankFromTop <= moves) return true;          // white pawn close to rank 8
            if (pos.cb[i] == 9 && rankFromTop >= 7 - moves) return true;      // black pawn close to rank 1
        }
        for (int i = 0; i < 64; i++) if (assumed[i]) return true;
        return false;
    }

    private Update fail(Update u, Status s, String why) {
        failCount++;
        failStreak++;
        u.status = s;
        u.ambiguous = s == Status.AMBIGUOUS;
        u.info = why;
        if (failCount > lostFrames || failStreak > lostFrames + 2) {
            lost = true;
            lostReason = why;
            u.status = Status.LOST;
        }
        return u;
    }

    private Update commitMoves(Update u, Outcome o, boolean wbForMoves, Status status) {
        pushSnapshot();
        boolean[] newAssumed = o.assumedAfter;
        pos = o.pos;
        assumed = newAssumed;
        whiteBottom = wbForMoves;
        history.addAll(o.moves);
        sideKnown = !o.tempoAlt;
        if (o.tempoAlt) pos.epSq = -1;       // the en passant square is only valid for one particular tempo
        lastPattern = Chess.pattern(pos, whiteBottom);
        clearPending();
        lost = false;
        lostReason = "";
        version++;
        u.status = status;
        u.changed = true;
        u.ok = true;
        u.moves = new ArrayList<>(o.moves);
        u.promotionAssumed = o.promotionAssumed;
        u.version = version;
        StringBuilder sb = new StringBuilder();
        for (Chess.Move m : o.moves) sb.append(m.uci()).append(' ');
        u.info = sb.toString().trim();
        lastInfo = u.info;
        return u;
    }

    private void pushSnapshot() {
        Snapshot s = new Snapshot();
        s.pos = pos.copy();
        s.assumed = assumed.clone();
        s.histSize = history.size();
        s.sideKnown = sideKnown;
        past.addFirst(s);
        while (past.size() > snapshotDepth) past.removeLast();
    }

    private Status tryNewGame(Update u, int[] obs, boolean[] unc) {
        Chess.Pos start = startPosition();
        for (int k = 0; k < 2; k++) {
            boolean wb = k == 0 ? whiteBottom : !whiteBottom;
            int[] sp = Chess.pattern(start, wb);
            if (!matches(sp, obs, unc)) continue;
            int certainPieces = 0;
            for (int i = 0; i < 64; i++) if (!unc[i] && sp[i] != 0) certainPieces++;
            if (certainPieces < 28) continue;
            if (Arrays.equals(Chess.pattern(pos, wb), sp) && Chess.validate(pos).isEmpty()
                    && pos.key().equals(start.key())) continue;
            whiteBottom = wb;
            String err = resetInternal(start, true);
            if (!err.isEmpty()) return null;
            u.status = Status.NEW_GAME;
            u.changed = true;
            u.ok = true;
            u.version = version;
            u.info = "new game";
            return Status.NEW_GAME;
        }
        return null;
    }

    private boolean tryRewind(Update u, int[] obs, boolean[] unc) {
        Iterator<Snapshot> it = past.iterator();
        int n = 0;
        while (it.hasNext()) {
            Snapshot s = it.next();
            n++;
            if (matches(Chess.pattern(s.pos, whiteBottom), obs, unc)) {
                for (int k = 0; k < n; k++) past.pollFirst();      // forget the future we undid
                pos = s.pos.copy();
                assumed = s.assumed.clone();
                sideKnown = s.sideKnown;
                while (history.size() > s.histSize) history.remove(history.size() - 1);
                lastPattern = Chess.pattern(pos, whiteBottom);
                clearPending();
                lost = false;
                lostReason = "";
                version++;
                u.status = Status.REWOUND;
                u.changed = true;
                u.ok = true;
                u.version = version;
                u.info = "take-back";
                return true;
            }
        }
        return false;
    }

    // ==================================================================== search

    private enum Kind { NONE, FOUND, AMBIGUOUS }

    /** one "what are the assumed (promoted) pieces really?" hypothesis; index 0 = all queens */
    private static class Variant {
        Chess.Pos base;
        boolean[] flags;
        List<Integer> retyped = new ArrayList<>();
    }

    private static class Cand {
        Chess.Pos pos; List<Chess.Move> moves; float cost; boolean[] flagsAfter; boolean tempo;
    }

    private static class Outcome {
        Kind kind = Kind.NONE;
        Chess.Pos pos;
        List<Chess.Move> moves = new ArrayList<>();
        boolean[] assumedAfter;
        boolean promotionAssumed;
        boolean tempoAlt;       // the same board is also reachable with the other side to move
    }

    /**
     * Promoted pieces are assumed to be queens (the colour picture cannot tell).  While such
     * a piece is on the board the search also tries "it is really a rook / bishop / knight",
     * but a different type only wins when the piece itself moved in the explanation (the
     * movement is the evidence) and the explanation is shorter or cheaper.
     */
    private List<Variant> variantsOf(Chess.Pos base) {
        List<Variant> out = new ArrayList<>();
        Variant v0 = new Variant();
        v0.base = base;
        v0.flags = assumed;
        out.add(v0);
        List<Integer> sq = new ArrayList<>();
        for (int i = 0; i < 64; i++) if (assumed[i] && base.cb[i] != 0) sq.add(i);
        if (sq.isEmpty() || sq.size() > 2) return out;
        int combos = 1;
        for (int i = 0; i < sq.size(); i++) combos *= 4;
        for (int c = 1; c < combos; c++) {
            Variant v = new Variant();
            Chess.Pos b = base.copy();
            boolean[] f = assumed.clone();
            int x = c;
            for (int idx : sq) {
                int t = x % 4; x /= 4;               // 0 = Q (unchanged), 1 = R, 2 = B, 3 = N
                if (t == 0) continue;
                int type = t == 1 ? 4 : (t == 2 ? 3 : 2);
                b.cb[idx] = Board.isWhite(b.cb[idx]) ? type : type + 8;
                f[idx] = false;
                v.retyped.add(idx);
            }
            if (v.retyped.isEmpty() || !Chess.validate(b).isEmpty()) continue;
            v.base = b;
            v.flags = f;
            out.add(v);
        }
        return out;
    }

    /**
     * Shortest sequence(s) of legal moves (alternating sides, starting with the side to
     * move; both sides when the side to move is only assumed) after which every CERTAIN square
     * shows what the screen shows.  Iterative deepening: the explanation with the fewest plies
     * wins.  Explanations that lead to the same board are one explanation (they may differ in
     * castling rights / en passant square only - the more conservative rights are kept);
     * explanations that lead to different boards are AMBIGUOUS and nothing is changed.
     */
    private Outcome explain(Chess.Pos base, boolean wb, int[] obs, boolean[] unc, float[] cf,
                            int maxDepth, boolean underPromotions) {
        Outcome out = new Outcome();
        List<Variant> variants = variantsOf(base);
        int[] budget = new int[]{nodeBudget};
        for (int depth = 1; depth <= maxDepth; depth++) {
            Map<String, Cand> found = collect(variants, depth, obs, unc, cf, wb, budget, underPromotions);
            if (found.isEmpty()) {
                if (budget[0] <= 0) return out;       // gave up - never guess
                continue;
            }
            List<Cand> reps = groupByBoard(found, base.side);
            Cand best = reps.get(0);
            if (DEBUG) for (Cand c : reps) System.err.println("depth " + depth + " cand " + c.moves + " cost " + c.cost + " " + c.pos.fen());
            if (reps.size() > 1) {                       // two different boards both fit the certain squares: a guess between them is never made
                out.kind = Kind.AMBIGUOUS;
                return out;
            }
            // Several plies at once: a longer sequence with the same colour picture but a different
            // board (e.g. queen and rook, or knight and pawn, ending on each other's squares) is
            // indistinguishable from the colour picture - never guess between them.  A longer
            // sequence reaching the same board with the other side to move (a pawn that stepped
            // e3-e4 instead of e2-e4 costs one more ply) only makes the side to move uncertain.
            if (depth >= 2 || !sideKnown) {
                int verifyTo = depth + 1;     // +2 never changes the tempo and is not a plausible identity swap
                for (int dd = depth + 1; dd <= verifyTo; dd++) {
                    if (budget[0] <= 0) { if (DEBUG) System.err.println("budget out before dd=" + dd); out.kind = Kind.AMBIGUOUS; return out; }   // cannot prove uniqueness
                    Map<String, Cand> longer = collect(variants, dd, obs, unc, cf, wb, budget, underPromotions);
                    for (Cand c : longer.values()) {
                        if (DEBUG) System.err.println("verify dd=" + dd + " alt " + c.moves + " best " + best.moves + " same=" + sameBoardModuloAssumed(best, c));
                        if (!sameBoardModuloAssumed(best, c)) {
                            // A different board reached with the same colour picture is only a real
                            // doubt when it is about as short as the best explanation (one ply more).
                            // Two plies more (a piece wandering round and swapping places with a
                            // neighbour) or a king-walk instead of castling is not plausible.
                            if (dd >= depth + 2 || castlingPrior(best, c)) continue;
                            out.kind = Kind.AMBIGUOUS;
                            return out;
                        }
                        if (c.pos.side != best.pos.side && dd == depth + 1) out.tempoAlt = true;
                    }
                    if (budget[0] <= 0) { out.kind = Kind.AMBIGUOUS; out.tempoAlt = false; return out; }   // search was cut short
                }
            }
            if (depth >= 3) {
                // Three or more plies at once: a rival board two plies longer (e.g. a bishop and a pawn
                // ending on each other's squares) is a real risk, so it has to be ruled out too.
                int[] vb = new int[]{tempoBudget * 3};
                Map<String, Cand> longer = collect(variants, depth + 2, obs, unc, cf, wb, vb, underPromotions);
                if (vb[0] <= 0) { out.kind = Kind.AMBIGUOUS; return out; }
                for (Cand c : longer.values()) {
                    if (!sameBoardModuloAssumed(best, c) && !castlingPrior(best, c)) { out.kind = Kind.AMBIGUOUS; return out; }
                }
            }
            if (best.tempo) out.tempoAlt = true;
            if (!sideKnown && !out.tempoAlt) {
                // the starting side was only assumed: a longer way (two plies more, starting with the
                // other side) to the same board would mean the other side is to move
                int[] tb = new int[]{tempoBudget};
                Map<String, Cand> longer = collect(variants, depth + 2, obs, unc, cf, wb, tb, underPromotions);
                if (tb[0] <= 0) out.tempoAlt = true;
                else for (Cand c : longer.values()) {
                    if (c.pos.side != best.pos.side && sameBoardModuloAssumed(best, c)) out.tempoAlt = true;
                }
            }
            out.kind = Kind.FOUND;
            out.pos = best.pos;
            out.moves = best.moves;
            out.assumedAfter = best.flagsAfter;
            out.promotionAssumed = anyNewAssumed(assumed, best.flagsAfter);
            return out;
        }
        return out;
    }

    private Map<String, Cand> collect(List<Variant> variants, int depth, int[] obs, boolean[] unc, float[] cf,
                                      boolean wb, int[] budget, boolean underPromotions) {
        Map<String, Cand> found = new LinkedHashMap<>();
        long dbgT = DEBUG ? System.nanoTime() : 0; int dbgB = budget[0];
        try {
        for (Variant v : variants) {
            List<Chess.Pos> starts = new ArrayList<>();
            starts.add(v.base);
            if (!sideKnown) {
                Chess.Pos other = v.base.copy();
                other.side = 3 - other.side;
                other.epSq = -1;
                if (Chess.validate(other).isEmpty()) starts.add(other);
            }
            for (Chess.Pos s : starts) {
                java.util.HashSet<Long> seen = new java.util.HashSet<>();
                dfs(s, s.copy(), v, depth, obs, unc, cf, wb, new ArrayList<Chess.Move>(), found, budget, underPromotions, seen);
            }
        }
        } finally {
            if (DEBUG) System.err.println("collect depth " + depth + " nodes " + (dbgB - budget[0]) + " ms " + (System.nanoTime() - dbgT) / 1000000);
        }
        return found;
    }

    /**
     * Group by board (not by side): the same board reached with the other side to move is only a
     * tempo question, not a different position.  Castling rights / en passant differences are not
     * ambiguity either.  Cheapest first.
     */
    private static List<Cand> groupByBoard(Map<String, Cand> found, int baseSide) {
        Map<String, List<Cand>> groups = new LinkedHashMap<>();
        for (Cand c : found.values()) {
            String bk = boardKey(c.pos);
            List<Cand> l = groups.get(bk);
            if (l == null) { l = new ArrayList<>(); groups.put(bk, l); }
            l.add(c);
        }
        List<Cand> reps = new ArrayList<>();
        for (List<Cand> g : groups.values()) reps.add(mergeGroup(g, baseSide));
        reps.sort((x, y) -> Float.compare(x.cost, y.cost));
        return reps;
    }

    /** same pieces on the same squares, except that an assumed (promoted) piece may be of any type */
    private static boolean sameBoardModuloAssumed(Cand a, Cand b) {
        for (int i = 0; i < 64; i++) {
            int x = a.pos.cb[i], y = b.pos.cb[i];
            if (x == y) continue;
            if (x == 0 || y == 0) return false;
            if (Board.isWhite(x) != Board.isWhite(y)) return false;
            if (!(a.flagsAfter[i] || b.flagsAfter[i])) return false;
        }
        return true;
    }

    /** best contains a castling move and the alternative differs only on that side's castling squares */
    private static boolean castlingPrior(Cand best, Cand alt) {
        boolean castled = false;
        for (Chess.Move m : best.moves) if (m.castle) castled = true;
        if (!castled) return false;
        for (int i = 0; i < 64; i++) {
            if (best.pos.cb[i] == alt.pos.cb[i]) continue;
            boolean top = i < 8, bottom = i >= 56;
            if (!(top || bottom)) return false;
            int f = i & 7;
            if (f == 0 || f == 7 || f == 4 || f == 5 || f == 6 || f == 3 || f == 2) continue;
            return false;
        }
        return true;
    }

    private static String boardKey(Chess.Pos p) {
        StringBuilder sb = new StringBuilder(66);
        for (int v : p.cb) sb.append((char) ('A' + v));
        return sb.toString();
    }

    /** same board reached in different ways: keep the cheapest / shortest, with the most conservative rights */
    private static Cand mergeGroup(List<Cand> g, int baseSide) {
        Cand best = g.get(0);
        for (Cand c : g) {
            if (c.cost < best.cost - 1e-6f) best = c;
            else if (Math.abs(c.cost - best.cost) <= 1e-6f) {
                if (c.moves.size() < best.moves.size()) best = c;
                else if (c.moves.size() == best.moves.size() && startSide(c) == baseSide && startSide(best) != baseSide) best = c;
            }
        }
        boolean tempo = false;
        for (Cand c : g) if (c.pos.side != best.pos.side) tempo = true;
        if (g.size() == 1) return best;
        Cand m = new Cand();
        m.pos = best.pos.copy();
        m.moves = best.moves;
        m.cost = best.cost;
        m.flagsAfter = best.flagsAfter;
        m.tempo = tempo;
        StringBuilder rights = new StringBuilder();
        String first = Chess.feasibleCastling(best.pos.cb, best.pos.castling);
        for (char ch : first.toCharArray()) {
            if (ch == '-') continue;
            boolean all = true;
            for (Cand c : g) if (Chess.feasibleCastling(c.pos.cb, c.pos.castling).indexOf(ch) < 0) all = false;
            if (all) rights.append(ch);
        }
        m.pos.castling = rights.length() == 0 ? "-" : rights.toString();
        boolean sameEp = true;
        for (Cand c : g) if (c.pos.epSq != best.pos.epSq) sameEp = false;
        if (!sameEp || tempo) m.pos.epSq = -1;
        return m;
    }

    private static int startSide(Cand c) {
        return (c.moves.size() % 2 == 0) ? c.pos.side : 3 - c.pos.side;
    }

    private static boolean anyNewAssumed(boolean[] before, boolean[] after) {
        for (int i = 0; i < 64; i++) if (after[i] && !before[i]) return true;
        return false;
    }

    /** where did the piece that stood on square s at the start end up (-1 = captured)? */
    private static int[] tokensAfter(Chess.Pos root, List<Chess.Move> moves) {
        int[] tok = new int[64];
        for (int i = 0; i < 64; i++) tok[i] = root.cb[i] != 0 ? i : -1;
        int side = root.side;
        for (Chess.Move m : moves) {
            int t = tok[m.from];
            tok[m.from] = -1;
            tok[m.to] = t;
            if (m.ep) tok[side == Chess.WHITE ? m.to + 8 : m.to - 8] = -1;
            if (m.castle) {
                if (m.to == 62) { tok[61] = tok[63]; tok[63] = -1; }
                else if (m.to == 58) { tok[59] = tok[56]; tok[56] = -1; }
                else if (m.to == 6) { tok[5] = tok[7]; tok[7] = -1; }
                else if (m.to == 2) { tok[3] = tok[0]; tok[0] = -1; }
            }
            side = 3 - side;
        }
        return tok;
    }

    private static boolean[] propagateAssumed(Chess.Pos root, boolean[] flags, List<Chess.Move> moves) {
        boolean[] f = flags.clone();
        int side = root.side;
        for (Chess.Move m : moves) {
            boolean moverFlag = f[m.from];
            f[m.from] = false;
            f[m.to] = moverFlag;                      // a capture clears the victim's flag
            if (m.ep) f[side == Chess.WHITE ? m.to + 8 : m.to - 8] = false;
            if (m.castle) {
                if (m.to == 62) { f[61] = f[63]; f[63] = false; }
                else if (m.to == 58) { f[59] = f[56]; f[56] = false; }
                else if (m.to == 6) { f[5] = f[7]; f[7] = false; }
                else if (m.to == 2) { f[3] = f[0]; f[0] = false; }
            }
            if (m.promo > 0) f[m.to] = (m.promo == 5);   // assumed queen; an explicit under-promotion is a fact
            side = 3 - side;
        }
        return f;
    }

    /** upper bound of colour-picture squares r more plies can change: 2 per move, +2 castling (once per side), +1 en passant */
    private static int maxFix(Chess.Pos p, int r, int firstSide) {
        int n = 2 * r;
        boolean wCastle = p.castling.indexOf('K') >= 0 || p.castling.indexOf('Q') >= 0;
        boolean bCastle = p.castling.indexOf('k') >= 0 || p.castling.indexOf('q') >= 0;
        if (p.epSq >= 0 && firstSide == p.side) n += 1;
        int side = firstSide;
        boolean wDone = false, bDone = false;
        for (int i = 0; i < r; i++) {
            if (side == Chess.WHITE && wCastle && !wDone) { n += 2; wDone = true; }
            if (side == Chess.BLACK && bCastle && !bDone) { n += 2; bDone = true; }
            side = 3 - side;
        }
        if (r >= 2) n += r / 2;                   // en passant after a double step inside the sequence
        return n;
    }

    private static boolean promotedIn(List<Chess.Move> acc) {
        for (Chess.Move m : acc) if (m.promo > 0) return true;
        return false;
    }

    private static long posHash(Chess.Pos p) {
        long h = 1469598103934665603L;
        for (int i = 0; i < 64; i++) { h ^= p.cb[i] + 1; h *= 1099511628211L; }
        h ^= p.side; h *= 1099511628211L;
        h ^= p.epSq + 2; h *= 1099511628211L;
        String c = p.castling;
        for (int i = 0; i < c.length(); i++) { h ^= c.charAt(i); h *= 1099511628211L; }
        return h;
    }

    private int chessIdx(int screenIdx, boolean wb) {
        int f = Board.fileIdxOf(screenIdx, wb), r = Board.rankIdxOf(screenIdx, wb);
        return (7 - r) * 8 + f;
    }

    private void dfs(Chess.Pos root, Chess.Pos p, Variant v, int r, int[] obs, boolean[] unc, float[] cf, boolean wb,
                     List<Chess.Move> acc, Map<String, Cand> out, int[] budget, boolean underPromo,
                     java.util.HashSet<Long> seen) {
        if (budget[0] <= 0) return;
        int[] cur = Chess.pattern(p, wb);
        long need = 0;
        int diff = 0;
        for (int i = 0; i < 64; i++) {
            if (!unc[i] && cur[i] != obs[i]) { diff++; need |= 1L << chessIdx(i, wb); }
        }
        if (r == 0) {
            if (diff != 0) return;
            if (!v.retyped.isEmpty()) {
                // a re-typed piece must have moved (or been captured): otherwise nothing proves its type
                int[] tok = tokensAfter(root, acc);
                for (int s : v.retyped) if (tok[s] == s) return;
            }
            float cost = 0;
            for (int i = 0; i < 64; i++) if (unc[i] && cur[i] != obs[i]) cost += cf[i];
            String key = p.key();
            Cand old = out.get(key);
            if (old == null || cost < old.cost) {
                Cand c = new Cand();
                c.pos = p;
                c.moves = new ArrayList<>(acc);
                c.cost = cost;
                c.flagsAfter = propagateAssumed(root, v.flags, acc);
                out.put(key, c);
            }
            return;
        }
        if (diff > maxFix(p, r, p.side)) return;          // squares the remaining plies could still change
        if (r >= 2 && !promotedIn(acc)) {
            // transposition: the same position with the same plies left leads to the same set of boards
            if (!seen.add(posHash(p) * 31 + r)) return;
        }
        budget[0]--;
        List<Chess.Move> moves;
        int capAfter = 0;
        if (r == 1) moves = Chess.legalCovering(p, need);       // last ply must fix everything
        else { moves = Chess.pseudoMoves(p); capAfter = maxFix(p, r - 1, 3 - p.side) + 1; }
        for (Chess.Move m : moves) {
            if (m.promo > 0 && m.promo != 5 && !underPromo) continue;
            if (r > 1 && Long.bitCount(need & ~Chess.touchedMask(p, m)) > capAfter) continue;   // cannot be repaired any more
            Chess.Pos q = p.copy();
            Chess.make(q, m);
            if (r > 1 && !Chess.kingSafe(q, p.side)) continue;
            acc.add(m);
            dfs(root, q, v, r - 1, obs, unc, cf, wb, acc, out, budget, underPromo, seen);
            acc.remove(acc.size() - 1);
            if (budget[0] <= 0) return;
        }
    }

    // ================================================================== helpers

    private static boolean matches(int[] pattern, int[] obs, boolean[] unc) {
        for (int i = 0; i < 64; i++) if (!unc[i] && pattern[i] != obs[i]) return false;
        return true;
    }

    /** two observations agree when they show the same thing on every square both are sure about */
    private boolean agrees(int[] a, float[] ca, int[] b, float[] cb) {
        for (int i = 0; i < 64; i++) {
            if (ca[i] >= certain && cb[i] >= certain && a[i] != b[i]) return false;
        }
        return true;
    }

    /** keep, per square, the reading we are more sure about */
    private static void mergeInto(int[] a, float[] ca, int[] b, float[] cb) {
        for (int i = 0; i < 64; i++) {
            if (cb[i] > ca[i]) { a[i] = b[i]; ca[i] = cb[i]; }
        }
    }

    /**
     * Seed for the "fix pieces" editor after the tracker lost the game: keep every trusted
     * piece whose square still shows the same colour on screen; elsewhere take the (weak)
     * silhouette suggestion.  Only ever shown to the user for confirmation.
     */
    public Board seedFor(int[] observed, Board guessed) {
        Board trustedBoard = Chess.toBoard(pos, whiteBottom);
        Board out = new Board();
        for (int i = 0; i < 64; i++) {
            if (observed == null || observed[i] == 0) continue;
            int tp = trustedBoard.s[i];
            boolean sameColour = (observed[i] == 1 && Board.isWhite(tp)) || (observed[i] == 2 && Board.isBlack(tp));
            if (sameColour) { out.s[i] = (byte) tp; continue; }
            int gp = guessed == null ? 0 : guessed.s[i];
            boolean gOk = (observed[i] == 1 && Board.isWhite(gp)) || (observed[i] == 2 && Board.isBlack(gp));
            if (gOk) out.s[i] = (byte) gp;
            else out.s[i] = (byte) (observed[i] == 1 ? 1 : 9);
        }
        return out;
    }
}
