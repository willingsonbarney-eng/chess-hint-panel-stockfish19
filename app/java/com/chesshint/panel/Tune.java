package com.chesshint.panel;

/**
 * Small, safe tuning that helps on low-end and older phones.
 * Everything is optional - a failure here must never matter.
 */
public class Tune {

    public static void apply() {
        // keep the engine's memory modest on devices with little RAM
        try {
            long maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            EngineBudget.heapMb = (int) maxHeap;
            EngineBudget.hashMb = maxHeap < 192 ? 16 : (maxHeap < 384 ? 32 : 64);
            EngineBudget.threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));  // big.LITTLE: leave the little cores alone
        } catch (Throwable ignored) { }
    }

    /** how much memory the engine is allowed to use - set once at start-up */
    public static class EngineBudget {
        public static int heapMb = 128;
        public static int hashMb = 16;
        public static int threads = 2;
    }
}
