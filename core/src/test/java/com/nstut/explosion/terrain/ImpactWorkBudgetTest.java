package com.nstut.explosion.terrain;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ImpactWorkBudgetTest {
    @Test void consecutiveNailsShareOneLevelAllowance() {
        Object level = new Object();
        var a = ImpactWorkBudget.forTick(level, 1);
        var b = ImpactWorkBudget.forTick(level, 1);
        assertSame(a, b);
        assertNotSame(a, ImpactWorkBudget.forTick(new Object(), 1));
    }
    @Test void changesAreCappedAcrossCallersAndResetOnlyNextTick() {
        var clock = new AtomicLong();
        var b = new ImpactWorkBudget(2, 20, 1, 100, clock::get);
        b.beginTick(1);
        assertTrue(b.tryScan()); b.changed(); b.beginTick(1);
        assertTrue(b.tryScan()); b.changed();
        assertFalse(b.tryScan());
        b.beginTick(2); assertTrue(b.tryScan());
    }
    @Test void emptySpaceScansAreStillBounded() {
        var b = new ImpactWorkBudget(10, 2, 1, 100, () -> 0);
        b.beginTick(1);
        assertTrue(b.tryScan()); assertTrue(b.tryScan()); assertFalse(b.tryScan());
    }
    @Test void chunkRequestsShareAnIndependentLimit() {
        var b = new ImpactWorkBudget(10, 20, 1, 100, () -> 0);
        b.beginTick(1);
        assertTrue(b.tryRequestChunk()); assertFalse(b.tryRequestChunk());
        assertTrue(b.tryScan());
        b.beginTick(2); assertTrue(b.tryRequestChunk());
    }
    @Test void elapsedTimeStopsWorkAndClockResetsEachTick() {
        var clock = new AtomicLong(10);
        var b = new ImpactWorkBudget(10, 20, 1, 100, clock::get);
        b.beginTick(1); clock.set(110);
        assertFalse(b.tryScan()); assertFalse(b.tryRequestChunk());
        b.beginTick(2); assertTrue(b.tryScan()); assertTrue(b.tryRequestChunk());
    }
}
