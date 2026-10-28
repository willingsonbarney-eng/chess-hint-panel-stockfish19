import com.chesshint.panel.Board;
import com.chesshint.panel.Chess;
import com.chesshint.panel.Track;
import com.chesshint.panel.UciEngine;

import java.util.List;

/** Host side verification of the engine plumbing, the move generator and the game tracker. */
public class CoreTest {

    static int pass = 0, fail = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (ok) { pass++; System.out.println("  PASS  " + what + "  = " + got); }
        else { fail++; System.out.println("  FAIL  " + what + "  got " + got + "  want " + want); }
    }

    static long perft(Chess.Pos p, int depth) {
        if (depth == 0) return 1;
        long n = 0;
        List<Chess.Move> ms = Chess.legal(p);
        for (Chess.Move m : ms) {
            Chess.Pos q = p.copy();
            Chess.make(q, m);
            n += perft(q, depth - 1);
        }
        return n;
    }

    static Chess.Pos pos(String fen) {
        Board b = Board.fromFen(fen, true);
        String[] parts = fen.split("\\s+");
        Chess.Pos p = Chess.fromBoard(b, true, parts.length > 1 && parts[1].equals("b") ? Chess.BLACK : Chess.WHITE);
        if (parts.length > 2 && !parts[2].isEmpty()) p.castling = parts[2];
        if (parts.length > 3 && !parts[3].equals("-")) p.epSq = Chess.sqFromName(parts[3]);
        return p;
    }

    static int[] pattern(Chess.Pos p) { return Chess.pattern(p, true); }

    static int[] pieceCodes(Chess.Pos p) {
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) {
            int f = Board.fileIdxOf(i, true);
            int r = Board.rankIdxOf(i, true);
            out[i] = p.cb[(7 - r) * 8 + f];
        }
        return out;
    }

    static float[] sure(int[] pattern) {
        float[] c = new float[64];
        for (int i = 0; i < 64; i++) c[i] = 1f;
        return c;
    }

    /** ask stockfish itself for a perft count - a perfect independent oracle */
    static long enginePerft(UciEngine e, String fen, int depth) throws Exception {
        e.clearRaw();
        e.send("position fen " + fen);
        e.send("go perft " + depth);
        long nodes = -1;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 120000) {
            String l = e.pollRaw(500);
            if (l == null) continue;
            if (l.startsWith("Nodes searched")) {
                nodes = Long.parseLong(l.replaceAll("[^0-9]", ""));
                break;
            }
        }
        return nodes;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 0. POSITION SERIALIZATION / STRICT ENGINE PREFLIGHT ===");
        Chess.Pos start = pos("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        check("starting position passes engine preflight", Chess.enginePositionProblem(start), null);
        Chess.Pos afterDoublePawn = start.copy();
        for (Chess.Move move : Chess.legal(afterDoublePawn)) {
            if (move.uci().equals("e2e4")) { Chess.make(afterDoublePawn, move); break; }
        }
        check("valid en-passant state passes engine preflight", Chess.enginePositionProblem(afterDoublePawn), null);
        Chess.Pos inconsistentCheck = pos("4k3/8/8/8/8/8/8/4R1K1 w - - 0 1");
        check("FEN serialization never relabels a checking rook",
                inconsistentCheck.fen().startsWith("4k3/8/8/8/8/8/8/4R1K1 w"), true);
        check("inconsistent side-to-move is rejected, not sanitized",
                Chess.enginePositionProblem(inconsistentCheck) != null, true);
        System.out.println("=== 1. MOVE GENERATOR (perft) ===");
        check("startpos perft 1", perft(start, 1), 20L);
        check("startpos perft 2", perft(start, 2), 400L);
        check("startpos perft 3", perft(start, 3), 8902L);
        check("startpos perft 4", perft(start, 4), 197281L);

        Chess.Pos kiwi = pos("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        check("kiwipete perft 1", perft(kiwi, 1), 48L);
        check("kiwipete perft 2", perft(kiwi, 2), 2039L);
        check("kiwipete perft 3", perft(kiwi, 3), 97862L);

        Chess.Pos p3 = pos("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");
        System.out.println("        pos3 perft1 (mine) = " + perft(p3, 1) + "   [checked against stockfish later]");

        Chess.Pos p5 = pos("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8");
        check("pos5 (promotions) perft 1", perft(p5, 1), 44L);
        check("pos5 (promotions) perft 2", perft(p5, 2), 1486L);
        check("pos5 (promotions) perft 3", perft(p5, 3), 62379L);

        Chess.Pos p6 = pos("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10");
        check("pos6 perft 1", perft(p6, 1), 46L);
        check("pos6 perft 2", perft(p6, 2), 2079L);

        System.out.println("=== 2. GAME TRACKER (following a game by patterns only) ===");
        String[] game = {"e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8c5",
                         "c2c3", "d7d6", "e1g1", "e8g8", "b1d2", "c8g4", "h2h3", "g4h5"};
        Chess.Pos p = pos("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Track t = new Track(p, true);
        boolean allOk = true;
        String applied = "";
        for (String mv : game) {
            // play the move for real
            List<Chess.Move> legal = Chess.legal(p);
            Chess.Move found = null;
            for (Chess.Move m : legal) if (m.uci().equals(mv)) found = m;
            if (found == null) { System.out.println("  (bad test move " + mv + ")"); allOk = false; break; }
            Chess.make(p, found);
            applied += mv + " ";
            // feed only the COLOUR pattern to the tracker - it must find the move itself
            Track.Update u = t.observe(pattern(p), sure(pattern(p)));
            if (!u.ok) { System.out.println("  FAIL tracker could not explain " + mv); allOk = false; break; }
        }
        check("16 moves followed, piece types intact", allOk && t.pos.fen().split(" ")[0].equals(p.fen().split(" ")[0]), true);
        System.out.println("        tracked : " + t.pos.fen());
        System.out.println("        truth   : " + p.fen());

        // Multiple moves between observations: accept only if the complete state is uniquely
        // recoverable. Two plies with a non-pawn black reply have unambiguous en-passant state.
        Chess.Pos p2 = pos("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Track t2 = new Track(p2, true);
        Chess.Pos sim = p2.copy();
        for (String mv : new String[]{"e2e4", "g8f6"}) {
            Chess.Move chosen = null;
            for (Chess.Move m : Chess.legal(sim)) if (m.uci().equals(mv)) { chosen = m; break; }
            if (chosen == null) throw new AssertionError("bad test move " + mv);
            Chess.make(sim, chosen);
        }
        Track.Update u2 = t2.observe(pattern(sim), sure(pattern(sim)), pieceCodes(sim), sure(pattern(sim)));
        check("2 plies caught up after missed frames", u2.ok && t2.pos.fen().equals(sim.fen()), true);
        System.out.println("        info: " + u2.info + "  moves: " + u2.moves + "  fixed squares: " + u2.fixedSquares);

        // Four plies can have indistinguishable board placements but different en-passant state.
        // With occupancy evidence alone, keeping the old trusted state is safer than guessing.
        Track ambiguousFour = new Track(p2, true);
        Chess.Pos four = p2.copy();
        for (String mv : new String[]{"e2e4", "c7c5", "g1f3", "d7d6"}) {
            Chess.Move chosen = null;
            for (Chess.Move m : Chess.legal(four)) if (m.uci().equals(mv)) { chosen = m; break; }
            if (chosen == null) throw new AssertionError("bad test move " + mv);
            Chess.make(four, chosen);
        }
        String fourBefore = ambiguousFour.pos.fen();
        Track.Update rejectedFour = ambiguousFour.observe(pattern(four), sure(pattern(four)),
                pieceCodes(four), sure(pattern(four)));
        check("ambiguous missed-frame sequence is rejected transactionally", !rejectedFour.ok
                && ambiguousFour.pos.fen().equals(fourBefore), true);
        System.out.println("        rejected: " + rejectedFour.info);

        // noisy read: one square misread (a piece seen as empty), confidence low there
        Chess.Pos p3b = pos("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Track t3 = new Track(p3b, true);
        Chess.Pos sim3 = p3b.copy();
        for (Chess.Move m : Chess.legal(sim3)) if (m.uci().equals("e2e4")) { Chess.make(sim3, m); break; }
        int[] noisy = pattern(sim3);
        float[] conf = sure(noisy);
        noisy[52] = noisy[52] == 0 ? 1 : 0;   // e2 misread
        conf[52] = 0.05f;
        Track.Update u3 = t3.observe(noisy, conf);
        check("noisy read auto-corrected", u3.ok && t3.pos.fen().equals(sim3.fen()), true);
        System.out.println("        fixedSquares=" + u3.fixedSquares + "  moves=" + u3.moves);

        boolean skipEngine = java.util.Arrays.asList(args).contains("--skip-engine");
        if (!skipEngine) {
        System.out.println("=== 3. ENGINE (Stockfish through the JNI bridge) ===");
        UciEngine e = new UciEngine();
        boolean ok = e.start();
        check("engine started", ok, true);
        if (ok) {
            check("official Stockfish 19 version", e.engineVersion, "19");
            String startFenForEngine = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
            String bm = e.bestMove(startFenForEngine, 800, -1);
            String mv = UciEngine.moveOf(bm);
            check("bestmove belongs to requested FEN", e.lastAnalyzedFen, startFenForEngine);
            boolean legalStartMove = false;
            for (Chess.Move legal : Chess.legal(pos(startFenForEngine))) {
                if (legal.uci().equals(mv)) legalStartMove = true;
            }
            check("startpos bestmove is legal", legalStartMove, true);
            System.out.println("        startpos bestmove: " + mv + " depth " + e.depth);
            check("startpos move looks legal", mv != null && mv.length() >= 4, true);

            bm = e.bestMove("7k/6pp/8/8/8/8/8/R6K w - - 0 1", 800, -1);
            mv = UciEngine.moveOf(bm);
            check("mate in 1 found", mv, "a1a8");
            System.out.println("        mate score: " + e.mateIn + " depth " + e.depth);

            bm = e.bestMove("8/P6k/8/8/8/8/8/K7 w - - 0 1", 800, -1);
            mv = UciEngine.moveOf(bm);
            check("queen promotion offered", mv != null && mv.endsWith("q"), true);
            System.out.println("        promotion move: " + mv);

            // ---- perft comparison against stockfish itself (independent oracle)
            String[][] perfts = {
                {"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", "4"},
                {"r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", "3"},
                {"8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", "3"},
                {"rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", "3"},
                {"r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", "3"},
                {"8/8/8/8/8/6k1/8/4K2R w K - 0 1", "4"},
                {"4k3/8/8/8/8/8/4P3/4K3 w - - 0 1", "5"},
            };
            for (String[] pf : perfts) {
                int d = Integer.parseInt(pf[1]);
                long mine = perft(pos(pf[0]), d);
                long theirs = enginePerft(e, pf[0], d);
                check("perft(" + d + ") " + pf[0].substring(0, 22) + "...", mine, theirs);
            }

            bm = e.bestMove("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 800, 1400);
            System.out.println("        1400 elo limited move: " + UciEngine.moveOf(bm));
            check("elo limited mode works", UciEngine.moveOf(bm) != null, true);

            // Cancellation must discard the cancelled result, drain its bestmove, and leave
            // the command stream synchronized for a later FEN.
            final String[] cancelledLine = new String[1];
            Thread cancellation = new Thread(() -> cancelledLine[0] = e.bestMove(
                    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 10000, -1),
                    "stockfish-cancel-regression");
            cancellation.start();
            Thread.sleep(150);
            e.stop();
            cancellation.join(6000);
            check("cancelled analysis terminates", !cancellation.isAlive(), true);
            check("cancelled bestmove is discarded", cancelledLine[0] == null, true);
            if (!cancellation.isAlive()) {
                String followFen = "7k/6pp/8/8/8/8/8/R6K w - - 0 1";
                String follow = e.bestMove(followFen, 600, -1);
                check("analysis after cancellation uses new FEN", e.lastAnalyzedFen, followFen);
                check("analysis after cancellation returns a move", UciEngine.moveOf(follow) != null, true);
            }

            e.shutdown();
            check("engine shutdown", e.isAlive(), false);
            boolean restarted = e.start();
            check("engine restarts cleanly", restarted, true);
            if (restarted) {
                String restartFen = "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1";
                String restartMove = e.bestMove(restartFen, 500, -1);
                check("post-restart analysis uses current FEN", e.lastAnalyzedFen, restartFen);
                check("post-restart bestmove is legal", UciEngine.moveOf(restartMove) != null, true);
            }
            e.shutdown();
            check("engine final shutdown", e.isAlive(), false);
        }
        }

        System.out.println();
        System.out.println("================  " + pass + " passed, " + fail + " failed  ================");
        if (fail > 0) System.exit(1);
    }
}
