package com.nstut.explosion;

import com.nstut.ExampleMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.enchantment.ProtectionEnchantment;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Time-slices explosion calculation and block mutation so large explosions do not
 * monopolize a server tick.
 */
public final class ExplosionScheduler {
    private static final Queue<ExplosionTask> TASK_QUEUE = new ArrayDeque<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Cooperative target for this mod's explosion work in one END_SERVER_TICK pass.
     * Individual chunk loads, callbacks and finalization can exceed this target.
     */
    private static final long WORK_BUDGET_NANOS = 4_000_000L;
    private static MinecraftServer activeServer;

    private ExplosionScheduler() {
    }

    /** workMs is elapsed time inside scheduler calls, NOT thread/process CPU time.
     * Downstream lighting, networking and client work are measured separately by integration tests.
     */
    public record ExplosionMetrics(
        int changedBlocks,
        long raySamples,
        double workMs,
        double maxSliceMs,
        int workPasses,
        double wallMs
    ) {
    }

    public static void schedule(ServerLevel level, Vec3 center, float power) {
        scheduleTracked(level, center, power, null);
    }

    public static void scheduleTracked(
        ServerLevel level,
        Vec3 center,
        float power,
        Consumer<ExplosionMetrics> completion
    ) {
        long start = System.nanoTime();
        ExplosionTask task = new ExplosionTask(level, center, power, completion);
        task.workNanos += System.nanoTime() - start;
        TASK_QUEUE.add(task);
    }

    public static void tick(MinecraftServer server) {
        tickUntil(server, System.nanoTime() + WORK_BUDGET_NANOS);
    }

    // Shared production driver; package-local deadline permits deterministic runtime
    // suspension tests without sleeping, mocking chunks, or changing the work budget.
    static void tickUntil(MinecraftServer server, long deadline) {
        if (activeServer == null) {
            activeServer = server;
        } else if (activeServer != server) {
            // Static state can outlive an integrated-server world in the same JVM.
            // Never let a stale task mutate a previous ServerLevel.
            TASK_QUEUE.clear();
            activeServer = server;
        }

        if (TASK_QUEUE.isEmpty()) {
            return;
        }

        while (!TASK_QUEUE.isEmpty()) {
            ExplosionTask task = TASK_QUEUE.peek();
            if (!task.isValidFor(server)) {
                TASK_QUEUE.poll();
                continue;
            }

            long sliceStart = System.nanoTime();
            boolean finished = task.processUntil(deadline);
            task.recordSlice(System.nanoTime() - sliceStart);
            if (!finished) {
                return;
            }

            task.notifyCompletion();
            TASK_QUEUE.poll();
            if (System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    static int pendingTasks() {
        return TASK_QUEUE.size();
    }

    record PendingMutation(ChunkPos chunk, int processed, int total, int changed) {}

    static PendingMutation pendingMutation() {
        ExplosionTask task = TASK_QUEUE.peek();
        if (task == null || task.destructionOrder == null || task.destructionIndex <= 0
            || task.actualChangedBlocks <= 0 || task.lastChangedChunk == null) {
            return null;
        }
        return new PendingMutation(task.lastChangedChunk,
            task.destructionIndex, task.destructionOrder.size(), task.actualChangedBlocks);
    }

    // Read-only fixture assertion: distinguish an unprocessed interior candidate
    // from a survivor outside the selected final crater.
    static boolean pendingSelectionContains(BlockPos pos) {
        ExplosionTask task = TASK_QUEUE.peek();
        return task != null && task.calculation.affectedBlocks().contains(pos);
    }

    static float serverSideSoundPitch(RandomSource effectsRandom) {
        return (1.0F + (effectsRandom.nextFloat() - effectsRandom.nextFloat()) * 0.2F) * 0.7F;
    }

    static ObjectArrayList<BlockPos> vanillaDestructionOrder(
        FastExplosionEngine.IncrementalCalculation calculation,
        RandomSource random
    ) {
        // Vanilla 1.20.1 copies its java.util.HashSet into an ObjectArrayList and
        // shuffles EVERY affected position, including air, with Level.random.
        // Keeping air entries is required both for exact RNG advancement and for
        // cases where earlier physics makes a formerly-air position non-air.
        ObjectArrayList<BlockPos> order = new ObjectArrayList<>();
        order.addAll(calculation.affectedBlocks());
        Util.shuffle(order, random);
        return order;
    }

    private static final class ExplosionTask {
        private final ServerLevel level;
        private final Vec3 center;
        private final float power;
        // Created only when this task reaches the head of TASK_QUEUE. Creating the
        // calculator consumes vanilla's 1,352 ray-strength draws from Level.random;
        // queued tasks must not reserve those draws before the active task shuffles.
        private FastExplosionEngine.IncrementalCalculation calculation;
        private final Consumer<ExplosionMetrics> completion;
        private final LongOpenHashSet queuedLightChecks = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<ChunkBlockModifier.MutationContext> sliceMutations =
            new Long2ObjectOpenHashMap<>();
        private final RandomSource effectsRandom = RandomSource.create();
        private final long createdNanos = System.nanoTime();
        private final BlockPos.MutableBlockPos lightCheckPos = new BlockPos.MutableBlockPos();

        private boolean started;
        private boolean calculationFinished;
        private ObjectArrayList<BlockPos> destructionOrder;
        private int destructionIndex;
        private int actualChangedBlocks;
        private ChunkPos lastChangedChunk;
        private it.unimi.dsi.fastutil.longs.LongIterator lightCheckIterator;
        private boolean postUpdatesStarted;
        private List<Entity> entitiesToDamage;
        private DamageSource explosionDamageSource;
        private int nextEntityIndex;
        private boolean entityDamageFinished;
        private Explosion blockCallbackExplosion;
        private long workNanos;
        private long maxSliceNanos;
        private int workPasses;
        private double finishedWallMs;
        private boolean completionNotified;

        private ExplosionTask(
            ServerLevel level,
            Vec3 center,
            float power,
            Consumer<ExplosionMetrics> completion
        ) {
            this.level = level;
            this.center = center;
            this.power = power;
            this.completion = completion;
        }

        private void initializeCalculation() {
            if (calculation == null) {
                calculation = FastExplosionEngine.create(level, center, power);
            }
        }

        private void recordSlice(long elapsedNanos) {
            workNanos += elapsedNanos;
            maxSliceNanos = Math.max(maxSliceNanos, elapsedNanos);
            workPasses++;
        }

        private boolean isValidFor(MinecraftServer server) {
            return level.getServer() == server && server.getLevel(level.dimension()) == level;
        }

        /**
         * Returns true when the whole task is complete.
         */
        private boolean processUntil(long deadlineNanos) {
            try {
                return processSlice(deadlineNanos);
            } finally {
                // No live chunk, heightmap or section reference survives a yield,
                // cancellation, dimension replacement or an exceptional callback.
                flushMutationSlice();
            }
        }

        private boolean processSlice(long deadlineNanos) {
            if (!started) {
                started = true;
                beginExplosionEffects();
                // Scheduling is intentionally RNG-free. Only the active queue head may
                // reserve ray strengths, so later explosions cannot interleave their
                // level-RNG draws before this task's vanilla affected-position shuffle.
                initializeCalculation();
            }

            if (!entityDamageFinished) {
                if (!processEntityDamageUntil(deadlineNanos)) {
                    return false;
                }
                entityDamageFinished = true;
            }

            if (!calculationFinished) {
                if (!calculation.processUntil(deadlineNanos)) {
                    return false;
                }

                calculationFinished = true;
                destructionOrder = vanillaDestructionOrder(calculation, level.getRandom());
                ExampleMod.LOGGER.info(
                    "Explosion calculation complete: {} non-air blocks / {} vanilla affected positions from {} ray samples at {} (power {})",
                    calculation.blockCount(),
                    destructionOrder.size(),
                    calculation.sampleCount(),
                    center,
                    power
                );

                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }

            if (postUpdatesStarted) {
                return processPostUpdates(deadlineNanos);
            }

            int checksUntilDeadline = 8;
            while (destructionIndex < destructionOrder.size()) {
                BlockPos pos = destructionOrder.get(destructionIndex++);
                if (removeBlock(pos)) {
                    actualChangedBlocks++;
                    lastChangedChunk = new ChunkPos(pos);
                }

                if (--checksUntilDeadline == 0) {
                    checksUntilDeadline = 8;
                    if (System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
            }

            return processPostUpdates(deadlineNanos);
        }

        private void beginExplosionEffects() {
            level.gameEvent(null, GameEvent.EXPLODE, center);
            prepareEntityDamage();

            // Vanilla 1.20.1 draws explosion sound pitch from the CLIENT level RNG.
            // This scheduler emits the sound server-side, so use task-local randomness;
            // consuming ServerLevel.random here would move gameplay RNG ahead of vanilla.
            float pitch = serverSideSoundPitch(effectsRandom);
            level.playSound(
                null,
                center.x,
                center.y,
                center.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.BLOCKS,
                4.0F,
                pitch
            );
            level.sendParticles(
                power < 2.0F ? ParticleTypes.EXPLOSION : ParticleTypes.EXPLOSION_EMITTER,
                center.x,
                center.y,
                center.z,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D
            );
        }

        private void prepareEntityDamage() {
            float doubleRadius = power * 2.0F;
            int x1 = net.minecraft.util.Mth.floor(center.x - (double)doubleRadius - 1.0D);
            int x2 = net.minecraft.util.Mth.floor(center.x + (double)doubleRadius + 1.0D);
            int y1 = net.minecraft.util.Mth.floor(center.y - (double)doubleRadius - 1.0D);
            int y2 = net.minecraft.util.Mth.floor(center.y + (double)doubleRadius + 1.0D);
            int z1 = net.minecraft.util.Mth.floor(center.z - (double)doubleRadius - 1.0D);
            int z2 = net.minecraft.util.Mth.floor(center.z + (double)doubleRadius + 1.0D);

            entitiesToDamage = level.getEntities(
                null,
                new AABB(x1, y1, z1, x2, y2, z2)
            );
            explosionDamageSource = level.damageSources().explosion(null, null);
        }

        private boolean processEntityDamageUntil(long deadlineNanos) {
            while (nextEntityIndex < entitiesToDamage.size()) {
                damageEntity(entitiesToDamage.get(nextEntityIndex++));
                // Explosion.getSeenPercent can raycast many samples for one entity, so
                // check the budget after every entity rather than in coarse batches.
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }

            entitiesToDamage = null;
            explosionDamageSource = null;
            return true;
        }

        private void damageEntity(Entity entity) {
            if (entity.ignoreExplosion()) {
                return;
            }

            float doubleRadius = power * 2.0F;
            double distanceRatio = Math.sqrt(entity.distanceToSqr(center)) / (double)doubleRadius;
            if (distanceRatio > 1.0D) {
                return;
            }

            double dx = entity.getX() - center.x;
            double dy = (entity instanceof PrimedTnt ? entity.getY() : entity.getEyeY()) - center.y;
            double dz = entity.getZ() - center.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance == 0.0D) {
                return;
            }

            dx /= distance;
            dy /= distance;
            dz /= distance;

            // Use vanilla's exact exposure sampling, including its X/Z sample offset.
            double exposure = Explosion.getSeenPercent(center, entity);
            double impact = (1.0D - distanceRatio) * exposure;

            entity.hurt(
                explosionDamageSource,
                (float)((int)((impact * impact + impact) / 2.0D * 7.0D * (double)doubleRadius + 1.0D))
            );

            double knockback = impact;
            if (entity instanceof LivingEntity living) {
                knockback = ProtectionEnchantment.getExplosionKnockbackAfterDampener(living, impact);
            }

            entity.setDeltaMovement(
                entity.getDeltaMovement().add(dx * knockback, dy * knockback, dz * knockback)
            );
            if (entity instanceof ServerPlayer player
                && player.distanceToSqr(center) < 4096.0D
                && !player.isSpectator()
                && (!player.isCreative() || !player.getAbilities().flying)) {
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
            }
        }
        private boolean removeBlock(BlockPos pos) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();

            long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
            ChunkBlockModifier.MutationContext mutation = sliceMutations.get(chunkKey);
            if (mutation == null) {
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                mutation = ChunkBlockModifier.begin(level, level.getChunk(chunkPos.x, chunkPos.z));
                sliceMutations.put(chunkKey, mutation);
            }

            BlockState oldState = mutation.remove(pos);
            if (oldState == null) {
                return false;
            }

            // Vanilla 1.20.1 only overrides Block#wasExploded for TNT. Restore that
            // behavior without paying a callback/allocation cost for every ordinary block.
            if (oldState.is(Blocks.TNT)) {
                if (blockCallbackExplosion == null) {
                    blockCallbackExplosion = new Explosion(
                        level,
                        null,
                        center.x,
                        center.y,
                        center.z,
                        power,
                        false,
                        Explosion.BlockInteraction.DESTROY
                    );
                }
                oldState.getBlock().wasExploded(level, pos, blockCallbackExplosion);
            }

            // Each mutation already queues vanilla light work. Recheck the final
            // crater boundary after all affected positions are processed as well.
            if (oldState.getLightEmission() > 0) {
                collectLightCheck(x, y, z);
            }

            // This is only an extra final-crater LIGHT recheck. Neighbor/shape
            // notifications already ran synchronously in MutationContext.remove(),
            // including notifications to candidates not removed yet.
            for (Direction direction : DIRECTIONS) {
                int nx = x + direction.getStepX();
                int ny = y + direction.getStepY();
                int nz = z + direction.getStepZ();
                if (level.isOutsideBuildHeight(ny)) {
                    continue;
                }

                long neighbor = BlockPos.asLong(nx, ny, nz);
                if (!calculation.blocks().contains(neighbor)) {
                    collectLightCheck(nx, ny, nz);
                }
            }

            return true;
        }

        private void collectLightCheck(int x, int y, int z) {
            queuedLightChecks.add(BlockPos.asLong(x, y, z));
        }

        private boolean processPostUpdates(long deadlineNanos) {
            if (!postUpdatesStarted) {
                postUpdatesStarted = true;

                // Boundary classification is complete. Drop the O(affected-blocks) data
                // before the extra lighting rechecks can span more ticks.
                calculation.affectedBlocks().clear();
                calculation.blocks().clear();
                calculation.blocksByChunk().clear();
                destructionOrder = null;

                lightCheckIterator = queuedLightChecks.iterator();
            }

            int checksUntilDeadline = 8;
            while (lightCheckIterator.hasNext()) {
                long packed = lightCheckIterator.nextLong();
                lightCheckPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                level.getLightEngine().checkBlock(lightCheckPos);

                if (--checksUntilDeadline == 0) {
                    checksUntilDeadline = 8;
                    if (System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
            }

            queuedLightChecks.clear();

            finishTask();
            return true;
        }

        private void flushMutationSlice() {
            if (sliceMutations.isEmpty()) {
                return;
            }
            try {
                for (ChunkBlockModifier.MutationContext mutation : sliceMutations.values()) {
                    mutation.finish();
                }
            } finally {
                // Never retain live chunk/section/heightmap references across a yield.
                sliceMutations.clear();
            }
        }

        private void finishTask() {
            finishedWallMs = (System.nanoTime() - createdNanos) / 1_000_000.0D;
        }

        private void notifyCompletion() {
            if (completionNotified) {
                return;
            }
            completionNotified = true;

            double workMs = workNanos / 1_000_000.0D;
            double maxSliceMs = maxSliceNanos / 1_000_000.0D;
            ExampleMod.LOGGER.info(
                "Explosion finished: {} blocks changed at {} (power {}) in {}ms wall-clock, {}ms active work, max {}ms/tick",
                actualChangedBlocks,
                center,
                power,
                String.format("%.2f", finishedWallMs),
                String.format("%.2f", workMs),
                String.format("%.2f", maxSliceMs)
            );
            if (completion != null) {
                completion.accept(new ExplosionMetrics(
                    actualChangedBlocks,
                    calculation.sampleCount(),
                    workMs,
                    maxSliceMs,
                    workPasses,
                    finishedWallMs
                ));
            }
        }
    }
}
