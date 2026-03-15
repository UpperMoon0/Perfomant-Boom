package com.nstut.explosion;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * High-performance explosion engine for mass block destruction.
 */
public class FastExplosionEngine {
    private static final ForkJoinPool CALCULATION_POOL = new ForkJoinPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
    );

    public record CalculationResult(List<BlockPos> blocks, double calcTime, double worldTime) {}
    
    /**
     * A pre-fetched view of the world to avoid slow ServerLevel lookups in worker threads.
     */
    public record FastWorldView(
        Long2ObjectOpenHashMap<LevelChunk> chunks, 
        net.minecraft.world.level.LevelHeightAccessor worldBounds
    ) {
        public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            long chunkKey = ChunkPos.asLong(cx, cz);
            LevelChunk chunk = chunks.get(chunkKey);
            if (chunk == null) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            
            int sectionIndex = chunk.getSectionIndex(pos.getY());
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            
            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section == null || section.hasOnlyAir()) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            
            return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    /**
     * Calculates blocks to be destroyed asynchronously.
     * Uses a spherical check for maximum performance on large scales.
     */
    public static CompletableFuture<List<BlockPos>> calculateExplosionAsync(net.minecraft.world.level.BlockGetter level, net.minecraft.world.level.LevelHeightAccessor worldBounds, Vec3 center, float radius) {
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
                            if (worldBounds.isOutsideBuildHeight(pos.getY())) continue;
                            affectedBlocks.add(pos.immutable());
                        }
                    }
                }
            }
            return affectedBlocks;
        }, CALCULATION_POOL);
    }

    /**
     * Advanced BFS-based calculation using FastWorldView for O(1) world access.
     */
    public static CompletableFuture<CalculationResult> calculateResistantExplosionAsync(FastWorldView worldView, Vec3 center, float radius) {
        return CompletableFuture.supplyAsync(() -> {
            long totalStart = System.nanoTime();
            long worldAccessTime = 0;
            int iterations = 0;

            LongOpenHashSet affectedBlocksLong = new LongOpenHashSet();
            Long2FloatOpenHashMap resistanceMap = new Long2FloatOpenHashMap();
            resistanceMap.defaultReturnValue(-1f);
            
            PriorityQueue<ExplosionPoint> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> -p.intensity));

            BlockPos startPos = BlockPos.containing(center);
            long startLong = startPos.asLong();
            queue.add(new ExplosionPoint(startPos, radius));
            resistanceMap.put(startLong, radius);

            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

            while (!queue.isEmpty()) {
                ExplosionPoint current = queue.poll();
                if (current.intensity <= 0) continue;

                affectedBlocksLong.add(current.pos.asLong());
                iterations++;

                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    int nx = current.pos.getX() + dir.getStepX();
                    int ny = current.pos.getY() + dir.getStepY();
                    int nz = current.pos.getZ() + dir.getStepZ();
                    
                    if (worldView.worldBounds.isOutsideBuildHeight(ny)) continue;
                    mutablePos.set(nx, ny, nz);
                    long nextLong = mutablePos.asLong();

                    long waStart = System.nanoTime();
                    // Direct access via FastWorldView
                    float resistance = worldView.getBlockState(mutablePos).getBlock().getExplosionResistance();
                    worldAccessTime += (System.nanoTime() - waStart);

                    float nextIntensity = current.intensity - (resistance + 0.3f) * 0.3f;

                    if (nextIntensity > 0 && nextIntensity > resistanceMap.get(nextLong)) {
                        resistanceMap.put(nextLong, nextIntensity);
                        queue.add(new ExplosionPoint(new BlockPos(nx, ny, nz), nextIntensity));
                    }
                }
            }

            long totalTime = System.nanoTime() - totalStart;
            double msTotal = totalTime / 1_000_000.0;
            double msWorld = worldAccessTime / 1_000_000.0;
            
            List<BlockPos> finalBlocks = new ArrayList<>(affectedBlocksLong.size());
            for (long l : affectedBlocksLong) {
                finalBlocks.add(BlockPos.of(l));
            }

            return new CalculationResult(finalBlocks, msTotal, msWorld);
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
