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
    public void benchmarkAirExplosion() {
        System.out.println("=== Air Explosion Benchmark ===");
        BlockGetter airLevel = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
            @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };

        float intensity = 2.0f;
        
        long start = System.currentTimeMillis();
        List<BlockPos> airBlocks = FastExplosionEngine.calculateResistantExplosionAsync(airLevel, mockBounds, Vec3.ZERO, intensity).join();
        long end = System.currentTimeMillis();
        
        System.out.println("Found " + airBlocks.size() + " blocks for intensity " + intensity + " in AIR.");
        System.out.println("Time: " + (end - start) + "ms");
        System.out.println("===============================");
    }
}
