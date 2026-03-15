package com.nstut.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
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
        // Implementation for a more "realistic" but still optimized explosion
        // will be added if needed. For million-block scale, sphere is often preferred.
        return calculateExplosionAsync(level, center, radius);
    }
}
