package com.chesshint.panel;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Local Stockfish 19 UCI engine hosted inside libstockfish.so.
 *
 * A single command/search stream is serialized so a delayed bestmove from an older position
 * cannot be returned as the answer to a newer FEN. The JNI engine and UCI pump are separate
 * threads; cancellation does not block behind the synchronous bestMove call.
 */
public class UciEngine {

    private static UciEngine INSTANCE;
    private static boolean libOk;
    private static String libError = "";

    public static synchronized UciEngine getInstance() {
        if (INSTANCE == null) INSTANCE = new UciEngine();
        return INSTANCE;
    }

    private native void nativeInit();
    private native void nativeWrite(String cmd);
    private native boolean nativeIsRunning();

    private final ArrayBlockingQueue<String> raw = new ArrayBlockingQueue<>(4096);
    private final ArrayBlockingQueue<String> best = new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<String> hand = new ArrayBlockingQueue<>(16);
    private final ArrayBlockingQueue<String> echo = new ArrayBlockingQueue<>(512);

    private Thread pump;
    private volatile boolean alive;
    private volatile boolean thinking;
    private volatile boolean searchOutstanding;
    private volatile boolean cancelRequested;

    public volatile int depth;
    public volatile int scoreCp;
    public volatile int mateIn;
    public volatile String lastName = "";
    public volatile String engineName = "";
    public volatile String engineVersion = "";
    /** The FEN attached to the last bestmove actually returned to the caller. */
    public volatile String lastAnalyzedFen = "";

    public static synchronized boolean loadLibrary() {
        if (libOk) return true;
        try {
            System.loadLibrary("stockfish");
            libOk = true;
            libError = "";
        } catch (Throwable t) {
            libOk = false;
            libError = String.valueOf(t.getMessage());
        }
        return libOk;
    }

    public static String libraryError() { return libError; }

    /** Called by the native engine thread for every UCI output line. Must never block it. */
    public void onEngineLine(String line) {
        if (line == null) return;
        if (!raw.offer(line)) {
            raw.poll();
            raw.offer(line);
        }
    }

    public boolean isAlive() { return alive && safeNativeRunning(); }
    public void clearRaw() { raw.clear(); echo.clear(); }
    public String pollRaw(long ms) throws InterruptedException { return echo.poll(ms, TimeUnit.MILLISECONDS); }
    public boolean isThinking() { return thinking; }

    private int hashMb = 16;
    private int threadsWanted = 0;

    /** Mobile budget. Stockfish caps Hash to 64 MiB and defaults to at most two worker threads. */
    public void setBudget(int hashMb, int threads) {
        this.hashMb = Math.max(8, Math.min(64, hashMb));
        this.threadsWanted = Math.max(0, Math.min(2, threads));
    }

    public synchronized boolean start() {
        if (alive && safeNativeRunning()) return true;
        // Retire a stale pump before creating another one. A native engine can terminate
        // unexpectedly while its Java pump still has alive=true; starting a second pump
        // would let both threads consume the same output queue and corrupt UCI ordering.
        if (alive || (pump != null && pump.isAlive())) {
            alive = false;
            if (pump != null && pump != Thread.currentThread()) {
                pump.interrupt();
                try { pump.join(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                if (pump.isAlive()) {
                    libError = "Previous Stockfish output thread did not stop cleanly";
                    return false;
                }
            }
            pump = null;
        }
        // A failed startup may have timed out while the native UCI thread was still unwinding.
        // Never initialize over a live native instance or share its command/output queues.
        long nativeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (safeNativeRunning() && System.nanoTime() < nativeDeadline) {
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        if (safeNativeRunning()) {
            libError = "Previous Stockfish native thread is still running; restart refused";
            return false;
        }
        alive = false;
        if (!loadLibrary()) return false;
        raw.clear();
        best.clear();
        hand.clear();
        echo.clear();
        engineName = "";
        engineVersion = "";
        lastAnalyzedFen = "";
        try {
            nativeInit();
            // Set the flag before starting the pump; otherwise the first scheduler slice could
            // see alive=false and make the reader exit before UCI's uciok arrives.
            alive = true;
            pump = new Thread(this::pumpLoop, "stockfish-uci-pump");
            pump.setDaemon(true);
            pump.start();

            nativeWrite("uci");
            if (!awaitHand("uciok", 12000)) return failStartup("Stockfish did not answer uciok");
            if (!engineName.toLowerCase().contains("stockfish 19")) {
                return failStartup("Expected Stockfish 19 but got: " + engineName);
            }

            int threads = threadsWanted > 0 ? threadsWanted
                    : Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
            nativeWrite("setoption name Threads value " + threads);
            nativeWrite("setoption name Hash value " + hashMb);
            nativeWrite("setoption name Skill Level value 20");
            nativeWrite("setoption name Ponder value false");
            nativeWrite("isready");
            if (!awaitHand("readyok", 12000)) return failStartup("Stockfish did not answer readyok");
            return true;
        } catch (Throwable t) {
            libError = "Stockfish startup failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            abortNativeStartup();
            return false;
        }
    }

    private boolean failStartup(String message) {
        libError = message;
        abortNativeStartup();
        return false;
    }

    private void abortNativeStartup() {
        try { nativeWrite("quit"); } catch (Throwable ignored) { }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (safeNativeRunning() && System.nanoTime() < end) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        alive = false;
        if (pump != null) pump.interrupt();
    }

    private boolean safeNativeRunning() {
        try { return nativeIsRunning(); } catch (Throwable ignored) { return false; }
    }

    private boolean awaitHand(String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            String line = hand.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null) return false;
            if (expected.equals(line)) return true;
        }
    }

    private void pumpLoop() {
        try {
            while (alive || safeNativeRunning() || !raw.isEmpty()) {
                String line = raw.poll(250, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                if (!echo.offer(line)) { echo.poll(); echo.offer(line); }

                if (line.startsWith("id name ")) {
                    engineName = line.substring("id name ".length()).trim();
                    int at = engineName.toLowerCase().indexOf("stockfish ");
                    if (at >= 0) {
                        int end = at + "stockfish ".length();
                        int stop = end;
                        while (stop < engineName.length() && (Character.isDigit(engineName.charAt(stop))
                                || engineName.charAt(stop) == '.')) stop++;
                        engineVersion = engineName.substring(end, stop);
                    }
                }
                if (line.startsWith("bestmove")) {
                    thinking = false;
                    searchOutstanding = false;
                    if (!best.offer(line)) { best.poll(); best.offer(line); }
                } else if (line.equals("uciok") || line.equals("readyok")) {
                    if (!hand.offer(line)) { hand.poll(); hand.offer(line); }
                } else if (line.startsWith("info") && line.contains(" depth ")) {
                    parseInfo(line);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // A closed service should not crash Android due to an output callback error.
        }
    }

    private void parseInfo(String line) {
        try {
            String[] t = line.split("\\s+");
            for (int i = 0; i < t.length - 1; i++) {
                if (t[i].equals("depth")) depth = parseInt(t[i + 1], depth);
                else if (t[i].equals("cp")) { scoreCp = parseInt(t[i + 1], scoreCp); mateIn = 0; }
                else if (t[i].equals("mate")) mateIn = parseInt(t[i + 1], 0);
                else if (t[i].equals("pv") && i + 1 < t.length) lastName = t[i + 1];
            }
        } catch (Throwable ignored) { }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    public void send(String cmd) {
        if (!alive || cmd == null) return;
        try { nativeWrite(cmd); } catch (Throwable ignored) { }
    }

    /** @param elo -1 = full strength, otherwise UCI_Elo target (1350..2850). */
    public synchronized String bestMove(String fen, int moveTimeMs, int elo) {
        if (!alive || fen == null || fen.trim().isEmpty()) return null;
        cancelRequested = false;
        try {
            // If a previous call was cancelled or timed out, consume its bestmove before
            // clearing queues and assigning a response to this new FEN.
            if (searchOutstanding) {
                send("stop");
                String stale = best.poll(3500, TimeUnit.MILLISECONDS);
                if (stale == null || !stale.startsWith("bestmove")) {
                    cancelRequested = true;
                    return null; // do not issue a new go into an unsynchronized UCI stream
                }
            }

            best.clear();
            hand.clear();
            send("isready");
            if (!awaitHand("readyok", 4000)) return null;

            if (elo > 0) {
                send("setoption name UCI_LimitStrength value true");
                send("setoption name UCI_Elo value " + Math.max(1350, Math.min(2850, elo)));
            } else {
                send("setoption name UCI_LimitStrength value false");
            }
            send("isready");
            if (!awaitHand("readyok", 4000)) return null;

            depth = 0;
            scoreCp = 0;
            mateIn = 0;
            lastName = "";
            searchOutstanding = true;
            thinking = true;
            send("position fen " + fen.trim());
            int budget = Math.max(100, Math.min(10000, moveTimeMs));
            send("go movetime " + budget);
            String line = best.poll(budget + 8000L, TimeUnit.MILLISECONDS);
            if (line == null || !line.startsWith("bestmove")) {
                cancelRequested = true;
                send("stop");
                String stopped = best.poll(3500, TimeUnit.MILLISECONDS);
                if (stopped == null || !stopped.startsWith("bestmove")) return null;
                return null;
            }
            if (cancelRequested) return null;
            lastAnalyzedFen = fen.trim();
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelRequested = true;
            send("stop");
            return null;
        } catch (Throwable t) {
            cancelRequested = true;
            send("stop");
            return null;
        } finally {
            // `searchOutstanding` is cleared by the pump only after a bestmove arrives.
            if (!searchOutstanding) thinking = false;
        }
    }

    public static String moveOf(String bestLine) {
        if (bestLine == null) return null;
        String[] t = bestLine.trim().split("\\s+");
        if (t.length >= 2 && t[0].equals("bestmove")) {
            if (t[1].equals("(none)")) return null;
            return t[1];
        }
        return null;
    }

    /** Non-blocking search cancellation; does not wait for bestMove's synchronized lock. */
    public void stop() {
        cancelRequested = true;
        if (alive && searchOutstanding) {
            try { nativeWrite("stop"); } catch (Throwable ignored) { }
        }
    }

    /** Gracefully stop and join the native UCI loop. Safe to call more than once. */
    public void shutdown() {
        cancelRequested = true;
        if (safeNativeRunning()) {
            try {
                if (searchOutstanding) nativeWrite("stop");
                nativeWrite("quit");
            } catch (Throwable ignored) { }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (safeNativeRunning() && System.nanoTime() < end) {
                try { Thread.sleep(20); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (!safeNativeRunning()) {
            alive = false;
            if (pump != null && pump != Thread.currentThread()) {
                pump.interrupt();
                try { pump.join(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            thinking = false;
            searchOutstanding = false;
            best.clear();
            hand.clear();
            raw.clear();
        }
    }
}
