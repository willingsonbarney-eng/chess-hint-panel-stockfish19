import com.chesshint.panel.Board;
import com.chesshint.panel.Chess;
import com.chesshint.panel.Track;

import java.util.List;

/** Regression tests for trusted piece identities and whole-board legal reconciliation. */
public final class TrackerRegressionTest {
    private static int passed;
    private static int failed;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name + (detail == null ? "" : " — " + detail));
        } else {
            failed++;
            System.out.println("FAIL " + name + (detail == null ? "" : " — " + detail));
        }
    }

    private static Chess.Pos fromFen(String fen) {
        String[] p = fen.trim().split("\\s+");
        Board b = Board.fromFen(fen, true);
        int side = p.length > 1 && p[1].equals("b") ? Chess.BLACK : Chess.WHITE;
        Chess.Pos pos = Chess.fromBoard(b, true, side);
        if (p.length > 2) pos.castling = p[2];
        if (p.length > 3 && !p[3].equals("-")) pos.epSq = Chess.sqFromName(p[3]);
        return pos;
    }

    private static void play(Chess.Pos pos, String uci) {
        for (Chess.Move m : Chess.legal(pos)) {
            if (m.uci().equals(uci)) {
                Chess.make(pos, m);
                return;
            }
        }
        throw new AssertionError("move is not legal: " + uci + " in " + pos.fen());
    }

    private static int[] pattern(Chess.Pos p, boolean whiteBottom) {
        return Chess.pattern(p, whiteBottom);
    }

    private static float[] confidence(float value) {
        float[] out = new float[64];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static int[] pieceCodes(Chess.Pos p, boolean whiteBottom) {
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) {
            int f = Board.fileIdxOf(i, whiteBottom);
            int r = Board.rankIdxOf(i, whiteBottom);
            out[i] = p.cb[(7 - r) * 8 + f];
        }
        return out;
    }

    private static void transition(String name, String startFen, String[] moves, boolean whiteBottom) {
        Chess.Pos initial = fromFen(startFen);
        Track tracker = new Track(initial, whiteBottom);
        Chess.Pos truth = initial.copy();
        boolean ok = true;
        String detail = "";
        for (String move : moves) {
            play(truth, move);
            Track.Update u = tracker.observe(pattern(truth, whiteBottom), confidence(1f),
                    pieceCodes(truth, whiteBottom), confidence(0.98f), truth.side);
            if (!u.ok || !tracker.pos.fen().equals(truth.fen())) {
                ok = false;
                detail = "move=" + move + ", result=" + u.info + ", tracked=" + tracker.pos.fen()
                        + ", expected=" + truth.fen();
                break;
            }
        }
        check(name, ok, detail);
    }

    public static void main(String[] args) {
        String startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

        // Same board over many noisy-free frames must not reclassify any identity.
        Chess.Pos mid = fromFen("r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 1");
        Track stable = new Track(mid, true);
        String stableFen = stable.pos.fen();
        boolean stableOk = true;
        for (int i = 0; i < 30; i++) {
            Track.Update u = stable.observe(pattern(mid, true), confidence(0.95f), pieceCodes(mid, true), confidence(0.95f));
            stableOk &= u.ok && stable.pos.fen().equals(stableFen);
        }
        check("identical frames preserve stationary piece identities", stableOk, null);

        // One low-confidence square cannot reset or reclassify a trusted piece.
        Chess.Pos initial = fromFen(startFen);
        Track noisy = new Track(initial, true);
        int[] bad = pattern(initial, true);
        float[] cf = confidence(0.9f);
        int e2 = Board.squareFromName("e2", true);
        bad[e2] = 0;
        cf[e2] = 0.04f;
        String before = noisy.pos.fen();
        Track.Update n = noisy.observe(bad, cf);
        check("single low-confidence square is ignored", n.ok && !n.changed && n.fixedSquares == 1
                && noisy.pos.fen().equals(before) && noisy.history.isEmpty(), n.info);

        transition("legal quiet move updates source/destination", startFen, new String[]{"e2e4"}, true);
        transition("legal sequence catches up after missed frames", startFen,
                new String[]{"e2e4", "c7c5", "g1f3", "d7d6"}, true);
        transition("board orientation black-bottom maps correctly", startFen, new String[]{"e2e4"}, false);

        transition("capture removes captured piece", "4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1",
                new String[]{"e4d5"}, true);
        transition("castling updates king and rook together", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
                new String[]{"e1g1"}, true);
        transition("en passant removes the bypassed pawn", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
                new String[]{"e5d6"}, true);
        transition("promotion is resolved by confident destination type", "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
                new String[]{"a7a8q"}, true);

        // Check-giving move: after the white rook moves to e7, it checks the black king.
        // The actual side-to-move is Black. Forcing White-to-move and sanitizing the board
        // would wrongly turn the checking rook into a non-checking piece.
        Chess.Pos checkStart = fromFen("4k3/8/8/8/8/8/8/K6R w - - 0 1");
        Track checking = new Track(checkStart, true);
        Chess.Pos checkedBoard = checkStart.copy();
        play(checkedBoard, "h1e1");
        Track.Update checkUpdate = checking.observe(pattern(checkedBoard, true), confidence(1f),
                pieceCodes(checkedBoard, true), confidence(0.98f), Chess.BLACK);
        int e1ChessIndex = Chess.sqFromName("e1");
        check("checking move preserves piece identity and real turn", checkUpdate.ok
                && checking.pos.side == Chess.BLACK && checking.pos.cb[e1ChessIndex] == 4,
                "piece=" + checking.pos.cb[e1ChessIndex] + ", side=" + checking.pos.side);

        Track wrongTurn = new Track(checkStart, true);
        String wrongTurnBefore = wrongTurn.pos.fen();
        Track.Update wrongTurnUpdate = wrongTurn.observe(pattern(checkedBoard, true), confidence(1f),
                pieceCodes(checkedBoard, true), confidence(0.98f), Chess.WHITE);
        check("inconsistent expected turn is rejected without mutation", !wrongTurnUpdate.ok
                && wrongTurn.pos.fen().equals(wrongTurnBefore) && wrongTurn.history.isEmpty(),
                wrongTurnUpdate.info);

        // An impossible high-confidence observation must fail without changing state/history.
        Track invalid = new Track(fromFen(startFen), true);
        String invalidBefore = invalid.pos.fen();
        int[] impossible = pattern(invalid.pos, true);
        impossible[Board.squareFromName("e2", true)] = 0;
        impossible[Board.squareFromName("e7", true)] = 0;
        Track.Update badUpdate = invalid.observe(impossible, confidence(0.99f));
        check("impossible transition is rejected transactionally", !badUpdate.ok
                && invalid.pos.fen().equals(invalidBefore) && invalid.history.isEmpty(), badUpdate.info);

        // A custom position remains custom; the tracker never silently returns to startpos.
        Chess.Pos custom = fromFen("8/8/2k5/8/3K4/8/4P3/8 w - - 0 1");
        Track customTrack = new Track(custom, true);
        String customFen = customTrack.pos.fen();
        Track.Update customSame = customTrack.observe(pattern(custom, true), confidence(1f));
        check("arbitrary valid positions are preserved", customSame.ok && customTrack.pos.fen().equals(customFen), null);

        // Multiple candidate promotion types with no type evidence must not be guessed.
        Chess.Pos promoStart = fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        Track promoUnknown = new Track(promoStart, true);
        Chess.Pos promoTruth = promoStart.copy();
        play(promoTruth, "a7a8q");
        Track.Update ambiguity = promoUnknown.observe(pattern(promoTruth, true), confidence(1f));
        check("ambiguous promotion without type evidence is not guessed", !ambiguity.ok
                && promoUnknown.pos.fen().equals(promoStart.fen()), ambiguity.info);

        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
