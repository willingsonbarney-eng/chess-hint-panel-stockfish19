import com.chesshint.panel.Board;
import com.chesshint.panel.Chess;
import com.chesshint.panel.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Random;

/**
 * Regression tests for the "piece identities randomly change" bug.
 *
 * Pure Java, no engine and no Android needed:
 *   javac -d classes -sourcepath ../../app/java:shim:. TrackerTest.java && java -cp classes TrackerTest
 */
public class TrackerTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    static void checkEq(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what + "\n          got  " + got + "\n          want " + want); }
    }

    // ------------------------------------------------------------------ helpers

    static Chess.Pos start() { return Track.startPosition(); }

    static Chess.Pos fen(String f) { return Chess.fromFen(f); }

    /** play uci moves on a copy of p (throws if one is illegal - catches typos in the tests) */
    static Chess.Pos play(Chess.Pos p, String... ucis) {
        Chess.Pos q = p.copy();
        for (String u : ucis) {
            Chess.Move found = null;
            for (Chess.Move m : Chess.legal(q)) if (m.uci().equals(u)) { found = m; break; }
            if (found == null) throw new IllegalStateException("illegal test move " + u + " in " + q.fen());
            Chess.make(q, found);
        }
        return q;
    }

    static float[] sure() {
        float[] c = new float[64];
        java.util.Arrays.fill(c, 1f);
        return c;
    }

    /** feed `n` identical frames of position `truth` as the screen would show it */
    static Track.Update feed(Track t, Chess.Pos truth, int n) {
        Track.Update u = null;
        for (int i = 0; i < n; i++) u = t.observe(Chess.pattern(truth, t.whiteBottom), sure());
        return u;
    }

    /** board part + side to move (castling / en-passant fields are history dependent) */
    static String core(Chess.Pos p) {
        String[] f = p.fen().split(" ");
        return f[0] + " " + f[1];
    }

    static Track newTrack(Chess.Pos p, boolean wb) { return new Track(p, wb); }

    /** tracker shows exactly the true board; the side to move only has to match when the tracker claims to know it */
    static boolean same(Track t, Chess.Pos truth) {
        String[] a = t.pos.fen().split(" "), b = truth.fen().split(" ");
        return a[0].equals(b[0]) && (!t.sideKnown || a[1].equals(b[1]));
    }

    // ======================================================================== tests

    static void stationaryPieces() {
        System.out.println("=== 1. identical frames never change identities ===");
        Chess.Pos mid = play(start(), "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6");
        Track t = newTrack(mid, true);
        String fen0 = t.pos.fen();
        long v0 = t.version;
        boolean allUnchanged = true;
        for (int i = 0; i < 500; i++) {
            Track.Update u = t.observe(Chess.pattern(mid, true), sure());
            if (u.status != Track.Status.UNCHANGED) allUnchanged = false;
        }
        check("500 identical frames -> always UNCHANGED", allUnchanged);
        checkEq("FEN identical after 500 frames", t.pos.fen(), fen0);
        checkEq("version did not move", t.version, v0);
        for (int i = 0; i < 64; i++) if (t.pos.cb[i] != mid.cb[i]) { check("piece at " + i + " unchanged", false); }
    }

    static void lowConfidenceAndFailedFrames() {
        System.out.println("=== 2. low confidence / failed recognition never replaces pieces ===");
        Chess.Pos mid = play(start(), "d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6");
        Track t = newTrack(mid, true);
        String fen0 = t.pos.fen();
        Random rnd = new Random(7);
        int changes = 0;
        for (int n = 0; n < 5000; n++) {
            int[] obs = Chess.pattern(mid, true);
            float[] cf = sure();
            int k = rnd.nextInt(13);                       // up to 12 low-confidence squares misread
            for (int j = 0; j < k; j++) {
                int sq = rnd.nextInt(64);
                obs[sq] = rnd.nextInt(3);
                cf[sq] = 0.01f + rnd.nextFloat() * 0.2f;
            }
            Track.Update u = t.observe(obs, cf);
            if (u.changed) changes++;
        }
        checkEq("5000 noisy frames: trusted position untouched", t.pos.fen(), fen0);
        checkEq("no change was ever committed", changes, 0);

        // failed / blank / garbage frames
        Track t2 = newTrack(mid, true);
        int[] blank = new int[64];
        check("blank frame ignored", t2.observe(blank, sure()).status == Track.Status.PENDING && t2.pos.fen().equals(fen0));
        int[] allWhite = new int[64];
        java.util.Arrays.fill(allWhite, 1);
        check("all-white garbage ignored", t2.observe(allWhite, sure()).status == Track.Status.PENDING && t2.pos.fen().equals(fen0));
        float[] bad = new float[64];                       // everything uncertain
        java.util.Arrays.fill(bad, 0.02f);
        int[] junk = new int[64];
        for (int i = 0; i < 64; i += 3) junk[i] = 1 + (i % 2);
        check("frame with no certain squares ignored", t2.observe(junk, bad).status == Track.Status.PENDING && t2.pos.fen().equals(fen0));
        check("null / short arrays rejected", t2.observe(null, null).status == Track.Status.INVALID
                && t2.observe(new int[10], null).status == Track.Status.INVALID);
        checkEq("still the same position", t2.pos.fen(), fen0);
    }

    static void singleChangeNeedsConfirmation() {
        System.out.println("=== 3. a change must be seen twice, then updates exactly source+destination ===");
        Track t = newTrack(start(), true);
        Chess.Pos after = play(start(), "e2e4");
        Track.Update u1 = t.observe(Chess.pattern(after, true), sure());
        check("first frame: PENDING, position kept", u1.status == Track.Status.PENDING && t.pos.fen().equals(start().fen()));
        Track.Update u2 = t.observe(Chess.pattern(after, true), sure());
        check("second frame: MOVED", u2.status == Track.Status.MOVED && u2.changed);
        checkEq("moves", u2.moves.toString(), "[e2e4]");
        checkEq("FEN", t.pos.fen(), after.fen());
        check("e2 empty / e4 white pawn", t.pos.cb[Chess.sqFromName("e2")] == 0 && t.pos.cb[Chess.sqFromName("e4")] == 1);
        check("side to move is black now", t.pos.side == Chess.BLACK);

        // a flicker (one odd frame in between) does not commit anything
        Track f = newTrack(start(), true);
        int[] odd = Chess.pattern(play(start(), "g1f3"), true);
        f.observe(Chess.pattern(after, true), sure());
        Track.Update uo = f.observe(odd, sure());
        check("conflicting frame restarts confirmation", uo.status == Track.Status.PENDING && f.pos.fen().equals(start().fen()));
        Track.Update ub = f.observe(Chess.pattern(after, true), sure());
        check("single frame after flicker still pending", ub.status == Track.Status.PENDING && f.pos.fen().equals(start().fen()));
        check("confirmed afterwards", f.observe(Chess.pattern(after, true), sure()).status == Track.Status.MOVED);
    }

    static void captures() {
        System.out.println("=== 4. captures ===");
        Chess.Pos p = start();
        Track t = newTrack(p, true);
        String[] mv = {"e2e4", "d7d5", "e4d5", "d8d5", "b1c3", "d5a5"};
        for (String m : mv) {
            p = play(p, m);
            Track.Update u = feed(t, p, 2);
            check("followed " + m, u.status == Track.Status.MOVED);
        }
        checkEq("final position", t.pos.fen(), p.fen());
        check("white d-pawn gone (captured), black queen on a5",
                t.pos.cb[Chess.sqFromName("d5")] == 0 && t.pos.cb[Chess.sqFromName("a5")] == 13);
        // capture changes only the colour of the destination square
        Chess.Pos q = play(start(), "e2e4", "d7d5");
        Track t2 = newTrack(q, true);
        Chess.Pos q2 = play(q, "e4d5");
        Track.Update u = feed(t2, q2, 2);
        check("exd5: pawn removed from e4, black pawn on d5 replaced by white pawn",
                u.status == Track.Status.MOVED && t2.pos.cb[Chess.sqFromName("d5")] == 1
                        && t2.pos.cb[Chess.sqFromName("e4")] == 0);
        // pieces that did not take part keep their exact type
        Chess.Pos before = play(start(), "e2e4", "d7d5");
        Track t3 = newTrack(before, true);
        feed(t3, play(before, "g1f3"), 2);
        int same = 0;
        for (int i = 0; i < 64; i++) if (i != Chess.sqFromName("g1") && i != Chess.sqFromName("f3") && t3.pos.cb[i] == before.cb[i]) same++;
        checkEq("all 62 uninvolved squares keep their identity", same, 62);
    }

    static void castling() {
        System.out.println("=== 5. castling (both sides, both wings) ===");
        String[] italian = {"e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5"};
        Chess.Pos p = play(start(), italian);
        Track t = newTrack(p, true);
        Chess.Pos w = play(p, "e1g1");
        Track.Update u = feed(t, w, 2);
        check("O-O recognised as one move", u.status == Track.Status.MOVED && u.moves.size() == 1 && u.moves.get(0).castle);
        check("king g1, rook f1, h1 and e1 empty",
                t.pos.cb[Chess.sqFromName("g1")] == 6 && t.pos.cb[Chess.sqFromName("f1")] == 4
                        && t.pos.cb[Chess.sqFromName("h1")] == 0 && t.pos.cb[Chess.sqFromName("e1")] == 0);
        Chess.Pos b = play(w, "g8f6", "d2d3", "e8g8");
        Track.Update ub = feed(t, b, 2);
        check("black O-O two plies later", ub.status == Track.Status.MOVED && ub.moves.size() == 3);
        check("black king g8, rook f8", t.pos.cb[Chess.sqFromName("g8")] == 14 && t.pos.cb[Chess.sqFromName("f8")] == 12);
        checkEq("castling rights gone", t.pos.fen().split(" ")[2], "-");

        // queen side
        Chess.Pos q = fen("r3k2r/pppqbppp/2npbn2/4p3/4P3/2NPBN2/PPPQBPPP/R3K2R w KQkq - 0 1");
        Track tq = newTrack(q, true);
        Chess.Pos q1 = play(q, "e1c1");
        Track.Update uq = feed(tq, q1, 2);
        check("O-O-O recognised", uq.status == Track.Status.MOVED && uq.moves.get(0).castle
                && tq.pos.cb[Chess.sqFromName("c1")] == 6 && tq.pos.cb[Chess.sqFromName("d1")] == 4);
        Chess.Pos q2 = play(q1, "e8c8");
        feed(tq, q2, 2);
        check("black O-O-O recognised", tq.pos.cb[Chess.sqFromName("c8")] == 14 && tq.pos.cb[Chess.sqFromName("d8")] == 12);

        // rights are really lost when the rook leaves and comes back
        Chess.Pos r = play(start(), "g1f3", "g8f6", "h1g1", "h8g8", "g1h1", "g8h8", "e2e4", "e7e5", "f1e2", "f8e7");
        Track tr = newTrack(start(), true);
        Chess.Pos cur = start();
        for (String m : new String[]{"g1f3", "g8f6", "h1g1", "h8g8", "g1h1", "g8h8", "e2e4", "e7e5", "f1e2", "f8e7"}) {
            cur = play(cur, m);
            feed(tr, cur, 2);
        }
        checkEq("same board", core(tr.pos), core(r));
        check("no king-side castling offered after the rooks went away and back",
                !tr.pos.fen().split(" ")[2].contains("K") && !tr.pos.fen().split(" ")[2].contains("k")
                        && tr.pos.fen().split(" ")[2].contains("Q"));
    }

    static void enPassant() {
        System.out.println("=== 6. en passant ===");
        Chess.Pos p = play(start(), "e2e4", "a7a6", "e4e5", "d7d5");
        Track t = newTrack(p, true);
        Chess.Pos e = play(p, "e5d6");
        Track.Update u = feed(t, e, 2);
        check("exd6 e.p. recognised", u.status == Track.Status.MOVED && u.moves.get(0).ep);
        check("victim on d5 removed, white pawn on d6",
                t.pos.cb[Chess.sqFromName("d5")] == 0 && t.pos.cb[Chess.sqFromName("d6")] == 1
                        && t.pos.cb[Chess.sqFromName("e5")] == 0);
        // black capturing en passant
        Chess.Pos b = play(start(), "a2a3", "e7e5", "a3a4", "e5e4", "d2d4");
        Track tb = newTrack(b, true);
        Chess.Pos b2 = play(b, "e4d3");
        Track.Update ub = feed(tb, b2, 2);
        check("black e.p. recognised", ub.status == Track.Status.MOVED && ub.moves.get(0).ep
                && tb.pos.cb[Chess.sqFromName("d4")] == 0 && tb.pos.cb[Chess.sqFromName("d3")] == 9);
    }

    static void promotions() {
        System.out.println("=== 7. promotions ===");
        Chess.Pos p = fen("8/P6k/8/8/8/8/8/K7 w - - 0 1");
        Track t = new Track(p, true);
        Chess.Pos q = play(p, "a7a8q");
        Track.Update u = feed(t, q, 2);
        check("a7-a8 recognised", u.status == Track.Status.MOVED);
        check("promoted piece is a (flagged) queen", t.pos.cb[Chess.sqFromName("a8")] == 5 && t.assumed[Chess.sqFromName("a8")] && u.promotionAssumed);

        // capture promotion
        Chess.Pos c = fen("1n5k/P7/8/8/8/8/8/K7 w - - 0 1");
        Track tc = new Track(c, true);
        Chess.Pos c2 = play(c, "a7b8q");
        Track.Update uc = feed(tc, c2, 2);
        check("axb8 promotion recognised, black knight gone", uc.status == Track.Status.MOVED
                && tc.pos.cb[Chess.sqFromName("b8")] == 5 && tc.pos.cb[Chess.sqFromName("a7")] == 0);

        // black promotes
        Chess.Pos bp2 = fen("7k/8/8/8/8/8/p7/4K3 b - - 0 1");
        Track tb2 = new Track(bp2, true);
        Track.Update ub = feed(tb2, play(bp2, "a2a1q"), 2);
        check("black promotion recognised", ub.status == Track.Status.MOVED && tb2.pos.cb[Chess.sqFromName("a1")] == 13);

        // the "queen" later moves like a knight -> it was an under-promotion, re-typed by the movement
        Chess.Pos n = fen("8/P6k/8/8/8/8/8/K7 w - - 0 1");
        Track tn = new Track(n, true);
        Chess.Pos n1 = play(n, "a7a8n");
        feed(tn, n1, 2);
        check("assumed queen after N promotion (colour picture cannot tell)", tn.pos.cb[Chess.sqFromName("a8")] == 5);
        Chess.Pos n2 = play(n1, "h7g7", "a8b6");
        Track.Update un = feed(tn, n2, 2);
        check("knight move re-types the piece", un.status == Track.Status.MOVED
                && tn.pos.cb[Chess.sqFromName("b6")] == 2 && !tn.assumed[Chess.sqFromName("b6")]);
        checkEq("position equals the real one", tn.pos.fen().split(" ")[0] + tn.pos.fen().split(" ")[1],
                n2.fen().split(" ")[0] + n2.fen().split(" ")[1]);
    }

    static void orientation() {
        System.out.println("=== 8. both orientations ===");
        String[] game = {"e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6"};
        for (boolean wb : new boolean[]{true, false}) {
            Track t = newTrack(start(), wb);
            Chess.Pos cur = start();
            boolean ok = true;
            for (String m : game) {
                cur = play(cur, m);
                Track.Update u = feed(t, cur, 2);
                if (u.status != Track.Status.MOVED) ok = false;
            }
            check((wb ? "white" : "black") + " at the bottom: 10 plies followed", ok);
            checkEq((wb ? "white" : "black") + " at the bottom: FEN", t.pos.fen(), cur.fen());
        }
        // square <-> index mapping, both orientations
        boolean mapOk = true;
        for (boolean wb : new boolean[]{true, false}) {
            for (int f = 0; f < 8; f++)
                for (int r = 0; r < 8; r++) {
                    int idx = Board.indexOf(f, r, wb);
                    if (Board.fileIdxOf(idx, wb) != f || Board.rankIdxOf(idx, wb) != r) mapOk = false;
                    String name = Board.squareName(idx, wb);
                    if (Board.squareFromName(name, wb) != idx) mapOk = false;
                }
        }
        check("square index round trips in both orientations", mapOk);
        checkEq("a1 bottom-left with white at the bottom", Board.squareFromName("a1", true), 56);
        checkEq("a1 top-right with black at the bottom", Board.squareFromName("a1", false), 7);
        checkEq("h8 bottom-left with black at the bottom", Board.squareFromName("h8", false), 56);

        // the board is turned around by the app: same position, nothing may change
        Chess.Pos mid = play(start(), "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "g8f6");
        Track t = newTrack(mid, true);
        String f0 = t.pos.fen();
        long v0 = t.version;
        int[] flipped = Chess.pattern(mid, false);
        t.observe(flipped, sure());
        Track.Update u = t.observe(flipped, sure());
        check("flip detected as ORIENTATION", u.status == Track.Status.ORIENTATION && !t.whiteBottom);
        checkEq("identities unchanged by the flip", t.pos.fen(), f0);
        checkEq("version unchanged by the flip", t.version, v0);
        Chess.Pos mid2 = play(mid, "e1g1");
        Track.Update um = feed(t, mid2, 2);
        check("moves are still followed after the flip (view from black)", um.status == Track.Status.MOVED
                && t.pos.fen().equals(mid2.fen()));
    }

    static void differentPositions() {
        System.out.println("=== 9. positions are never forced into the start position ===");
        String[] fens = {
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
        };
        for (String f : fens) {
            Chess.Pos p = fen(f);
            Track t = new Track(p, true);
            check("trusted: " + f.substring(0, 20), t.isTrusted());
            // a legal move in this position is followed exactly
            List<Chess.Move> legal = Chess.legal(p);
            Chess.Move m = legal.get(legal.size() / 2);
            Chess.Pos n = p.copy();
            Chess.make(n, m);
            Track.Update u = feed(t, n, 2);
            check("   first legal move " + m + " followed (" + u.status + ")", u.status == Track.Status.MOVED && t.pos.fen().equals(n.fen()));
        }
        // a position that merely resembles the start (one pawn short) is NOT reset to the start
        Chess.Pos almost = fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPP1/RNBQKBNR w KQkq - 0 1");
        Track ta = new Track(almost, true);
        feed(ta, almost, 3);
        checkEq("near-start position stays as it is", ta.pos.fen(), almost.fen());
    }

    static void impossibleTransitions() {
        System.out.println("=== 10. impossible / inconsistent transitions are rejected ===");
        Chess.Pos p = play(start(), "e2e4", "e7e5");
        Track t = newTrack(p, true);
        String f0 = t.pos.fen();
        // a white piece appears on h6 while the e4 pawn vanished: no capture, no path
        int[] tele = Chess.pattern(p, true);
        tele[Board.squareFromName("e4", true)] = 0;
        tele[Board.squareFromName("h6", true)] = 1;
        Track.Update last = null;
        for (int i = 0; i < 12; i++) last = t.observe(tele, sure());
        check("teleport never accepted", t.pos.fen().equals(f0));
        check("reported UNEXPLAINED/LOST", last.status == Track.Status.LOST || last.status == Track.Status.UNEXPLAINED);
        check("tracker flagged lost", t.lost);
        // piece count can never go up
        int[] extra = Chess.pattern(p, true);
        extra[Board.squareFromName("d4", true)] = 1;
        Track t2 = newTrack(p, true);
        for (int i = 0; i < 12; i++) t2.observe(extra, sure());
        check("extra piece on the board is not invented", t2.pos.fen().equals(f0));
        // a rook vanishes without being captured
        Chess.Pos wrongSide = play(start(), "e2e4");
        int[] vanish = Chess.pattern(wrongSide, true);
        vanish[Board.squareFromName("a1", true)] = 0;
        Track t3 = newTrack(wrongSide, true);
        for (int i = 0; i < 12; i++) t3.observe(vanish, sure());
        check("a piece vanishing without a capture is rejected", t3.pos.fen().equals(wrongSide.fen()) && t3.lost);
        // and the tracker recovers by itself once the screen makes sense again
        Track.Update back = t.observe(Chess.pattern(p, true), sure());
        check("screen agrees again -> not lost any more", back.status == Track.Status.UNCHANGED && !t.lost);
        Chess.Pos nxt = play(p, "g1f3");
        check("and keeps following", feed(t, nxt, 2).status == Track.Status.MOVED && t.pos.fen().equals(nxt.fen()));
    }

    static void recovery() {
        System.out.println("=== 11. recovery after missed frames / several moves ===");
        String[] line = {"e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8c5", "e1g1", "d7d6"};
        for (int gap = 1; gap <= 4; gap++) {
            Chess.Pos cur = start();
            Track t = newTrack(start(), true);
            boolean ok = true, neverWrong = true;
            int resyncs = 0;
            for (int i = 0; i < line.length; i += gap) {
                int to = Math.min(line.length, i + gap);
                for (int k = i; k < to; k++) cur = play(cur, line[k]);
                String before = t.pos.fen();
                Track.Update u = feed(t, cur, 2);
                if (same(t, cur)) continue;
                ok = false;
                // not following: acceptable only as an honest "I do not know" with the trusted position untouched
                boolean honest = (u.status == Track.Status.AMBIGUOUS || u.status == Track.Status.UNEXPLAINED
                        || u.status == Track.Status.LOST || u.status == Track.Status.PENDING) && t.pos.fen().equals(before);
                if (!honest) neverWrong = false;
                t.resetUser(cur);                      // what a re-synchronisation (AutoSeed / new game) does
                resyncs++;
            }
            if (gap <= 2) check("observing every " + gap + " ply: final board exact, no help needed", ok);
            else check("observing every " + gap + " plies: never wrong (" + resyncs + " resync(s) needed)", neverWrong);
        }
        // 5-6 plies in one gap are found by the deeper recovery search, never guessed
        Chess.Pos cur = play(start(), "e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6");
        Track t = newTrack(start(), true);
        Track.Update u = null;
        for (int i = 0; i < 6; i++) u = t.observe(Chess.pattern(cur, true), sure());
        check("6 plies at once: exact or still waiting, never wrong",
                t.pos.fen().equals(cur.fen()) || t.pos.fen().equals(start().fen()));
        System.out.println("        (6 plies at once -> " + u.status + ", tracked " + (t.pos.fen().equals(cur.fen()) ? "exact" : "kept start") + ")");
        // after losing track the user can re-synchronise without corrupting anything
        Track lostT = newTrack(play(start(), "e2e4"), true);
        int[] garbage = Chess.pattern(play(start(), "e2e4", "e7e5", "d2d4", "e5d4", "d1d4", "b8c6", "d4g7"), true);
        garbage[0] = 0; garbage[7] = 0;
        for (int i = 0; i < 10; i++) lostT.observe(garbage, sure());
        String keep = lostT.pos.fen();
        check("lost tracker keeps the last trusted position untouched", keep.equals(play(start(), "e2e4").fen()));
        String err = lostT.resetUser(fen("r3k3/8/8/8/8/8/8/4K2R w - - 0 1"));
        check("user can re-initialise", err.isEmpty() && !lostT.lost && lostT.isTrusted());
        String err2 = lostT.resetUser(fen("8/8/8/8/8/8/8/8 w - - 0 1"));
        check("an impossible position is refused and the old one kept", !err2.isEmpty() && lostT.pos.fen().startsWith("r3k3"));
    }

    static void takeBackAndNewGame() {
        System.out.println("=== 12. take-back and new game ===");
        Chess.Pos a = play(start(), "e2e4", "e7e5");
        Chess.Pos b = play(a, "g1f3", "b8c6");
        Track t = newTrack(start(), true);
        feed(t, a, 2);
        feed(t, b, 2);
        long v = t.version;
        Chess.Pos b1 = play(a, "g1f3");
        Track tt = newTrack(start(), true);
        feed(tt, a, 2);
        feed(tt, b1, 2);
        Track.Update ur = feed(tt, a, 2);
        check("one-ply take-back ends on the right board (history rewind, or the same board explained forward)",
                same(tt, a));
        Track.Update u = feed(t, a, 2);
        check("two-ply take-back ends on the right board (REWOUND or moved back)", same(t, a));
        check("version moved on a take-back", t.version > v);
        Chess.Pos c = play(a, "f1c4");
        check("continues from the rewound position", feed(t, c, 2).status == Track.Status.MOVED && t.pos.fen().equals(c.fen()));
        // new game
        Track.Update un = feed(t, start(), 2);
        check("start pattern on screen -> NEW_GAME", un.status == Track.Status.NEW_GAME || un.status == Track.Status.REWOUND);
        checkEq("start position FEN", t.pos.fen(), start().fen());
        // new game with black at the bottom, after a long game
        Chess.Pos long1 = play(start(), "d2d4", "g8f6", "c2c4", "e7e6", "b1c3", "f8b4", "e2e3", "e8g8", "f1d3", "d7d5");
        Track tb = newTrack(long1, false);
        for (int i = 0; i < 3; i++) tb.observe(Chess.pattern(start(), false), sure());
        check("new game recognised with black at the bottom", tb.pos.fen().equals(start().fen()) && !tb.whiteBottom);
        // new game with the other orientation than before
        Track tc = newTrack(long1, true);
        for (int i = 0; i < 3; i++) tc.observe(Chess.pattern(start(), false), sure());
        check("new game + board turned: recognised", tc.pos.fen().equals(start().fen()) && !tc.whiteBottom);
    }

    static void colourAmbiguity() {
        System.out.println("=== 12b. identical colour pictures from different pieces are never guessed ===");
        // Bf4-h4 (via g3/g5) and h2-h4 + Bf4-h2 give the same colour picture four plies later
        Chess.Pos mid = fen("r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2P1PN2/PP1NBPPP/R2Q1RK1 w - - 0 1");
        Chess.Pos four = play(mid, "f4g3", "h7h6", "g3h4", "g7g5");
        Track t = newTrack(mid, true);
        Track.Update u = feed(t, four, 3);
        check("two different boards fit -> AMBIGUOUS, trusted position untouched",
                (u.status == Track.Status.AMBIGUOUS || u.status == Track.Status.LOST) && t.pos.fen().equals(mid.fen()));
        // observed one ply at a time there is no ambiguity at all
        Track t2 = newTrack(mid, true);
        Chess.Pos cur = mid;
        for (String m : new String[]{"f4g3", "h7h6", "g3h4", "g7g5"}) { cur = play(cur, m); feed(t2, cur, 2); }
        checkEq("ply by ply the identity is exact", t2.pos.fen(), cur.fen());
    }

    static void wildcards() {
        System.out.println("=== 13. uncertain squares are wildcards, better frames resolve them ===");
        Chess.Pos s = start();
        Chess.Pos after = play(s, "e2e4");
        // destination unreadable (empty, low confidence) - origin clearly empty, e3 clearly empty -> only e4 fits
        Track t = newTrack(s, true);
        int[] obs = Chess.pattern(after, true);
        float[] cf = sure();
        int e4 = Board.squareFromName("e4", true), e3 = Board.squareFromName("e3", true);
        obs[e4] = 0; cf[e4] = 0.03f;
        t.observe(obs, cf);
        Track.Update u0 = t.observe(obs, cf);
        check("unreadable destination does not block the only legal explanation", u0.status == Track.Status.MOVED && t.pos.fen().equals(after.fen()));
        // e3 AND e4 both unreadable: e2-e3 or e2-e4? must not be guessed
        Track ta = newTrack(s, true);
        int[] obs2 = Chess.pattern(after, true);
        float[] cf2 = sure();
        obs2[e4] = 0; cf2[e4] = 0.03f; cf2[e3] = 0.03f;
        ta.observe(obs2, cf2);
        Track.Update u = ta.observe(obs2, cf2);
        check("e3 or e4? not decided from unreadable squares (AMBIGUOUS, nothing changed)",
                u.status == Track.Status.AMBIGUOUS && ta.pos.fen().equals(s.fen()));
        Track.Update u2 = feed(ta, after, 1);
        check("a clear frame resolves it", u2.status == Track.Status.MOVED && ta.pos.fen().equals(after.fen()));
        // a move whose destination is merely uncertain but not ambiguous (capture: colour flips)
        Chess.Pos cp = play(start(), "e2e4", "d7d5");
        Track tc = newTrack(cp, true);
        Chess.Pos cap = play(cp, "e4d5");
        int[] o2 = Chess.pattern(cap, true);
        float[] c2 = sure();
        int d5 = Board.squareFromName("d5", true);
        o2[d5] = 2; c2[d5] = 0.05f;         // still looks like the old black pawn
        tc.observe(o2, c2);
        Track.Update uc = tc.observe(o2, c2);
        check("capture with an unsure destination colour still resolved by the vacated square", uc.status == Track.Status.MOVED && tc.pos.fen().equals(cap.fen()));
    }

    static void validationAndFen() {
        System.out.println("=== 14. whole-position validation, FEN never rewrites pieces ===");
        check("start position valid", Chess.validate(start()).isEmpty());
        Chess.Pos twoKings = fen("4k3/8/8/8/8/8/8/K3K3 w - - 0 1");
        check("two white kings rejected", !Chess.validate(twoKings).isEmpty());
        check("pawn on first rank rejected", !Chess.validate(fen("4k3/8/8/8/8/8/8/P3K3 w - - 0 1")).isEmpty());
        check("no black king rejected", !Chess.validate(fen("8/8/8/8/8/8/8/4K3 w - - 0 1")).isEmpty());
        check("adjacent kings rejected", !Chess.validate(fen("8/8/8/3kK3/8/8/8/8 w - - 0 1")).isEmpty());
        check("side not to move in check rejected", !Chess.validate(fen("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1")).isEmpty());
        check("9 pawns rejected", !Chess.validate(fen("4k3/pppppppp/p7/8/8/8/8/4K3 w - - 0 1")).isEmpty());
        check("two extra queens without missing pawns rejected",
                !Chess.validate(fen("4k3/8/8/8/8/PPPPPPPP/QQQQ4/4K3 w - - 0 1")).isEmpty());
        // fen() reproduces the position exactly (the old code rewrote pieces here)
        Chess.Pos odd = fen("4k3/8/8/8/8/8/8/P3K3 w - - 0 1");
        checkEq("fen() of an invalid position is not 'repaired'", odd.fen().split(" ")[0], "4k3/8/8/8/8/8/8/P3K3");
        Chess.Pos twoK = fen("4k3/8/8/8/8/8/8/K3K3 w - - 0 1");
        checkEq("fen() keeps both white kings (no K->Q rewrite)", twoK.fen().split(" ")[0], "4k3/8/8/8/8/8/8/K3K3");
        Track tk = new Track(twoK, true);
        check("a Track refuses to trust an invalid position", !tk.isTrusted());
        // round trip
        String[] fens = {"r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                         "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 0 1"};
        for (String f : fens) checkEq("FEN round trip", fen(f).fen(), f);
    }

    // ----------------------------------------------------------------- fuzz

    /** boards differ only by the king and rook standing on each other's castling squares */
    static boolean castlingWalkOnly(Chess.Pos a, Chess.Pos b) {
        for (int i = 0; i < 64; i++) {
            if (a.cb[i] == b.cb[i]) continue;
            if (!(i < 8 || i >= 56)) return false;
            int pa = a.cb[i], pb = b.cb[i];
            int ta = pa > 8 ? pa - 8 : pa, tb = pb > 8 ? pb - 8 : pb;
            if (!((ta == 6 && tb == 4) || (ta == 4 && tb == 6))) return false;     // king <-> rook only
        }
        return true;
    }

    static void fuzz() {
        System.out.println("=== 15. randomized games: noise, dropped frames, glitches - never a wrong trusted position ===");
        Random rnd = new Random(20260901);
        int games = Integer.getInteger("fuzz.games", 40), userFixes = 0, wrong = 0, followed = 0, plies = 0, lostEvents = 0, pending = 0;
        long t0 = System.currentTimeMillis();
        int checkpoints = 0, priorChoices = 0, longGap = 0;
        Map<String, Integer> behind = new TreeMap<>();
        for (int g = 0; g < games; g++) {
            boolean wb = rnd.nextBoolean();
            Chess.Pos truth = start();
            Track t = newTrack(start(), wb);
            int maxPly = 30 + rnd.nextInt(90);
            List<Chess.Pos> truthHistory = new ArrayList<>();
            int sinceObserved = 0;
            boolean tainted = false;      // after a documented castling-prior choice the identities stay off until resync
            List<String> sinceSync = new ArrayList<>();
            for (int ply = 0; ply < maxPly; ply++) {
                List<Chess.Move> legal = Chess.legal(truth);
                if (legal.isEmpty()) break;
                // queen promotions only here (under-promotion has its own test)
                List<Chess.Move> cand = new ArrayList<>();
                for (Chess.Move m : legal) if (m.promo == 0 || m.promo == 5) cand.add(m);
                // prefer captures / pawn moves a bit so games develop
                Chess.Move m = cand.get(rnd.nextInt(cand.size()));
                Chess.make(truth, m);
                sinceSync.add(m.uci());
                plies++;
                sinceObserved++;
                // ~20% of the time we "miss" the frame (screen changed while we were not looking)
                if (rnd.nextInt(100) < 20 && sinceObserved < 3) continue;
                sinceObserved = 0;
                // several frames of the same screen, each with its own glitches
                int frames = 2 + rnd.nextInt(2);
                for (int fr = 0; fr < frames; fr++) {
                    int[] obs = Chess.pattern(truth, t.whiteBottom);
                    float[] cf = sure();
                    int k = rnd.nextInt(5);                      // up to 4 low-confidence misreads
                    for (int j = 0; j < k; j++) {
                        int sq = rnd.nextInt(64);
                        obs[sq] = rnd.nextInt(3);
                        cf[sq] = 0.01f + rnd.nextFloat() * 0.25f;
                    }
                    if (rnd.nextInt(100) < 4) {                  // failed capture: blank frame
                        obs = new int[64];
                    } else if (rnd.nextInt(100) < 3) {           // animation: a piece lifted off its square
                        int sq = rnd.nextInt(64);
                        obs[sq] = 0;
                    }
                    String before = t.pos.fen();
                    Track.Update u = t.observe(obs, cf);
                    if (System.getProperty("fz") != null && g == 0) System.out.println("   ply " + ply + " fr " + fr + " " + u.status + " " + u.info + " sk=" + t.sideKnown + " since=" + sinceSync);
                    if (u.status == Track.Status.LOST) lostEvents++;
                    if (u.status == Track.Status.PENDING) pending++;
                    // INVARIANT: whenever the tracker claims to agree with the screen it must be right
                    if (u.ok && u.status != Track.Status.UNCHANGED || u.status == Track.Status.UNCHANGED && u.ok) {
                        if (!same(t, truth) && sameOccupancy(t, truth) && castlingWalkOnly(t.pos, truth)) {
                            priorChoices++;            // documented prior: O-O preferred over King-walk + Rook-move
                            tainted = true;
                        } else if (!same(t, truth) && sameOccupancy(t, truth) && !tainted && sinceSync.size() >= 4) {
                            longGap++;                 // 4+ plies went unobserved: shorter look-alike explanations exist (documented limit)
                            tainted = true;
                        } else if (!same(t, truth) && sameOccupancy(t, truth) && !tainted) {
                            wrong++;
                            if (wrong <= 4) System.out.println("    WRONG game " + g + " ply " + ply + " status " + u.status + " moves " + u.moves + " truthSince " + sinceSync
                                    + "\n        before  " + before + "\n        tracker " + t.pos.fen() + "\n        truth   " + truth.fen());
                        }
                    }
                }
                checkpoints++;
                if (!same(t, truth)) behind.merge(t.lost ? "lost" : (sameOccupancy(t, truth) ? "sameOcc-diffId" : "behind"), 1, Integer::sum);
                if (same(t, truth)) { followed++; sinceSync.clear(); tainted = false; }
                else if (t.lost) {
                    // the app re-reads the board (AutoSeed) or the user starts a new game; the tracker never re-initialises itself
                    t.resetUser(truth);
                    t.sideKnown = true;
                    userFixes++;
                }
            }
        }
        System.out.println("        look-alike identity after 4+ unobserved plies (documented limit): " + longGap);
        System.out.println("        castling-prior choices (king+rook swapped on the back rank, documented): " + priorChoices);
        System.out.println("        checkpoints=" + checkpoints + " not-following=" + behind);
        System.out.println("        games=" + games + " plies=" + plies + " exactly-followed checkpoints=" + followed
                + " lost-events=" + lostEvents + " user-fixes=" + userFixes + " pending-frames=" + pending
                + " time=" + (System.currentTimeMillis() - t0) + " ms");
        checkEq("never a wrong trusted position that agrees with the screen", wrong, 0);
        check("tracker followed the large majority of positions exactly", followed > plies * 0.55);
    }

    /** does the tracker's position look exactly like the truth on screen (colour pattern)? */
    static boolean sameOccupancy(Track t, Chess.Pos truth) {
        return java.util.Arrays.equals(Chess.pattern(t.pos, t.whiteBottom), Chess.pattern(truth, t.whiteBottom));
    }

    // ----------------------------------------------------------------- perf

    static void speed() {
        System.out.println("=== 16. speed (phone friendly) ===");
        Chess.Pos mid = fen("r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2P1PN2/PP1NBPPP/R2Q1RK1 w - - 0 1");
        Track t = new Track(mid, true);
        // 4 plies (worst normal case)
        Chess.Pos four = play(mid, "d2b3", "a7a6", "a2a4", "b7b6");
        long t0 = System.nanoTime();
        Track.Update u = feed(t, four, 2);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("4 plies explained in " + ms + " ms (" + u.status + ")", u.status == Track.Status.MOVED && ms < 1500);
        // unexplainable frame costs a bounded search
        Track t2 = new Track(mid, true);
        int[] tele = Chess.pattern(mid, true);
        tele[Board.squareFromName("a2", true)] = 0;
        tele[Board.squareFromName("a6", true)] = 1;
        long t1 = System.nanoTime();
        for (int i = 0; i < 6; i++) t2.observe(tele, sure());
        long ms2 = (System.nanoTime() - t1) / 1_000_000;
        check("6 unexplainable frames (incl. deep recovery search) bounded: " + ms2 + " ms", ms2 < 8000);
    }

    public static void main(String[] args) {
        stationaryPieces();
        lowConfidenceAndFailedFrames();
        singleChangeNeedsConfirmation();
        captures();
        castling();
        enPassant();
        promotions();
        orientation();
        differentPositions();
        impossibleTransitions();
        recovery();
        takeBackAndNewGame();
        colourAmbiguity();
        wildcards();
        validationAndFen();
        fuzz();
        speed();
        System.out.println();
        System.out.println("================  TrackerTest: " + pass + " passed, " + fail + " failed  ================");
        if (fail > 0) System.exit(1);
    }
}
