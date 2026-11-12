package com.akicater.microblocks;

/** For finding a freeze: with {@code debugTiming = true} in the config every slow piece of work of the mod (over 8 ms) is written to the log with its name. */
public final class MicroTiming {
    private MicroTiming() {}

    private static final long LIMIT_NS = 8_000_000L;

    public static long start() {
        return MicroConfig.debugTiming ? System.nanoTime() : 0L;
    }

    public static void end(String what, long started) {
        if (started == 0L) return;
        long took = System.nanoTime() - started;
        if (took >= LIMIT_NS) System.out.println("microblocks: slow " + what + ": " + took / 1_000_000 + " ms (" + Thread.currentThread().getName() + ")");
    }
}
