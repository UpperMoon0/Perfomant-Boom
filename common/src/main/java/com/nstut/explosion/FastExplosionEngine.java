package com.nstut.explosion;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * High-performance explosion engine for mass block destruction.
 */
public class FastExplosionEngine {
    private static final ForkJoinPool CALCULATION_POOL = new ForkJoinPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
    );

    /**
     * Calculates blocks to be destroyed asynchronously.
     * Uses a spherical check for maximum performance on large scales.
     */
    public static CompletableFuture<List<BlockPos>> calculateExplosionAsync(Level level, Vec3 center, float radius) {
        return CompletableFuture.supplyAsync(() -> {
            List<BlockPos> affectedBlocks = new ArrayList<>();
            int blockRadius = (int) Math.ceil(radius);
            int centerX = (int) Math.floor(center.x);
            int centerY = (int) Math.floor(center.y);
            int centerZ = (int) Math.floor(center.z);
            double radiusSq = radius * radius;

            // Iterate over the bounding box of the sphere
            for (int x = -blockRadius; x <= blockRadius; x++) {
                for (int z = -blockRadius; z <= blockRadius; z++) {
                    double distSqXZ = (double)x * x + (double)z * z;
                    if (distSqXZ > radiusSq) continue;

                    for (int y = -blockRadius; y <= blockRadius; y++) {
                        double distSq = distSqXZ + (double)y * y;
                        if (distSq <= radiusSq) {
                            BlockPos pos = new BlockPos(centerX + x, centerY + y, centerZ + z);
                            if (level.isInWorldBounds(pos)) {
                                affectedBlocks.add(pos.immutable());
                            }
                        }
                    }
                }
            }
            return affectedBlocks;
        }, CALCULATION_POOL);
    }

    /**
     * Advanced BFS-based calculation that respects block resistance.
     * Still asynchronous but slower than the spherical check.
     */
    public static CompletableFuture<List<BlockPos>> calculateResistantExplosionAsync(Level level, Vec3 center, float radius) {
        return CompletableFuture.supplyAsync(() -> {
            LongOpenHashSet affectedBlocksLong = new LongOpenHashSet();
            Map<Long, Float> resistanceMap = new HashMap<>();
            PriorityQueue<ExplosionPoint> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> -p.intensity));

            BlockPos startPos = BlockPos.containing(center);
            long startLong = startPos.asLong();
            queue.add(new ExplosionPoint(startPos, radius));
            resistanceMap.put(startLong, radius);

            while (!queue.isEmpty()) {
                ExplosionPoint current = queue.poll();
                if (current.intensity <= 0) continue;

                if (affectedBlocksLong.add(current.pos.asLong())) {
                    // This is only to keep the order or return type if necessary, 
                    // but we'll collect at the end for better perf.
                }

                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    BlockPos nextPos = current.pos.relative(dir);
                    long nextLong = nextPos.asLong();
                    if (!level.isInWorldBounds(nextPos)) continue;

                    // Optimization: Use a local cache for block resistance if possible
                    float resistance = level.getBlockState(nextPos).getBlock().getExplosionResistance();
                    float nextIntensity = current.intensity - (resistance + 0.3f) * 0.3f;

                    if (nextIntensity > 0 && nextIntensity > resistanceMap.getOrDefault(nextLong, -1f)) {
                        resistanceMap.put(nextLong, nextIntensity);
                        queue.add(new ExplosionPoint(nextPos, nextIntensity));
                    }
                }
            }

            List<BlockPos> finalBlocks = new ArrayList<>(affectedBlocksLong.size());
            for (long l : affectedBlocksLong) {
                finalBlocks.add(BlockPos.of(l));
            }
            return finalBlocks;
        }, CALCULATION_POOL);
    }

    private static class ExplosionPoint {
        final BlockPos pos;
        final float intensity;

        ExplosionPoint(BlockPos pos, float intensity) {
            this.pos = pos;
            this.intensity = intensity;
        }
    }
}
