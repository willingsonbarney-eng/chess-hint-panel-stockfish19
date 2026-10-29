import com.chesshint.panel.Vision;

import java.io.RandomAccessFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import android.graphics.Rect;

/** Loads the synthetic screenshots and checks board detection + square reading. */
public class VisionTest {

    static int pass = 0, fail = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what + "   got " + got + "  want " + want); }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a.length > 0 ? a[0] : "build/test/boards");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
        java.util.Arrays.sort(files);
        for (File meta : files) {
            String name = meta.getName().replace(".txt", "");
            String[] lines = new String(Files.readAllBytes(Paths.get(meta.getPath()))).split("\n");
            String[] hdr = lines[0].trim().split("\\s+");
            int w = Integer.parseInt(hdr[0]), h = Integer.parseInt(hdr[1]);
            int bx = Integer.parseInt(hdr[2]), by = Integer.parseInt(hdr[3]), bs = Integer.parseInt(hdr[4]);
            String expected = lines.length > 1 ? lines[1].trim() : "";
            String fen = lines.length > 2 ? lines[2].trim() : "";
            boolean negative = fen.equals(".");

            byte[] raw = Files.readAllBytes(Paths.get(dir.getPath() + "/" + name + ".raw"));
            if (raw.length < w * h * 3) { System.out.println("skip " + name); continue; }
            int[] px = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                int r = raw[i * 3] & 255, g = raw[i * 3 + 1] & 255, b = raw[i * 3 + 2] & 255;
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }

            System.out.println("---- " + name + "  (" + w + "x" + h + ")");
            Rect det = Vision.detect(px, w, h);
            if (negative) {
                check("no board found on a board-less screen", det == null, true);
                continue;
            }
            check("board detected", det != null, true);
            if (det == null) continue;
            System.out.println("        detected " + det.toShortString() + "  truth (" + bx + "," + by + "," + bs + ")");
            int dx = Math.abs(det.left - bx), dy = Math.abs(det.top - by), ds = Math.abs(det.width() - bs);
            check("detected rect within 12 px", (dx <= 12 && dy <= 12 && ds <= 14), true);

            Rect truth = new Rect(bx, by, bx + bs, by + bs);
            Vision.Result res = Vision.read(px, w, h, det);
            check("read ok", res.ok, true);
            // ---- confidence: every square the reader gets wrong must be flagged as uncertain, never trusted
            int wrongTrusted = 0, wrongTotal = 0, rightUncertain = 0, uncertain = 0;
            for (int i = 0; i < 64; i++) {
                boolean right = Character.getNumericValue(expected.charAt(i)) == res.colorPat[i];
                boolean sure = res.conf[i] >= com.chesshint.panel.Track.CERTAIN_DEFAULT;
                if (!right) { wrongTotal++; if (sure) wrongTrusted++; }
                if (!sure) { uncertain++; if (right) rightUncertain++; }
            }
            System.out.println("        squares wrong: " + wrongTotal + " (trusted although wrong: " + wrongTrusted
                    + ")   uncertain: " + uncertain + " (of which actually right: " + rightUncertain + ")");
            check("no wrong square is reported with confidence", wrongTrusted, 0);
            check("fewer than 20 uncertain squares (the tracker ignores frames with more)", uncertain < 20, true);
            // ---- end to end: the tracker follows a game from this very picture only when it is clean
            if (name.equals("chesscom_start")) {
                com.chesshint.panel.Chess.Pos start = com.chesshint.panel.Track.startPosition();
                com.chesshint.panel.Track tr = new com.chesshint.panel.Track(start, true);
                com.chesshint.panel.Track.Update u = tr.observeStable(res.colorPat, res.conf);
                check("tracker agrees with the real start position", u.status == com.chesshint.panel.Track.Status.UNCHANGED, true);
                check("tracker keeps the correct position",
                        tr.pos.fen().startsWith(com.chesshint.panel.Board.START_FEN.split(" ")[0]), true);
            }

            // ---- degraded pictures: sensor noise + a translucent highlight/arrow drawn over some squares.
            // Whatever the reader now gets wrong must be flagged as unsure; it must never be trusted.
            java.util.Random rnd = new java.util.Random(name.hashCode());
            for (int trial = 0; trial < 3; trial++) {
                int[] deg = px.clone();
                for (int i = 0; i < deg.length; i++) {
                    int c = deg[i];
                    int n = (int) Math.round(rnd.nextGaussian() * (trial == 0 ? 6 : 14));
                    int r = Math.max(0, Math.min(255, ((c >> 16) & 255) + n));
                    int g = Math.max(0, Math.min(255, ((c >> 8) & 255) + n));
                    int b = Math.max(0, Math.min(255, (c & 255) + n));
                    deg[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                if (trial >= 1) {
                    int cell = det.width() / 8;
                    for (int k = 0; k < 3; k++) {            // three highlighted squares (last-move colour)
                        int sq = rnd.nextInt(64);
                        int x0 = det.left + (sq % 8) * cell, y0 = det.top + (sq / 8) * cell;
                        for (int y = y0; y < y0 + cell; y++) for (int x = x0; x < x0 + cell; x++) {
                            int c = deg[y * w + x];
                            int r = (((c >> 16) & 255) * 55 + 255 * 45) / 100, g = (((c >> 8) & 255) * 55 + 205 * 45) / 100, b = ((c & 255) * 55 + 40 * 45) / 100;
                            deg[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                    }
                }
                Vision.Result dr = Vision.read(deg, w, h, det);
                if (!dr.ok) { System.out.println("        degraded trial " + trial + ": picture rejected (acceptable)"); continue; }
                int bad = 0, badSure = 0, unsure = 0;
                for (int i = 0; i < 64; i++) {
                    boolean right = Character.getNumericValue(expected.charAt(i)) == dr.colorPat[i];
                    boolean sure = dr.conf[i] >= com.chesshint.panel.Track.CERTAIN_DEFAULT;
                    if (!right) { bad++; if (sure) badSure++;
                        System.out.println("          square " + i + " expected " + expected.charAt(i) + " got " + dr.colorPat[i] + " conf " + String.format("%.2f", dr.conf[i])
                            + " score " + String.format("%.3f", dr.score[i]) + " thr " + String.format("%.3f", dr.threshold) + " dmax " + String.format("%.2f", dr.dmax[i])
                            + " inkFrac " + String.format("%.2f", dr.inkFrac[i]) + " height " + String.format("%.2f", dr.pieceHeight[i]) + " hl " + dr.highlighted[i]); }
                    if (!sure) unsure++;
                }
                System.out.println("        degraded trial " + trial + ": wrong " + bad + ", trusted although wrong " + badSure + ", unsure " + unsure);
                check("degraded picture #" + trial + ": no wrong square is trusted", badSure, 0);
            }

            StringBuilder got = new StringBuilder();
            for (int i = 0; i < 64; i++) got.append(res.colorPat[i]);
            if (got.toString().equals(expected)) {
                check("all 64 squares correct", true, true);
            } else {
                fail++;
                System.out.println("  FAIL  squares differ:");
                StringBuilder want = new StringBuilder(expected);
                for (int r = 0; r < 8; r++) {
                    StringBuilder g = new StringBuilder(), e = new StringBuilder();
                    for (int f = 0; f < 8; f++) {
                        int i = r * 8 + f;
                        char gc = got.charAt(i), ec = want.charAt(i);
                        g.append(gc == ec ? '.' : gc);
                        e.append(gc == ec ? '.' : ec);
                    }
                    System.out.println("        row " + r + "  got: " + g + "   want: " + e);
                }
                // which squares are wrong
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 64; i++) if (got.charAt(i) != want.charAt(i))
                    sb.append(ChessName(i)).append("(").append(got.charAt(i)).append("->").append(want.charAt(i)).append(") ");
                System.out.println("        wrong: " + sb);
                System.out.printf("        lmid=%.3f thr=%.3f%n", res.lmid, res.threshold);
                for (int i = 0; i < 64; i++) if (got.charAt(i) != want.charAt(i))
                    System.out.printf("        %s inkFrac=%.3f inkLum=%.3f dmax=%.3f score=%.3f%n",
                        ChessName(i), res.inkFrac[i], res.inkLum[i], res.dmax[i], res.score[i]);
            }
        }
        System.out.println("\n================  " + pass + " passed, " + fail + " failed  ================");
    }

    static String ChessName(int idx) {
        int r = idx / 8, f = idx % 8;
        return "" + (char) ('a' + f) + (8 - r);
    }
}
