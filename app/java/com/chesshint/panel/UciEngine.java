package com.chesshint.panel;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stockfish, compiled into libstockfish.so and loaded with System.loadLibrary().
 * The native side runs the official UCI loop on its own thread and forwards every
 * output line to onEngineLine(); commands go in with nativeWrite().
 *
 * Guarantees of this class (all covered by tools/tests/EngineTest.java):
 *  - at most ONE search is outstanding.  A new analysis first stops the previous one and waits for
 *    its "bestmove", so a late answer of an old search can never be mistaken for the new one;
 *  - every {@link Result} carries the FEN (and the caller's tag, e.g. the tracker version) it was
 *    computed for; its "info" numbers come from that search only;
 *  - the FEN is validated before it is sent and the returned move is checked against our own legal
 *    move generator - an illegal answer is reported as an error, never as a move;
 *  - cancel() stops the running search, shutdown()/restart() really end the engine thread.
 *
 * No Android dependencies on purpose: the identical code is tested on a PC.
 */
public class UciEngine {

    private static UciEngine INSTANCE;
    private static boolean libOk;
    private static String libError = "";
    private static String libName = "";

    public static synchronized UciEngine getInstance() {
        if (INSTANCE == null) INSTANCE = new UciEngine();
        return INSTANCE;
    }

    private native void nativeInit();
    private native void nativeWrite(String cmd);
    private native boolean nativeShutdown(int timeoutMs);

    // ------------------------------------------------------------------ result of one analysis

    public static final class Result {
        /** the position that was analysed (as sent to the engine) */
        public String fen;
        /** caller supplied token - lets the caller drop an answer whose position is no longer current */
        public long tag;
        /** engine move in UCI notation (e2e4, e7e8q) - only set when it is legal in {@link #fen} */
        public String bestmove;
        public String ponder;
        public int depth;
        public int cp;
        public int mate;
        public String pv = "";
        public long searchId;
        public long millis;
        /** true when cancel() / a newer analysis ended this search early: do not show it */
        public boolean cancelled;
        /** null when everything is fine */
        public String error;

        public boolean ok() { return error == null && !cancelled && bestmove != null; }
    }

    private static final class Search {
        final long id;
        final String fen;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String bestLine;
        volatile int depth, cp, mate;
        volatile String pv = "";
        volatile boolean cancelled;
        Search(long id, String fen) { this.id = id; this.fen = fen; }
    }

    // ------------------------------------------------------------------ state

    private final ArrayBlockingQueue<String> raw = new ArrayBlockingQueue<>(4096);
    private final LinkedBlockingQueue<String> hand = new LinkedBlockingQueue<>();
    private final ArrayBlockingQueue<String> echo = new ArrayBlockingQueue<>(512);
    private final Object cmdLock = new Object();
    private final AtomicLong seq = new AtomicLong();

    private Thread pump;
    private volatile boolean alive;
    private volatile Search active;
    private volatile String engineName = "";
    private volatile int eloMin = 1320, eloMax = 3190;
    private volatile boolean sawElo;
    private volatile int strayBestmoves;
    private int restarts;

    /** last finished search (convenience for diagnostics only - use Result for decisions) */
    public volatile int depth;
    public volatile int scoreCp;
    public volatile int mateIn;

    private int hashMb = 32;
    private int threadsWanted = 0;

    // ------------------------------------------------------------------ library

    public static synchronized boolean loadLibrary() {
        if (libOk) return true;
        String[] order = cpuHasDotProd() ? new String[]{"stockfish_dp", "stockfish"} : new String[]{"stockfish"};
        for (String n : order) {
            try {
                System.loadLibrary(n);
                libOk = true;
                libName = n;
                libError = "";
                return true;
            } catch (Throwable t) {
                libError = String.valueOf(t.getMessage());
            }
        }
        libOk = false;
        return false;
    }

    /** ARMv8.2 dot-product CPUs (Realme 11 4G: Helio G99 with Cortex-A76/A55) get the faster build */
    static boolean cpuHasDotProd() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("Features") && (" " + l + " ").contains(" asimddp ")) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** the engine packaged in this APK (shown in the app; EngineTest checks it against what the engine really reports) */
    public static final String BUNDLED_NAME = "Stockfish 19";

    public static String libraryError() { return libError; }
    /** which native library variant is loaded ("stockfish" or "stockfish_dp") */
    public static String libraryName() { return libName; }

    // ------------------------------------------------------------------ engine output

    /** called from the engine threads inside libstockfish.so */
    public void onEngineLine(String line) {
        if (line == null) return;
        if (!raw.offer(line)) {          // never block the engine thread
            raw.poll();
            raw.offer(line);
        }
    }

    public boolean isAlive() { return alive; }
    /** e.g. "Stockfish 19" - whatever the engine says in "id name" */
    public String engineName() { return engineName; }

    /** test helpers: raw engine console access */
    public void clearRaw() { raw.clear(); echo.clear(); }
    public String pollRaw(long ms) throws InterruptedException { return echo.poll(ms, TimeUnit.MILLISECONDS); }
    public boolean isThinking() { return active != null; }
    /** bestmove lines that arrived while no search was waiting for them (must stay 0) */
    public int strayBestmoves() { return strayBestmoves; }
    public int restartCount() { return restarts; }

    /** memory / thread budget, decided once at start-up by Tune */
    public void setBudget(int hashMb, int threads) {
        this.hashMb = Math.max(8, Math.min(256, hashMb));
        this.threadsWanted = Math.max(0, threads);
    }

    // ------------------------------------------------------------------ life cycle

    public synchronized boolean start() {
        synchronized (cmdLock) { return startLocked(); }
    }

    private boolean startLocked() {
        if (alive) return true;
        if (!loadLibrary()) return false;
        try {
            raw.clear(); echo.clear(); hand.clear();
            active = null;
            nativeInit();
        } catch (Throwable t) {
            libError = String.valueOf(t.getMessage());
            return false;
        }
        alive = true;
        pump = new Thread(this::pumpLoop, "uci-pump");
        pump.setDaemon(true);
        pump.start();
        try {
            send("uci");
            if (!waitHand("uciok", 8000)) { killLocked(); libError = "engine did not answer 'uci'"; return false; }
            int threads = threadsWanted > 0 ? threadsWanted
                    : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            send("setoption name Threads value " + threads);
            send("setoption name Hash value " + hashMb);
            send("setoption name Ponder value false");
            send("setoption name UCI_LimitStrength value false");
            if (!ready(10000)) { killLocked(); libError = "engine did not become ready"; return false; }
            return true;
        } catch (Throwable t) {
            killLocked();
            libError = String.valueOf(t.getMessage());
            return false;
        }
    }

    /** wait for "token" in the handshake queue */
    private boolean waitHand(String token, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return false;
            String l = hand.poll(left, TimeUnit.MILLISECONDS);
            if (l == null) return false;
            if (l.equals(token)) return true;
        }
    }

    /** "isready" and wait for exactly one "readyok" */
    private boolean ready(long ms) throws InterruptedException {
        hand.clear();
        send("isready");
        return waitHand("readyok", ms);
    }

    private void pumpLoop() {
        try {
            while (alive || !raw.isEmpty()) {
                String line = raw.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                if (!echo.offer(line)) { echo.poll(); echo.offer(line); }
                if (line.startsWith("bestmove")) {
                    Search s = active;
                    if (s == null) { strayBestmoves++; continue; }
                    s.bestLine = line;
                    active = null;
                    s.done.countDown();
                } else if (line.equals("uciok") || line.equals("readyok")) {
                    hand.offer(line);
                } else if (line.startsWith("id name ")) {
                    engineName = line.substring(8).trim();
                } else if (line.startsWith("option name UCI_Elo ")) {
                    parseEloRange(line);
                } else if (line.startsWith("info ")) {
                    Search s = active;
                    if (s != null && line.contains(" depth ") && line.contains(" score ")) parseInfo(s, line);
                }
            }
        } catch (Throwable ignored) { }
    }

    private void parseEloRange(String line) {
        try {
            String[] t = line.split("\\s+");
            for (int i = 0; i < t.length - 1; i++) {
                if (t[i].equals("min")) eloMin = Integer.parseInt(t[i + 1]);
                else if (t[i].equals("max")) eloMax = Integer.parseInt(t[i + 1]);
            }
            sawElo = true;
        } catch (Throwable ignored) { }
    }

    private static void parseInfo(Search s, String line) {
        try {
            String[] t = line.split("\\s+");
            // only the main line counts
            for (int i = 0; i < t.length - 1; i++) {
                if (t[i].equals("multipv") && !t[i + 1].equals("1")) return;
                if (t[i].equals("string")) return;
            }
            int d = s.depth, cp = s.cp, mate = s.mate;
            String pv = null;
            for (int i = 0; i < t.length - 1; i++) {
                if (t[i].equals("depth")) d = Integer.parseInt(t[i + 1]);
                else if (t[i].equals("cp")) { cp = Integer.parseInt(t[i + 1]); mate = 0; }
                else if (t[i].equals("mate")) { mate = Integer.parseInt(t[i + 1]); }
                else if (t[i].equals("pv")) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = i + 1; k < t.length; k++) sb.append(k > i + 1 ? " " : "").append(t[k]);
                    pv = sb.toString();
                    break;
                }
            }
            s.depth = d; s.cp = cp; s.mate = mate;
            if (pv != null) s.pv = pv;
        } catch (Throwable ignored) { }
    }

    /** raw command (tests, advanced use) */
    public void send(String cmd) {
        if (!alive) return;
        try { nativeWrite(cmd); } catch (Throwable ignored) { }
    }

    /** a new game starts: forget the old search tables */
    public void newGame() {
        synchronized (cmdLock) {
            if (!alive) return;
            stopActiveLocked();
            send("ucinewgame");
            try { ready(5000); } catch (InterruptedException ignored) { }
        }
    }

    // ------------------------------------------------------------------ analysis

    /**
     * Analyse exactly the position given.  Blocks up to moveTimeMs + a few seconds.
     *
     * @param tag   caller token copied into the result
     * @param elo   -1 = full strength, otherwise UCI_Elo target
     */
    public Result analyze(String fen, long tag, int moveTimeMs, int elo) {
        Result r = new Result();
        r.fen = fen;
        r.tag = tag;
        // ---- validate before anything reaches the engine
        Chess.Pos p;
        try {
            p = Chess.fromFen(fen);
        } catch (Throwable t) {
            r.error = "bad FEN";
            return r;
        }
        String why = p == null ? "bad FEN" : Chess.validate(p);
        if (!why.isEmpty()) { r.error = "position not valid: " + why; return r; }
        List<Chess.Move> legal = Chess.legal(p);
        if (legal.isEmpty()) { r.error = "no legal move (game over)"; return r; }

        long t0 = System.currentTimeMillis();
        synchronized (cmdLock) {
            if (!alive && !startLocked()) { r.error = "engine not running: " + libError; return r; }
            Search s = null;
            try {
                if (!stopActiveLocked()) {
                    if (!restartLocked()) { r.error = "engine restart failed"; return r; }
                }
                // everything sent before this point has been processed when readyok arrives
                if (!ready(4000)) {
                    if (!restartLocked() || !ready(4000)) { r.error = "engine not responding"; return r; }
                }
                if (elo > 0) {
                    send("setoption name UCI_LimitStrength value true");
                    send("setoption name UCI_Elo value " + Math.max(eloMin, Math.min(eloMax, elo)));
                } else {
                    send("setoption name UCI_LimitStrength value false");
                }
                s = new Search(seq.incrementAndGet(), fen);
                r.searchId = s.id;
                send("position fen " + fen);
                active = s;                         // must be set before "go": the answer can be instant
                send("go movetime " + Math.max(50, moveTimeMs));
                boolean got = s.done.await(Math.max(50, moveTimeMs) + 8000L, TimeUnit.MILLISECONDS);
                if (!got) {
                    active = null;
                    r.error = "engine did not answer";
                    restartLocked();
                    return r;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                r.error = "interrupted";
                if (s != null) { s.cancelled = true; stopActiveLocked(); }
                return r;
            }
            r.depth = s.depth; r.cp = s.cp; r.mate = s.mate; r.pv = s.pv;
            r.cancelled = s.cancelled;
            r.millis = System.currentTimeMillis() - t0;
            depth = s.depth; scoreCp = s.cp; mateIn = s.mate;
            String mv = moveOf(s.bestLine);
            String[] bl = s.bestLine == null ? new String[0] : s.bestLine.trim().split("\\s+");
            if (bl.length >= 4 && bl[2].equals("ponder")) r.ponder = bl[3];
            if (mv == null) {
                if (!r.cancelled) r.error = "engine returned no move";
                return r;
            }
            boolean isLegal = false;
            for (Chess.Move m : legal) if (m.uci().equals(mv)) { isLegal = true; break; }
            if (!isLegal) {
                r.error = "engine move " + mv + " is not legal in the analysed position";
                return r;
            }
            r.bestmove = mv;
            return r;
        }
    }

    /** stop the search in progress (if any); the pending analyze() returns a cancelled Result */
    public void cancel() {
        Search s = active;
        if (s != null) {
            s.cancelled = true;
            send("stop");
        }
    }

    /** make sure no search is running: stop it and wait for its "bestmove".  false = it never ended */
    private boolean stopActiveLocked() {
        Search s = active;
        if (s == null) return true;
        s.cancelled = true;
        send("stop");
        try {
            return s.done.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ stop / restart

    /** stop the running search; the engine stays alive */
    public synchronized void stop() {
        cancel();
    }

    /** end the engine thread (quit + join).  start() brings it back */
    public void shutdown() {
        synchronized (cmdLock) {
            killLocked();
        }
    }

    public boolean restart() {
        synchronized (cmdLock) { return restartLocked(); }
    }

    private boolean restartLocked() {
        restarts++;
        killLocked();
        return startLocked();
    }

    private void killLocked() {
        Search s = active;
        if (s != null) { s.cancelled = true; }
        boolean wasAlive = alive;
        if (wasAlive) {
            try { nativeWrite("stop"); } catch (Throwable ignored) { }
            try { nativeShutdown(4000); } catch (Throwable ignored) { }
        }
        alive = false;
        active = null;
        if (s != null) s.done.countDown();
        Thread p = pump;
        if (p != null) {
            try { p.join(1000); } catch (InterruptedException ignored) { }
            pump = null;
        }
        raw.clear();
        hand.clear();
    }

    public static String moveOf(String bestLine) {
        if (bestLine == null) return null;
        String[] t = bestLine.trim().split("\\s+");
        if (t.length >= 2 && t[0].equals("bestmove")) {
            if (t[1].equals("(none)") || t[1].equals("0000")) return null;
            return t[1];
        }
        return null;
    }
}
