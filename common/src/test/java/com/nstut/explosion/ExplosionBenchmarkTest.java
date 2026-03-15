package com.nstut.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

/**
 * Headless benchmark for the explosion engine.
 */
public class ExplosionBenchmarkTest {

    @BeforeAll
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final LevelHeightAccessor mockBounds = new LevelHeightAccessor() {
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    };

    @Test
    public void benchmarkStoneExplosion() {
        System.out.println("=== Stone Explosion Benchmark ===");
        BlockGetter stoneLevel = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return Blocks.STONE.defaultBlockState(); }
            @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };

        float intensity = 25.0f;
        int iterations = 3;
        
        // Warmup
        FastExplosionEngine.calculateResistantExplosionAsync(stoneLevel, mockBounds, Vec3.ZERO, intensity).join();

        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            List<BlockPos> blocks = FastExplosionEngine.calculateResistantExplosionAsync(stoneLevel, mockBounds, Vec3.ZERO, intensity).join();
            System.out.println("Iteration " + (i + 1) + ": Found " + blocks.size() + " blocks.");
        }
        
        long end = System.currentTimeMillis();
        System.out.println("Average BFS time for intensity " + intensity + " in STONE: " + (end - start) / iterations + "ms");
        System.out.println("=================================");
    }

    @Test
    public void benchmarkMixedWorldExplosion() {
        System.out.println("=== Realistic Mixed World Benchmark ===");
        // Mock a world with layers: Stone (-64 to 0), Dirt (0 to 64), Air (64+)
        BlockGetter mixedLevel = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) {
                if (pos.getY() < 0) return Blocks.STONE.defaultBlockState();
                if (pos.getY() < 64) return Blocks.DIRT.defaultBlockState();
                return Blocks.AIR.defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };

        float intensity = 5.0f; 
        
        long start = System.currentTimeMillis();
        List<BlockPos> blocks = FastExplosionEngine.calculateResistantExplosionAsync(mixedLevel, mockBounds, new Vec3(0, 60, 0), intensity).join();
        long end = System.currentTimeMillis();
        
        System.out.println("Found " + blocks.size() + " blocks for intensity " + intensity + " at Y=60 (Forest scenario).");
        System.out.println("Calculation Time: " + (end - start) + "ms");
        System.out.println("=======================================");
    }

    @Test
    public void benchmarkLargeScaleDestructionEfficiency() {
        System.out.println("=== Destruction Efficiency Benchmark ===");
        // This simulates the ExplosionScheduler.processBatch logic
        int blockCount = 100000;
        List<BlockPos> blocks = new ArrayList<>(blockCount);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet blocksLong = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(blockCount);
        
        for (int i = 0; i < blockCount; i++) {
            BlockPos p = new BlockPos(i, 0, 0);
            blocks.add(p);
            blocksLong.add(p.asLong());
        }

        long start = System.currentTimeMillis();
        for (BlockPos pos : blocks) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                if (!blocksLong.contains(pos.relative(dir).asLong())) {
                    // boundary check
                }
            }
        }
        long end = System.currentTimeMillis();

        System.out.println("Processed " + blockCount + " blocks for boundary check.");
        System.out.println("Destruction Processing Time (O(1) approach): " + (end - start) + "ms");
        System.out.println("========================================");
    }

    @Test
    public void benchmarkLightingSubmissionOverhead() {
        System.out.println("=== Lighting Submission Benchmark ===");
        int blockCount = 100000;
        List<BlockPos> boundaries = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            boundaries.add(new BlockPos(i, 0, 0));
        }

        long start = System.currentTimeMillis();
        for (BlockPos pos : boundaries) {
            dummyCheckBlock(pos);
        }
        long end = System.currentTimeMillis();

        System.out.println("Simulated submission of " + blockCount + " blocks to Light Engine.");
        System.out.println("Submission Time (Iteration Overhead): " + (end - start) + "ms");
        System.out.println("======================================");
    }

    private void dummyCheckBlock(BlockPos pos) {
        // Mock method to measure iteration/call overhead
    }
}
