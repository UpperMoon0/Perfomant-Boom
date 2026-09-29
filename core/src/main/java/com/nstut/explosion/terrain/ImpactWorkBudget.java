package com.nstut.explosion.terrain;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/** Cooperative per-level budget. Callbacks are not preempted; stop before the next operation. */
public final class ImpactWorkBudget {
    private static final Map<Object, ImpactWorkBudget> LEVELS = new WeakHashMap<>();
    private final int maxChanges, maxScans, maxRequests;
    private final long maxNanos;
    private final LongSupplier clock;
    private long tick = Long.MIN_VALUE, started;
    private int changes, scans, requests;

    public ImpactWorkBudget(int changes, int scans, int requests, long nanos, LongSupplier clock) {
        if (changes < 1 || scans < 1 || requests < 1 || nanos < 1) throw new IllegalArgumentException("Invalid budget");
        this.maxChanges = changes; this.maxScans = scans; this.maxRequests = requests;
        this.maxNanos = nanos; this.clock = java.util.Objects.requireNonNull(clock);
    }

    /** Server-thread only. Values never retain their weak level keys. */
    public static ImpactWorkBudget forTick(Object level, long tick) {
        ImpactWorkBudget budget = LEVELS.computeIfAbsent(level,
                ignored -> new ImpactWorkBudget(3000, 45000, 1, 8_000_000L, System::nanoTime));
        budget.beginTick(tick);
        return budget;
    }

    public void beginTick(long currentTick) {
        if (tick == currentTick) return;
        tick = currentTick; started = clock.getAsLong(); changes = scans = requests = 0;
    }

    public boolean tryScan() {
        if (changes >= maxChanges || scans >= maxScans || clock.getAsLong() - started >= maxNanos) return false;
        scans++;
        return true;
    }

    public void changed() { changes++; }

    public boolean tryRequestChunk() {
        if (requests >= maxRequests || clock.getAsLong() - started >= maxNanos) return false;
        requests++;
        return true;
    }
}
