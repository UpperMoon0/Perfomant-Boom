package com.nstut.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Manages mass destruction by splitting it across multiple ticks.
 */
public class ExplosionScheduler {
    private static final Queue<DestructionTask> TASK_QUEUE = new ArrayDeque<>();
    private static final int BLOCKS_PER_TICK = 50000; // Configurable

    public static void scheduleDestruction(ServerLevel level, Vec3 center, List<BlockPos> blocks) {
        TASK_QUEUE.add(new DestructionTask(level, center, blocks));
    }

    public static void tick() {
        if (TASK_QUEUE.isEmpty()) return;

        DestructionTask currentTask = TASK_QUEUE.peek();
        if (currentTask.processBatch(BLOCKS_PER_TICK)) {
            TASK_QUEUE.poll();
            // Trigger final cleanup/lighting for this task if needed
        }
    }

    private static class DestructionTask {
        private final ServerLevel level;
        private final Vec3 center;
        private final List<BlockPos> blocks;
        private final LootAggregator lootAggregator = new LootAggregator();
        private final Set<ChunkPos> affectedChunks = new HashSet<>();
        private int currentIndex = 0;

        public DestructionTask(ServerLevel level, Vec3 center, List<BlockPos> blocks) {
            this.level = level;
            this.center = center;
            this.blocks = blocks;
        }

        /**
         * Processes a batch of blocks. Returns true if the task is finished.
         */
        public boolean processBatch(int count) {
            int end = Math.min(currentIndex + count, blocks.size());
            BlockState air = Blocks.AIR.defaultBlockState();

            for (int i = currentIndex; i < end; i++) {
                BlockPos pos = blocks.get(i);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    lootAggregator.addBlockDrops(level, pos, state);
                    ChunkBlockModifier.setBlockFast(level, pos, air);
                    affectedChunks.add(new ChunkPos(pos));
                }
            }

            currentIndex = end;
            if (currentIndex >= blocks.size()) {
                finalizeDestruction();
                return true;
            }
            return false;
        }

        private void finalizeDestruction() {
            // Finalize each chunk
            for (ChunkPos chunkPos : affectedChunks) {
                ChunkBlockModifier.finalizeChunkChanges(level, level.getChunk(chunkPos.x, chunkPos.z));
            }
            // Spawn aggregated loot
            lootAggregator.spawnDrops(level, center);
        }
    }
}
