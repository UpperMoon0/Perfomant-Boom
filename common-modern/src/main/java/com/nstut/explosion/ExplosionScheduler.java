package com.nstut.explosion;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/** Server-thread, cooperative explosion work. Native callbacks are never preempted. */
public final class ExplosionScheduler {
    private static final ArrayDeque<Task> TASKS = new ArrayDeque<>();
    private static MinecraftServer activeServer;
    private ExplosionScheduler() {}
    public record ExplosionMetrics(int changedBlocks, long raySamples, double workMs,
            double maxSliceMs, int workPasses, double wallMs) {}

    public static void schedule(ServerLevel level, Vec3 center, float power) {
        scheduleTracked(level, center, power, null);
    }
    public static void scheduleTracked(ServerLevel level, Vec3 center, float power,
            Consumer<ExplosionMetrics> completion) {
        if (!Float.isFinite(power) || power <= 0 || power > 500
                || !Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z))
            throw new IllegalArgumentException("Finite center and power in (0, 500] required");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        bind(level.getServer());
        Task task = new Task(level, center, power, completion);
        if (TASKS.isEmpty()) task.initialize();
        TASKS.add(task);
    }
    private static void bind(MinecraftServer server) {
        if (activeServer != server) { TASKS.clear(); activeServer = server; }
    }
    public static void tick(MinecraftServer server) { tickUntil(server, System.nanoTime() + 4_000_000L); }
    static void tickUntil(MinecraftServer server, long deadline) {
        bind(server);
        while (!TASKS.isEmpty()) {
            Task task = TASKS.peek();
            if (server.getLevel(task.level.dimension()) != task.level) { TASKS.remove(); continue; }
            long start = System.nanoTime();
            boolean done = task.step(deadline);
            long elapsed = System.nanoTime() - start;
            task.work += elapsed; task.maximum = Math.max(task.maximum, elapsed); task.passes++;
            if (!done) return;
            TASKS.remove(); // A completion callback may enqueue the next task.
            if (task.completion != null) task.completion.accept(new ExplosionMetrics(task.changed,
                    task.calculation.sampleCount(), task.work / 1e6, task.maximum / 1e6,
                    task.passes, (System.nanoTime() - task.created) / 1e6));
            if (System.nanoTime() >= deadline) return;
        }
    }
    private static final class Task {
        final ServerLevel level;
        final Vec3 center;
        final float power;
        final Consumer<ExplosionMetrics> completion;
        final long created = System.nanoTime();
        final Explosion explosion;
        FastExplosionEngine.IncrementalCalculation calculation;
        ObjectArrayList<BlockPos> order;
        List<Entity> entities;
        final java.util.Map<net.minecraft.server.level.ServerPlayer, Vec3> hitPlayers = new java.util.HashMap<>();
        final java.util.Set<net.minecraft.server.level.ServerPlayer> effectsSent = new java.util.HashSet<>();
        boolean started, calculated, damaged;
        int entityIndex, blockIndex, changed, passes;
        long work, maximum;
        Task(ServerLevel level, Vec3 center, float power, Consumer<ExplosionMetrics> completion) {
            this.level=level; this.center=center; this.power=power; this.completion=completion;
            explosion=VanillaExplosionAdapter.create(level, center, power);
        }
        void initialize() {
            if (calculation == null) calculation=FastExplosionEngine.create(level, center, power);
        }
        boolean step(long deadline) {
            if (!started) {
                started=true;
                level.gameEvent(null, GameEvent.EXPLODE, center);
                initialize();
            }
            // Vanilla selects positions before hurting entities; callbacks can change blocks/RNG.
            if (!calculated) {
                if (!calculation.processUntil(deadline)) return false;
                calculated=true;
                float diameter=power*2;
                entities=level.getEntities(null, new AABB(Mth.floor(center.x-diameter-1),
                        Mth.floor(center.y-diameter-1), Mth.floor(center.z-diameter-1),
                        Mth.floor(center.x+diameter+1), Mth.floor(center.y+diameter+1),
                        Mth.floor(center.z+diameter+1)));
            }
            if (!damaged) {
                while (entityIndex < entities.size()) {
                    Entity entity = entities.get(entityIndex++);
                    VanillaExplosionAdapter.hurt(level, explosion, entity, hitPlayers);
                    // Send the additive impulse in the same slice as the server velocity change.
                    // Evaluate range now, before a yielded task lets the player move again.
                    if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
                        Vec3 impulse = hitPlayers.remove(player);
                        if (impulse != null && player.distanceToSqr(center) < 4096.0) {
                            player.connection.send(VanillaExplosionAdapter.clientPacket(center, power, impulse));
                            effectsSent.add(player);
                        }
                    }
                    if (System.nanoTime() >= deadline) return false;
                }
                damaged=true; entities=null;
                order=new ObjectArrayList<>(calculation.affectedBlocks());
                VanillaExplosionAdapter.shuffle(order, level.getRandom());
                VanillaExplosionAdapter.sendEffects(level, center, power, effectsSent);
                effectsSent.clear();
            }
            while (blockIndex < order.size()) {
                BlockPos pos=order.get(blockIndex++);
                var before=level.getBlockState(pos);
                // Run the current version's native explosion callback. General loot is discarded,
                // matching Boom's no-general-loot contract; inventory/TNT lifecycle remains native.
                before.onExplosionHit(level, pos, explosion, (stack, at) -> {});
                if (level.getBlockState(pos) != before) changed++;
                if (System.nanoTime() >= deadline) return false;
            }
            return true;
        }
    }
}
