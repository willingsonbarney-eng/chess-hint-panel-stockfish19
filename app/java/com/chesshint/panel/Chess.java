package com.chesshint.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, self contained chess move generator.
 *
 * It is used to follow the game that is being watched on screen:
 * the app starts from a known position (standard start, a FEN, or a position the
 * user fixed by hand) and after every screen change looks for the legal move(s)
 * that produce exactly what was seen.  That way pawns, knights, bishops, rooks,
 * queens and kings stay 100% correct even though the screen reader itself can
 * only see "empty / white piece / black piece".
 *
 * Board array here is in normal chess order: index 0 = a8 ... 63 = h1.
 */
public class Chess {

    public static final int WHITE = 1, BLACK = 2;

    public static class Move {
        public int from, to;
        public int promo;          // 0 or piece code 2..5 (knight..queen)
        public boolean ep, castle;

        public Move(int from, int to) { this.from = from; this.to = to; }

        /** uci string (in chess coordinates) */
        public String uci() {
            return sq(from) + sq(to) + (promo > 0 ? String.valueOf(" PNBRQK".charAt(promo)).toLowerCase() : "");
        }

        @Override public String toString() { return uci(); }
    }

    public static String sq(int i) {
        return "" + (char) ('a' + (i % 8)) + (char) ('1' + (7 - i / 8));
    }

    public static int sqFromName(String s) {
        if (s == null || s.length() < 2) return -1;
        int f = s.charAt(0) - 'a', r = s.charAt(1) - '1';
        if (f < 0 || f > 7 || r < 0 || r > 7) return -1;
        return (7 - r) * 8 + f;
    }

    /** position = board + side to move + en passant + castling rights */
    public static class Pos {
        public int[] cb = new int[64];
        public int side = WHITE;
        public int epSq = -1;
        public String castling = "-";

        public Pos copy() {
            Pos p = new Pos();
            System.arraycopy(cb, 0, p.cb, 0, 64);
            p.side = side; p.epSq = epSq; p.castling = castling;
            return p;
        }

        /** FEN of exactly this position - nothing is rewritten on the way (see validate()) */
        public String fen() {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 8; r++) {
                int e = 0;
                for (int f = 0; f < 8; f++) {
                    int p = cb[r * 8 + f];
                    if (p == 0) e++;
                    else {
                        if (e > 0) { sb.append(e); e = 0; }
                        sb.append(Board.pieceChar(p));
                    }
                }
                if (e > 0) sb.append(e);
                if (r < 7) sb.append('/');
            }
            sb.append(side == WHITE ? " w " : " b ");
            sb.append(feasibleCastling(cb, castling));
            sb.append(' ').append(epSq >= 0 ? sq(epSq) : "-");
            sb.append(" 0 1");
            return sb.toString();
        }

        /** identity of the position for de-duplication (board + side + castling + en passant) */
        public String key() {
            StringBuilder sb = new StringBuilder(80);
            for (int v : cb) sb.append((char) ('A' + v));
            sb.append(side).append(feasibleCastling(cb, castling)).append(epSq);
            return sb.toString();
        }

        public int king(boolean white) {
            int k = white ? 6 : 14;
            for (int i = 0; i < 64; i++) if (cb[i] == k) return i;
            return -1;
        }
    }

    // ------------------------------------------------------------------ conversion

    public static Pos fromBoard(Board b, boolean whiteBottom, int side) {
        Pos p = new Pos();
        for (int i = 0; i < 64; i++) {
            int f = Board.fileIdxOf(i, whiteBottom), r = Board.rankIdxOf(i, whiteBottom);
            p.cb[(7 - r) * 8 + f] = b.s[i];
        }
        p.side = (side == BLACK) ? BLACK : WHITE;
        p.castling = deriveCastling(p.cb);
        return p;
    }

    /**
     * Whole-position validation.  NEVER modifies the position (the old sanitize() silently
     * rewrote piece identities - pawns into bishops, extra kings into queens, attackers into
     * pawns - which is exactly how stationary pieces changed identity).  A position that fails
     * is rejected, it is not "repaired".
     *
     * @return "" when the position is a legal chess position, otherwise the first problem found
     */
    public static String validate(Pos p) {
        if (p == null || p.cb == null) return "no position";
        int[] cb = p.cb;
        int[] w = new int[7], b = new int[7];
        for (int i = 0; i < 64; i++) {
            int pc = cb[i];
            if (pc == 0) continue;
            if (pc >= 1 && pc <= 6) w[pc]++;
            else if (pc >= 9 && pc <= 14) b[pc - 8]++;
            else return "invalid piece code " + pc;
            if ((pc == 1 || pc == 9) && (i < 8 || i >= 56)) return "pawn on the first/last rank";
        }
        if (w[6] != 1) return "white needs exactly one king (found " + w[6] + ")";
        if (b[6] != 1) return "black needs exactly one king (found " + b[6] + ")";
        String sw = sideCountProblem("white", w);
        if (sw != null) return sw;
        String sb = sideCountProblem("black", b);
        if (sb != null) return sb;
        int wk = p.king(true), bk = p.king(false);
        if (kingStep(wk, bk)) return "the kings are next to each other";
        if (p.side != WHITE && p.side != BLACK) return "side to move unknown";
        int mover = p.side, other = 3 - p.side;
        int otherKing = other == WHITE ? wk : bk;
        if (attacked(cb, otherKing, mover)) return "the side that is not to move is in check";
        int myKing = mover == WHITE ? wk : bk;
        if (countAttackers(cb, myKing, other) > 2) return "king attacked by more than two pieces";
        return "";
    }

    private static String sideCountProblem(String name, int[] c) {
        int total = 0;
        for (int t = 1; t <= 6; t++) total += c[t];
        if (total > 16) return "too many " + name + " pieces (" + total + ")";
        if (c[1] > 8) return "too many " + name + " pawns (" + c[1] + ")";
        int promoted = Math.max(0, c[5] - 1) + Math.max(0, c[4] - 2)
                + Math.max(0, c[3] - 2) + Math.max(0, c[2] - 2);
        if (c[1] + promoted > 8) return "too many " + name + " pieces for the pawns that are left";
        return null;
    }

    private static int countAttackers(int[] cb, int sq, int by) {
        int n = 0;
        for (int i = 0; i < 64; i++) {
            int pc = cb[i];
            if (pc == 0 || Board.isWhite(pc) != (by == WHITE)) continue;
            if (attackedBySingle(cb, i, pc, sq)) n++;
        }
        return n;
    }

    /** does the piece standing on `from` attack `sq` on the real board? (kings count too) */
    private static boolean attackedBySingle(int[] cb, int from, int pc, int sq) {
        boolean white = Board.isWhite(pc);
        int type = white ? pc : pc - 8;
        int f0 = from % 8, r0 = from / 8, f1 = sq % 8, r1 = sq / 8;
        int df = f1 - f0, dr = r1 - r0;
        switch (type) {
            case 1: return Math.abs(df) == 1 && dr == (white ? -1 : 1);
            case 2: return (Math.abs(df) == 1 && Math.abs(dr) == 2) || (Math.abs(df) == 2 && Math.abs(dr) == 1);
            case 6: return Math.abs(df) <= 1 && Math.abs(dr) <= 1 && (df != 0 || dr != 0);
            default: break;
        }
        boolean diag = Math.abs(df) == Math.abs(dr) && df != 0;
        boolean orth = (df == 0) != (dr == 0);
        if (type == 3 && !diag) return false;
        if (type == 4 && !orth) return false;
        if (type == 5 && !diag && !orth) return false;
        int sf = Integer.compare(df, 0), sr = Integer.compare(dr, 0);
        int f = f0 + sf, r = r0 + sr;
        while (f != f1 || r != r1) {
            if (cb[r * 8 + f] != 0) return false;
            f += sf; r += sr;
        }
        return true;
    }

    /** castling rights that are still physically possible (king + rook on their home squares) */
    public static String feasibleCastling(int[] cb, String rights) {
        if (rights == null) return "-";
        StringBuilder sb = new StringBuilder();
        if (cb[60] == 6) {
            if (rights.indexOf('K') >= 0 && cb[63] == 4) sb.append('K');
            if (rights.indexOf('Q') >= 0 && cb[56] == 4) sb.append('Q');
        }
        if (cb[4] == 14) {
            if (rights.indexOf('k') >= 0 && cb[7] == 12) sb.append('k');
            if (rights.indexOf('q') >= 0 && cb[0] == 12) sb.append('q');
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** Parses a FEN (first four fields; the clocks are ignored).  Does not repair anything. */
    public static Pos fromFen(String fen) {
        if (fen == null) return null;
        String[] t = fen.trim().split("\\s+");
        if (t.length < 2) return null;
        Pos p = new Pos();
        int r = 0, f = 0;
        for (char c : t[0].toCharArray()) {
            if (c == '/') { r++; f = 0; }
            else if (c >= '1' && c <= '8') f += c - '0';
            else {
                int code = Board.fromChar(c);
                if (code == 0 || r > 7 || f > 7) return null;
                p.cb[r * 8 + f] = code;
                f++;
            }
        }
        p.side = t[1].equals("b") ? BLACK : WHITE;
        p.castling = t.length > 2 ? t[2] : "-";
        p.epSq = t.length > 3 ? sqFromName(t[3].equals("-") ? null : t[3]) : -1;
        return p;
    }

    public static Board toBoard(Pos p, boolean whiteBottom) {
        Board b = new Board();
        for (int ci = 0; ci < 64; ci++) {
            int f = ci % 8, r = 7 - ci / 8;
            b.s[Board.indexOf(f, r, whiteBottom)] = (byte) p.cb[ci];
        }
        return b;
    }

    public static String deriveCastling(int[] cb) {
        StringBuilder sb = new StringBuilder();
        if (cb[60] == 6) {
            if (cb[63] == 4) sb.append('K');
            if (cb[56] == 4) sb.append('Q');
        }
        if (cb[4] == 14) {
            if (cb[7] == 12) sb.append('k');
            if (cb[0] == 12) sb.append('q');
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ------------------------------------------------------------------ generation

    private static final int[] KNIGHT_D = {-17, -15, -10, -6, 6, 10, 15, 17};
    private static final int[] KING_D = {-9, -8, -7, -1, 1, 7, 8, 9};
    private static final int[] DIAG_D = {-9, -7, 7, 9};
    private static final int[] ORTH_D = {-8, -1, 1, 8};

    public static List<Move> legal(Pos p) {
        List<Move> out = new ArrayList<>(48);
        for (Move m : pseudo(p)) {
            Pos q = p.copy();
            make(q, m);
            int k = q.king(p.side == WHITE);
            if (k < 0 || !attacked(q.cb, k, 3 - p.side)) out.add(m);
        }
        return out;
    }

    /** pseudo-legal moves (the king may be left in check); callers must test {@link #kingSafe} */
    public static List<Move> pseudoMoves(Pos p) { return pseudo(p); }

    /** after the move was made on q: is the mover's king safe? */
    public static boolean kingSafe(Pos q, int moverSide) {
        int k = q.king(moverSide == WHITE);
        return k < 0 || !attacked(q.cb, k, 3 - moverSide);
    }

    /** bit mask (bit = chess-order square) of the squares whose colour picture a move can change */
    public static long touchedMask(Pos p, Move m) {
        long t = (1L << m.from) | (1L << m.to);
        if (m.ep) t |= 1L << (p.side == WHITE ? m.to + 8 : m.to - 8);
        if (m.castle) {
            if (m.to == 62) t |= (1L << 61) | (1L << 63);
            else if (m.to == 58) t |= (1L << 59) | (1L << 56);
            else if (m.to == 6) t |= (1L << 5) | (1L << 7);
            else if (m.to == 2) t |= (1L << 3) | (1L << 0);
        }
        return t;
    }

    /** legal moves that touch EVERY square in `need` (cheap: the king-safety test runs only for those) */
    public static List<Move> legalCovering(Pos p, long need) {
        List<Move> out = new ArrayList<>(8);
        for (Move m : pseudo(p)) {
            if ((need & ~touchedMask(p, m)) != 0) continue;
            Pos q = p.copy();
            make(q, m);
            int k = q.king(p.side == WHITE);
            if (k < 0 || !attacked(q.cb, k, 3 - p.side)) out.add(m);
        }
        return out;
    }

    private static List<Move> pseudo(Pos p) {
        List<Move> out = new ArrayList<>(64);
        int[] cb = p.cb;
        boolean white = p.side == WHITE;
        for (int i = 0; i < 64; i++) {
            int pc = cb[i];
            if (pc == 0) continue;
            boolean pcWhite = Board.isWhite(pc);
            if (pcWhite != white) continue;
            int f = i % 8, r = i / 8;
            switch (pcWhite ? pc : pc - 8) {
                case 1: { // pawn
                    int dir = white ? -1 : 1;
                    int startRank = white ? 6 : 1;
                    int promoRank = white ? 0 : 7;
                    int nr = r + dir;
                    if (nr >= 0 && nr < 8) {
                        int to = nr * 8 + f;
                        if (cb[to] == 0) {
                            if (nr == promoRank) addPromos(out, i, to);
                            else out.add(new Move(i, to));
                            if (r == startRank && cb[(r + 2 * dir) * 8 + f] == 0) {
                                out.add(new Move(i, (r + 2 * dir) * 8 + f));
                            }
                        }
                        for (int df = -1; df <= 1; df += 2) {
                            int nf = f + df;
                            if (nf < 0 || nf > 7) continue;
                            int t = nr * 8 + nf;
                            int tgt = cb[t];
                            if (tgt != 0 && Board.isWhite(tgt) != white) {
                                if (nr == promoRank) addPromos(out, i, t);
                                else out.add(new Move(i, t));
                            } else if (tgt == 0 && t == p.epSq) {
                                Move m = new Move(i, t);
                                m.ep = true;
                                out.add(m);
                            }
                        }
                    }
                    break;
                }
                case 2: // knight
                    for (int d : KNIGHT_D) {
                        int t = i + d;
                        if (t < 0 || t > 63) continue;
                        if (!knightStep(i, t)) continue;
                        if (cb[t] == 0 || Board.isWhite(cb[t]) != white) out.add(new Move(i, t));
                    }
                    break;
                case 3: // bishop
                    ray(out, cb, i, white, DIAG_D);
                    break;
                case 4: // rook
                    ray(out, cb, i, white, ORTH_D);
                    break;
                case 5: // queen
                    ray(out, cb, i, white, DIAG_D);
                    ray(out, cb, i, white, ORTH_D);
                    break;
                case 6: { // king
                    for (int d : KING_D) {
                        int t = i + d;
                        if (t < 0 || t > 63) continue;
                        if (!kingStep(i, t)) continue;
                        if (cb[t] == 0 || Board.isWhite(cb[t]) != white) out.add(new Move(i, t));
                    }
                    // castling
                    if (white && i == 60) {
                        if (cb[61] == 0 && cb[62] == 0 && cb[63] == 4 && p.castling.contains("K")
                                && !attacked(cb, 60, BLACK) && !attacked(cb, 61, BLACK)) {
                            Move m = new Move(60, 62); m.castle = true; out.add(m);
                        }
                        if (cb[59] == 0 && cb[58] == 0 && cb[57] == 0 && cb[56] == 4 && p.castling.contains("Q")
                                && !attacked(cb, 60, BLACK) && !attacked(cb, 59, BLACK)) {
                            Move m = new Move(60, 58); m.castle = true; out.add(m);
                        }
                    } else if (!white && i == 4) {
                        if (cb[5] == 0 && cb[6] == 0 && cb[7] == 12 && p.castling.contains("k")
                                && !attacked(cb, 4, WHITE) && !attacked(cb, 5, WHITE)) {
                            Move m = new Move(4, 6); m.castle = true; out.add(m);
                        }
                        if (cb[3] == 0 && cb[2] == 0 && cb[1] == 0 && cb[0] == 12 && p.castling.contains("q")
                                && !attacked(cb, 4, WHITE) && !attacked(cb, 3, WHITE)) {
                            Move m = new Move(4, 2); m.castle = true; out.add(m);
                        }
                    }
                    break;
                }
            }
        }
        return out;
    }

    private static void addPromos(List<Move> out, int from, int to) {
        for (int pc : new int[]{5, 4, 3, 2}) { // Q R B N
            Move m = new Move(from, to);
            m.promo = pc;
            out.add(m);
        }
    }

    private static void ray(List<Move> out, int[] cb, int i, boolean white, int[] dirs) {
        for (int d : dirs) {
            int df, dr;
            if (d == -9) { df = -1; dr = -1; }
            else if (d == 9) { df = 1; dr = 1; }
            else if (d == -7) { df = 1; dr = -1; }
            else if (d == 7) { df = -1; dr = 1; }
            else if (d == -8) { df = 0; dr = -1; }
            else if (d == 8) { df = 0; dr = 1; }
            else if (d == -1) { df = -1; dr = 0; }
            else { df = 1; dr = 0; }
            int nf = i % 8 + df, nr = i / 8 + dr;
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int t = nr * 8 + nf;
                int tgt = cb[t];
                if (tgt == 0) out.add(new Move(i, t));
                else {
                    if (Board.isWhite(tgt) != white) out.add(new Move(i, t));
                    break;
                }
                nf += df; nr += dr;
            }
        }
    }

    private static boolean knightStep(int a, int b) {
        int df = Math.abs(a % 8 - b % 8), dr = Math.abs(a / 8 - b / 8);
        return (df == 1 && dr == 2) || (df == 2 && dr == 1);
    }

    private static boolean kingStep(int a, int b) {
        int df = Math.abs(a % 8 - b % 8), dr = Math.abs(a / 8 - b / 8);
        return df <= 1 && dr <= 1 && (df + dr) > 0;
    }

    public static boolean attacked(int[] cb, int sq, int by) {
        int f = sq % 8, r = sq / 8;
        // pawns
        int pr = by == WHITE ? r + 1 : r - 1;   // where an attacking pawn would stand
        int pawn = by == WHITE ? 1 : 9;
        if (pr >= 0 && pr < 8) {
            for (int df = -1; df <= 1; df += 2) {
                int nf = f + df;
                if (nf < 0 || nf > 7) continue;
                if (cb[pr * 8 + nf] == pawn) return true;
            }
        }
        // knights
        int kn = by == WHITE ? 2 : 10;
        for (int d : KNIGHT_D) {
            int t = sq + d;
            if (t < 0 || t > 63) continue;
            if (knightStep(sq, t) && cb[t] == kn) return true;
        }
        // king
        int kg = by == WHITE ? 6 : 14;
        for (int d : KING_D) {
            int t = sq + d;
            if (t < 0 || t > 63) continue;
            if (kingStep(sq, t) && cb[t] == kg) return true;
        }
        // sliders
        int bi = by == WHITE ? 3 : 11, ro = by == WHITE ? 4 : 12, qu = by == WHITE ? 5 : 13;
        int[][] dirs = {{1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] d : dirs) {
            int nf = f + d[0], nr = r + d[1];
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int p = cb[nr * 8 + nf];
                if (p != 0) {
                    if (p == bi || p == qu) return true;
                    break;
                }
                nf += d[0]; nr += d[1];
            }
        }
        int[][] dirs2 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs2) {
            int nf = f + d[0], nr = r + d[1];
            while (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) {
                int p = cb[nr * 8 + nf];
                if (p != 0) {
                    if (p == ro || p == qu) return true;
                    break;
                }
                nf += d[0]; nr += d[1];
            }
        }
        return false;
    }

    /** apply a move and update side / en-passant / castling state */
    public static void make(Pos p, Move m) {
        int[] cb = p.cb;
        int pc = cb[m.from];
        int cap = cb[m.to];
        cb[m.to] = pc;
        cb[m.from] = 0;
        if (m.ep) {
            int capSq = p.side == WHITE ? m.to + 8 : m.to - 8;
            cb[capSq] = 0;
        }
        if (m.castle) {
            if (m.to == 62) { cb[61] = cb[63]; cb[63] = 0; }
            else if (m.to == 58) { cb[59] = cb[56]; cb[56] = 0; }
            else if (m.to == 6) { cb[5] = cb[7]; cb[7] = 0; }
            else if (m.to == 2) { cb[3] = cb[0]; cb[0] = 0; }
        }
        int type = Board.isWhite(pc) ? pc : pc - 8;
        if (m.promo > 0) cb[m.to] = (byte) (Board.isWhite(pc) ? m.promo : m.promo + 8);
        // en passant square for the next move
        if (type == 1 && Math.abs(m.to - m.from) == 16) p.epSq = (m.from + m.to) / 2;
        else p.epSq = -1;
        // castling rights
        String c = p.castling;
        if (type == 6) {
            c = Board.isWhite(pc) ? c.replace("K", "").replace("Q", "") : c.replace("k", "").replace("q", "");
        }
        if (m.from == 63 || m.to == 63) c = c.replace("K", "");
        if (m.from == 56 || m.to == 56) c = c.replace("Q", "");
        if (m.from == 7 || m.to == 7) c = c.replace("k", "");
        if (m.from == 0 || m.to == 0) c = c.replace("q", "");
        if (c.isEmpty()) c = "-";
        p.castling = c;
        p.side = 3 - p.side;
    }

    /** colour pattern of a position (0 empty, 1 white, 2 black) in screen order */
    public static int[] pattern(Pos p, boolean whiteBottom) {
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) {
            int f = Board.fileIdxOf(i, whiteBottom), r = Board.rankIdxOf(i, whiteBottom);
            int pc = p.cb[(7 - r) * 8 + f];
            out[i] = pc == 0 ? 0 : (Board.isWhite(pc) ? 1 : 2);
        }
        return out;
    }

    public static boolean inCheck(Pos p) {
        int k = p.king(p.side == WHITE);
        return k >= 0 && attacked(p.cb, k, 3 - p.side);
    }
}
