package com.nstut.testing;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Test-only coordination for asynchronous work in the unpaced GameTest server. */
final class GameTestLightAwaiter {
    private GameTestLightAwaiter() {}

    /** GameTest's succeedWhen retries assertions; never restart an expired wall budget. */
    static BooleanSupplier once(BooleanSupplier observation) {
        return new BooleanSupplier() {
            private Boolean result;
            @Override public boolean getAsBoolean() {
                if (result == null) result = observation.getAsBoolean();
                return result;
            }
        };
    }

    static boolean await(Consumer<BooleanSupplier> managedBlock, Runnable scheduleQueuedWork,
                         BooleanSupplier ready, LongSupplier nanoTime, long timeoutNanos) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeout must be positive");
        if (ready.getAsBoolean()) return true;
        long started = nanoTime.getAsLong();
        managedBlock.accept(() -> {
            // Only schedules existing vanilla light tasks; never adds a block check,
            // initializes a light source, changes chunk data, or relights a fixture.
            scheduleQueuedWork.run();
            return ready.getAsBoolean() || nanoTime.getAsLong() - started >= timeoutNanos;
        });
        // Deadline expiry is a failure, not evidence of convergence.
        return ready.getAsBoolean();
    }
}
