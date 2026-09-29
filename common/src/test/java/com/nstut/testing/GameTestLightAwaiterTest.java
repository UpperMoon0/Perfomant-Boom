package com.nstut.testing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class GameTestLightAwaiterTest {
    private static void managedBlock(BooleanSupplier stop) {
        for (int i = 0; i < 100; i++) if (stop.getAsBoolean()) return;
        fail("wait did not terminate");
    }

    @Test void alreadyConvergedNeedsNoWorkerOrBlocking() {
        assertTrue(GameTestLightAwaiter.await(
            stop -> fail("should not block"), () -> fail("should not schedule"),
            () -> true, () -> { throw new AssertionError("should not read time"); }, 1));
    }

    @Test void pendingWorkerMayConvergeWithoutAnyAdditionalGameTick() {
        var cycles = new AtomicInteger();
        assertTrue(GameTestLightAwaiter.await(GameTestLightAwaiterTest::managedBlock,
            cycles::incrementAndGet, () -> cycles.get() == 5, () -> 0L, 10));
        assertEquals(5, cycles.get());
    }

    @Test void completedControlCannotHideAStuckFastPath() {
        var clock = new AtomicLong();
        var cycles = new AtomicInteger();
        boolean vanillaReady = true;
        boolean fastReady = false;
        assertFalse(GameTestLightAwaiter.await(GameTestLightAwaiterTest::managedBlock,
            cycles::incrementAndGet, () -> vanillaReady && fastReady,
            clock::getAndIncrement, 4));
        assertEquals(4, cycles.get());
    }

    @Test void deadlineRemainsBoundedAcrossNanoTimeWraparound() {
        var clock = new AtomicLong(Long.MAX_VALUE - 2);
        var cycles = new AtomicInteger();
        assertFalse(GameTestLightAwaiter.await(GameTestLightAwaiterTest::managedBlock,
            cycles::incrementAndGet, () -> false, clock::getAndIncrement, 5));
        assertEquals(5, cycles.get());
    }

    @Test void workerFailureIsNotConvertedToSuccess() {
        assertThrows(IllegalStateException.class, () -> GameTestLightAwaiter.await(
            GameTestLightAwaiterTest::managedBlock,
            () -> { throw new IllegalStateException("worker failed"); },
            () -> false, () -> 0L, 10));
    }

    @Test void gameTestRetriesDoNotRestartEitherSuccessfulOrExpiredWaits() {
        for (boolean expected : new boolean[]{false, true}) {
            var observations = new AtomicInteger();
            BooleanSupplier once = GameTestLightAwaiter.once(() -> {
                observations.incrementAndGet();
                return expected;
            });
            for (int tick = 0; tick < 240; tick++) assertEquals(expected, once.getAsBoolean());
            assertEquals(1, observations.get());
        }
    }

    @Test void timeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> GameTestLightAwaiter.await(
            GameTestLightAwaiterTest::managedBlock, () -> {}, () -> false, () -> 0L, 0));
    }
}
