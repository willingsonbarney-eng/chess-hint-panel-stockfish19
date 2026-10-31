import com.chesshint.panel.*;
import android.graphics.Rect;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The automatic position reader that replaced the manual "FIX PIECES" editor, on the synthetic screenshots.
 * Desktop test only: it says nothing about real chess apps or a real phone.
 */
public class AutoSeedTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    static String dir;
    static String fenOf;

    static Vision.Result load(String name) throws Exception {
        String[] lines = new String(Files.readAllBytes(Paths.get(dir + "/" + name + ".txt"))).split("\n");
        String[] hdr = lines[0].trim().split("\\s+");
        int w = Integer.parseInt(hdr[0]), h = Integer.parseInt(hdr[1]);
        fenOf = lines[2].trim();
        byte[] raw = Files.readAllBytes(Paths.get(dir + "/" + name + ".raw"));
        int[] px = new int[w * h];
        for (int i = 0; i < w * h; i++)
            px[i] = 0xFF000000 | ((raw[i * 3] & 255) << 16) | ((raw[i * 3 + 1] & 255) << 8) | (raw[i * 3 + 2] & 255);
        Rect det = Vision.detect(px, w, h);
        return Vision.read(px, w, h, det);
    }

    static Chess.Pos truth(String placement) { return Chess.fromFen(placement + " w - - 0 1"); }

    static boolean samePieces(Chess.Pos a, Chess.Pos b) {
        for (int i = 0; i < 64; i++) if (a.cb[i] != b.cb[i]) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        dir = args.length > 0 ? args[0] : "build/test/boards";
        Chess.Pos startPos = Track.startPosition();

        // ---- the templates learn this app's pieces from the start position
        Vision.Result start = load("chesscom_start");
        PieceTemplates tpl = new PieceTemplates();
        check("templates are not ready before anything was learned", !tpl.ready());
        check("learning from the start picture works", tpl.learn(start, startPos, true));
        check("all twelve piece types are known after the start position", tpl.ready());
        PieceTemplates wrongPos = new PieceTemplates();
        Chess.Pos other = Chess.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1");
        check("nothing is learned from a picture that does not match the position", !wrongPos.learn(start, other, true));
        check("...and the templates stay empty", !wrongPos.ready());

        // ---- joining half way WITH learned templates: exact pieces, flagged as "learned"
        {
            String n = "lichess_mid";
            Vision.Result r = load(n);
            AutoSeed.Result a = AutoSeed.seed(r, true, null, tpl);
            check(n + ": a position is built from the screen", a.ok());
            if (!a.ok()) System.out.println("        problem: " + a.problem);
            else {
                check(n + ": source is 'learned', not an estimate", a.source == AutoSeed.SRC_LEARNED);
                check(n + ": every piece is the right piece", samePieces(a.pos, truth(fenOf)));
                check(n + ": the position is legal", Chess.validate(a.pos).isEmpty());
            }
        }
        {   // a busy background leaves squares unsure: it may refuse (and wait for a clearer frame), but never be wrong
            Vision.Result r = load("busy_bg");
            AutoSeed.Result a = AutoSeed.seed(r, true, null, tpl);
            System.out.println("        busy_bg: " + (a.ok() ? "read, source " + a.source : "refused (" + a.problem + ")"));
            check("busy_bg: refused or exact, never a wrong board", !a.ok() || samePieces(a.pos, truth(fenOf)));
        }
        for (String n : new String[] { "promotion", "blue_endgame" }) {
            Vision.Result r = load(n);
            AutoSeed.Result a = AutoSeed.seed(r, true, null, tpl);
            check(n + ": a position is built from the screen", a.ok());
            if (!a.ok()) { System.out.println("        problem: " + a.problem); continue; }
            check(n + ": source is 'learned', not an estimate", a.source == AutoSeed.SRC_LEARNED);
            check(n + ": every piece is the right piece", samePieces(a.pos, truth(fenOf)));
        }

        // ---- without templates the result is an ESTIMATE and says so
        AutoSeed.Result est = AutoSeed.seed(load("lichess_mid"), true, null, new PieceTemplates());
        if (est.ok()) check("without templates the result is marked as an estimate", est.source == AutoSeed.SRC_ESTIMATED);
        else check("without templates it either estimates or refuses (" + est.problem + ")", true);
        check("an estimate never claims to be 'learned'", !est.ok() || est.source != AutoSeed.SRC_LEARNED);

        // ---- a different look (other theme): templates must not produce a confident WRONG board
        Vision.Result dark = load("dark_theme");
        AutoSeed.Result d = AutoSeed.seed(dark, true, null, tpl);
        boolean claimsLearned = d.ok() && d.source == AutoSeed.SRC_LEARNED;
        check("other theme: never an unflagged wrong board",
                !claimsLearned || samePieces(d.pos, truth(fenOf)));
        if (claimsLearned) System.out.println("        note: other theme was read as 'learned': exact=" + samePieces(d.pos, truth(fenOf)));

        // ---- lost game: pieces the tracker still knows are kept exactly, only the rest is read
        Vision.Result mid = load("lichess_mid");
        Chess.Pos real = truth(fenOf);
        Chess.Pos stale = real.copy();                 // the tracker's old picture: the e4 pawn is still on e2, the bishop on f1
        int e4 = Board.squareFromName("e4", true) , e2 = Board.squareFromName("e2", true);
        int sqE4 = Chess.sqFromName("e4"), sqE2 = Chess.sqFromName("e2"), sqC4 = Chess.sqFromName("c4"), sqF1 = Chess.sqFromName("f1");
        stale.cb[sqE2] = stale.cb[sqE4]; stale.cb[sqE4] = 0;
        stale.cb[sqF1] = stale.cb[sqC4]; stale.cb[sqC4] = 0;
        Track lostTrack = new Track(stale, true);
        check("the stale position is itself a legal position", lostTrack.isTrusted());
        AutoSeed.Result rec = AutoSeed.seed(mid, true, lostTrack, tpl);
        check("recovery after a lost game builds a position", rec.ok());
        if (rec.ok()) {
            check("recovery: pieces that are still sure are kept (" + rec.kept + ")", rec.kept >= 28);
            check("recovery: the moved pieces are read correctly", rec.pos.cb[sqE4] == 1 && rec.pos.cb[sqC4] == 3);
            check("recovery: the result is the real position", samePieces(rec.pos, real));
        }
        // the same recovery WITHOUT templates keeps the known pieces and estimates only the two it must
        AutoSeed.Result rec2 = AutoSeed.seed(mid, true, lostTrack, new PieceTemplates());
        check("recovery without templates still keeps every sure piece", !rec2.ok() || rec2.kept >= 28);
        check("recovery without templates marks the estimate", !rec2.ok() || rec2.guessed == 0 || rec2.source == AutoSeed.SRC_ESTIMATED);

        // ---- unclear pictures are refused, never guessed
        Vision.Result unclear = load("lichess_mid");
        unclear.conf[10] = 0.05f;
        check("a picture with an unsure square is refused", !AutoSeed.seed(unclear, true, null, tpl).ok());
        check("a missing picture is refused", !AutoSeed.seed(null, true, null, tpl).ok());

        // ---- the position it builds is always a legal one
        Vision.Result broken = load("lichess_mid");
        for (int i = 0; i < 64; i++) if (broken.colorPat[i] == 2) { broken.colorPat[i] = 1; }   // no black pieces at all
        check("an impossible picture (no black king) is refused", !AutoSeed.seed(broken, true, null, tpl).ok());

        // ---- template (de)serialisation
        String text = tpl.serialize();
        PieceTemplates copy = new PieceTemplates();
        check("templates can be saved and loaded", copy.load(text) && copy.ready());
        check("garbage is rejected", !new PieceTemplates().load("v1;99,1,2") && !new PieceTemplates().load("nonsense"));
        AutoSeed.Result viaCopy = AutoSeed.seed(load("lichess_mid"), true, null, copy);
        check("loaded templates read the pieces the same way", viaCopy.ok() && samePieces(viaCopy.pos, truth(fenOf)));

        System.out.println("\n================  " + pass + " passed, " + fail + " failed  ================");
        if (fail > 0) System.exit(1);
    }
}
