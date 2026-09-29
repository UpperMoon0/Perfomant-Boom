package com.nstut.explosion.terrain;

/** Suppress native cascades only while a bounded terrain operation is on the server stack. */
public final class TerrainMutationScope implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private final boolean previous;
    private TerrainMutationScope() { previous=ACTIVE.get(); ACTIVE.set(true); }
    public static TerrainMutationScope enter() { return new TerrainMutationScope(); }
    public static boolean active() { return ACTIVE.get(); }
    @Override public void close() { if(previous)ACTIVE.set(true);else ACTIVE.remove(); }
}
