import com.chesshint.panel.Chess;
import com.chesshint.panel.UciEngine;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host test of the engine layer: real native library (libstockfish built by tools/build-stockfish-host.sh),
 * real UCI traffic through the same JNI bridge the phone uses.  Nothing is mocked.
 *
 *   -Dexpect.engine="Stockfish 19"   fail unless the engine identifies itself like this (used by CI)
 *   -Dengine.smoke=true              library without network: only the UCI handshake can be tested
 *
 * This is a desktop test.  It says nothing about a phone - see docs/TESTING.md for what was run where.
 */
public class EngineTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    static boolean legal(String fen, String mv) {
        Chess.Pos p = Chess.fromFen(fen);
        for (Chess.Move m : Chess.legal(p)) if (m.uci().equals(mv)) return true;
        return false;
    }

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    // forced / unique answers: lets us prove that an answer belongs to ITS position
    static final String MATE_IN_1_A = "7k/6pp/8/8/8/8/8/R6K w - - 0 1";             // a1a8#
    static final String MATE_IN_1_B = "r6k/8/8/8/8/8/6PP/7K b - - 0 1";             // a8a1#
    static final String ONLY_MOVE   = "k7/8/8/8/8/8/1r6/K7 w - - 0 1";             // Kxb2 is the only legal move

    public static void main(String[] args) throws Exception {
        boolean smoke = Boolean.getBoolean("engine.smoke");
        String expect = System.getProperty("expect.engine");
        System.out.println("=== 1. library, handshake, identity ===");
        UciEngine e = new UciEngine();
        boolean started = e.start();
        check("library loaded and engine started (uciok + readyok)", started);
        if (!started) {
            System.out.println("        " + UciEngine.libraryError());
            System.out.println("================  " + pass + " passed, " + fail + " failed  ================");
            System.exit(1);
        }
        System.out.println("        engine says: \"" + e.engineName() + "\"   library: " + UciEngine.libraryName());
        check("engine identifies itself as Stockfish", e.engineName().startsWith("Stockfish"));
        if (expect != null) check("engine version is \"" + expect + "\" (got \"" + e.engineName() + "\")", e.engineName().startsWith(expect));
        if (expect != null) check("the engine name shown in the app (\"" + UciEngine.BUNDLED_NAME + "\") is the engine that is packaged", expect.equals(UciEngine.BUNDLED_NAME));

        e.clearRaw();
        e.send("isready");
        String l; boolean ready = false;
        for (int i = 0; i < 20 && !ready; i++) { l = e.pollRaw(250); ready = "readyok".equals(l); }
        check("a second isready is answered with readyok", ready);

        if (!smoke) {
            analysis(e);
            robustness(e);
        }
        System.out.println("=== lifecycle ===");
        lifecycle(e, smoke);

        System.out.println();
        System.out.println("================  EngineTest: " + pass + " passed, " + fail + " failed  ================");
        if (fail > 0) System.exit(1);
        System.exit(0);       // engine threads are not daemon threads of the JVM
    }

    static void analysis(UciEngine e) throws Exception {
        System.out.println("=== 2. legal bestmove, answer belongs to the position ===");
        UciEngine.Result r = e.analyze(START, 7, 400, -1);
        check("start position: ok result", r.ok());
        System.out.println("        bestmove " + r.bestmove + "  depth " + r.depth + "  cp " + r.cp + "  pv " + r.pv);
        check("start position: move is legal", r.bestmove != null && legal(START, r.bestmove));
        check("result carries the analysed FEN and the caller's tag", START.equals(r.fen) && r.tag == 7);
        check("search really searched (depth > 1)", r.depth > 1);

        r = e.analyze(MATE_IN_1_A, 1, 400, -1);
        check("mate in one (white): a1a8", r.ok() && "a1a8".equals(r.bestmove));
        check("mate score reported", r.mate == 1);
        r = e.analyze(MATE_IN_1_B, 2, 400, -1);
        check("mate in one (black): a8a1", r.ok() && "a8a1".equals(r.bestmove));
        r = e.analyze(ONLY_MOVE, 3, 200, -1);
        check("the only legal move is returned", r.ok() && "a1b2".equals(r.bestmove));

        // Promotion.  The position must FORCE the promotion, otherwise the test would assert an engine preference:
        // in "8/P6k/8/8/8/8/8/K7 w" the pawn promotes in 10, but king moves such as a1b2 win by force as well, and the
        // multi-threaded search (Lazy SMP) legitimately returns one of them on some runs (measured: 1 of 30 runs with 2
        // threads at 400 ms, about half with 4 threads).  Here the white king is boxed in by the queen on f2 (not in check),
        // so the ONLY legal moves are the four promotions of the a7 pawn.
        String promo = "7k/P7/8/8/8/8/5q2/7K w - - 0 1";
        List<Chess.Move> promoMoves = Chess.legal(Chess.fromFen(promo));
        boolean forced = promoMoves.size() == 4;
        for (Chess.Move m : promoMoves) forced &= m.uci().matches("a7a8[qrbn]");
        check("promotion position: every legal move is a promotion (test premise)", forced);
        r = e.analyze(promo, 4, 400, -1);
        check("promotion position: legal move in UCI promotion notation (e.g. a7a8q)",
                r.ok() && r.bestmove.matches("[a-h][1-8][a-h][1-8][qrbn]") && legal(promo, r.bestmove));
        // the answer must stay a promotion whatever the thread count / timing of the machine running the test
        boolean stable = true; String seen = "";
        for (int th : new int[]{1, 2, 4}) {
            e.send("setoption name Threads value " + th);
            for (int i = 0; i < 3; i++) {
                r = e.analyze(promo, 40 + i, 150, -1);
                if (!(r.ok() && r.bestmove.matches("a7a8[qrbn]") && legal(promo, r.bestmove))) { stable = false; seen += " [" + th + "T:" + (r.ok() ? r.bestmove : r.error) + "]"; }
            }
        }
        e.send("setoption name Threads value " + com.chesshint.panel.Tune.EngineBudget.threads);
        check("forced promotion is a promotion with 1, 2 and 4 search threads" + seen, stable);
        // the original, non-forced position: whatever winning move the engine prefers must be legal; a pawn move to
        // the last rank is only legal WITH its promotion letter, so a bare "a7a8" could never pass
        String promoFree = "8/P6k/8/8/8/8/8/K7 w - - 0 1";
        r = e.analyze(promoFree, 45, 400, -1);
        check("non-forced promotion position: engine answer is a legal move", r.ok() && legal(promoFree, r.bestmove));
        check("a bare pawn move to the last rank (no promotion letter) is not accepted as legal", !legal(promoFree, "a7a8"));
        String castle = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1";
        r = e.analyze(castle, 5, 300, -1);
        check("castling position: legal move", r.ok() && legal(castle, r.bestmove));
        String ep = "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3";
        r = e.analyze(ep, 6, 300, -1);
        check("en passant position: legal move", r.ok() && legal(ep, r.bestmove));
        String check1 = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";
        r = e.analyze(check1, 7, 300, -1);
        check("checkmate position -> 'no legal move', no invented move", r.error != null && r.bestmove == null);
        r = e.analyze("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1", 8, 300, 1500);
        check("limited strength (UCI_Elo 1500) works", r.ok() && legal("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1", r.bestmove));

        System.out.println("=== 3. repeated analysis never mixes answers ===");
        boolean allOk = true;
        for (int i = 0; i < 24; i++) {
            String fen = (i % 3 == 0) ? MATE_IN_1_A : (i % 3 == 1 ? MATE_IN_1_B : ONLY_MOVE);
            String want = (i % 3 == 0) ? "a1a8" : (i % 3 == 1 ? "a8a1" : "a1b2");
            r = e.analyze(fen, i, 60, -1);
            if (!(r.ok() && want.equals(r.bestmove) && fen.equals(r.fen) && r.tag == i)) {
                allOk = false;
                System.out.println("        round " + i + ": " + r.bestmove + " err=" + r.error);
            }
        }
        check("24 alternating analyses: every answer matches its own FEN", allOk);
        check("no stray bestmove lines", e.strayBestmoves() == 0);
    }

    static void robustness(UciEngine e) throws Exception {
        System.out.println("=== 4. invalid input, cancel, interruption by a newer request ===");
        UciEngine.Result r = e.analyze("this is not a fen", 1, 100, -1);
        check("garbage FEN rejected without sending it", r.error != null && r.bestmove == null);
        r = e.analyze("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 extra junk ###", 1, 100, -1);
        check("trailing junk does not crash", r.error != null || r.ok());
        r = e.analyze("4k3/8/8/8/8/8/8/4K1K1 w - - 0 1", 1, 100, -1);
        check("position with two white kings rejected", r.error != null && r.bestmove == null);
        r = e.analyze("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1", 1, 100, -1);
        check("position where the side NOT to move is in check rejected", r.error != null && r.bestmove == null);

        // cancel a long search from another thread
        final AtomicReference<UciEngine.Result> res = new AtomicReference<>();
        Thread th = new Thread(() -> res.set(e.analyze(START, 11, 8000, -1)));
        long t0 = System.currentTimeMillis();
        th.start();
        Thread.sleep(500);
        e.cancel();
        th.join(5000);
        long took = System.currentTimeMillis() - t0;
        check("cancel ends an 8 s search quickly (" + took + " ms)", !th.isAlive() && took < 3000);
        check("the cancelled result is marked cancelled (never shown as an answer)", res.get() != null && res.get().cancelled && !res.get().ok());
        r = e.analyze(MATE_IN_1_A, 12, 300, -1);
        check("a new analysis after a cancel is clean", r.ok() && "a1a8".equals(r.bestmove) && MATE_IN_1_A.equals(r.fen));
        check("no stray bestmove after cancel", e.strayBestmoves() == 0);

        // a newer request while an older one is still searching
        final AtomicReference<UciEngine.Result> older = new AtomicReference<>();
        Thread t2 = new Thread(() -> older.set(e.analyze(START, 21, 6000, -1)));
        t2.start();
        Thread.sleep(400);
        e.cancel();                                  // what the app does when the position changed
        UciEngine.Result newer = e.analyze(MATE_IN_1_B, 22, 300, -1);
        t2.join(5000);
        check("newer request returns the answer for ITS position", newer.ok() && "a8a1".equals(newer.bestmove) && newer.tag == 22);
        check("older request is flagged cancelled", older.get() != null && older.get().cancelled);
        check("still no stray bestmove", e.strayBestmoves() == 0);
        e.newGame();
        r = e.analyze(START, 31, 200, -1);
        check("works after ucinewgame", r.ok() && legal(START, r.bestmove));
    }

    static void lifecycle(UciEngine e, boolean smoke) throws Exception {
        int before = e.restartCount();
        boolean again = e.restart();
        check("restart() brings the engine back (uciok/readyok again)", again && e.isAlive() && e.restartCount() == before + 1);
        if (!smoke) {
            UciEngine.Result r = e.analyze(MATE_IN_1_A, 41, 300, -1);
            check("analysis works after a restart", r.ok() && "a1a8".equals(r.bestmove));
        }
        boolean cyclesOk = true;
        for (int i = 0; i < 3; i++) {
            e.shutdown();
            if (e.isAlive()) cyclesOk = false;
            if (!e.start()) { cyclesOk = false; break; }
            if (!smoke) {
                UciEngine.Result r = e.analyze(ONLY_MOVE, 50 + i, 150, -1);
                if (!(r.ok() && "a1b2".equals(r.bestmove))) cyclesOk = false;
            }
        }
        check("3 x shutdown + start (+ analysis) cycles", cyclesOk);
        e.shutdown();
        check("shutdown() really ends the engine", !e.isAlive());
        if (!smoke) {
            UciEngine.Result r = e.analyze(START, 60, 100, -1);
            check("analyze() after shutdown starts the engine on demand", r.ok() && e.isAlive());
        }
        e.shutdown();
        check("final shutdown", !e.isAlive());
    }
}
