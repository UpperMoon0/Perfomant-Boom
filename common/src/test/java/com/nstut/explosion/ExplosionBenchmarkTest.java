package com.nstut.explosion;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Headless benchmark for the explosion engine.
 */
public class ExplosionBenchmarkTest {

    @BeforeAll
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FastExplosionEngine.FastWorldView createMockWorldView(BlockState state) {
        LevelChunk mockChunk = mock(LevelChunk.class);
        LevelChunkSection mockSection = mock(LevelChunkSection.class);
        LevelChunkSection[] sections = new LevelChunkSection[24]; // Standard height
        Arrays.fill(sections, mockSection);

        when(mockChunk.getSections()).thenReturn(sections);
        when(mockChunk.getSectionIndex(anyInt())).thenReturn(10);
        when(mockChunk.getSection(anyInt())).thenReturn(mockSection);
        when(mockSection.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(state);

        Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
        // Add a wide range of chunks for the benchmark
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                chunks.put(ChunkPos.asLong(x, z), mockChunk);
            }
        }

        return new FastExplosionEngine.FastWorldView(chunks, new net.minecraft.world.level.LevelHeightAccessor() {
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        });
    }

    @Test
    public void benchmarkStoneExplosion() {
        System.out.println("=== Stone Explosion Benchmark ===");
        FastExplosionEngine.FastWorldView worldView = createMockWorldView(Blocks.STONE.defaultBlockState());

        float intensity = 25.0f;
        int iterations = 3;
        
        // Warmup
        FastExplosionEngine.calculateResistantExplosionAsync(worldView, Vec3.ZERO, intensity).join();

        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            List<BlockPos> blocks = FastExplosionEngine.calculateResistantExplosionAsync(worldView, Vec3.ZERO, intensity).join().blocks();
            System.out.println("Iteration " + (i + 1) + ": Found " + blocks.size() + " blocks.");
        }
        
        long end = System.currentTimeMillis();
        System.out.println("Average BFS time for intensity " + intensity + " in STONE: " + (end - start) / iterations + "ms");
        System.out.println("=================================");
    }

    @Test
    public void benchmarkMixedWorldExplosion() {
        System.out.println("=== Realistic Mixed World Benchmark ===");
        FastExplosionEngine.FastWorldView worldView = createMockWorldView(Blocks.DIRT.defaultBlockState());

        float intensity = 5.0f; 
        
        long start = System.currentTimeMillis();
        List<BlockPos> blocks = FastExplosionEngine.calculateResistantExplosionAsync(worldView, new Vec3(0, 60, 0), intensity).join().blocks();
        long end = System.currentTimeMillis();
        
        System.out.println("Found " + blocks.size() + " blocks for intensity " + intensity + " at Y=60 (Forest scenario).");
        System.out.println("Calculation Time: " + (end - start) + "ms");
        System.out.println("=======================================");
    }

    @Test
    public void benchmarkLargeScaleDestructionEfficiency() {
        System.out.println("=== Destruction Efficiency Benchmark ===");
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
                }
            }
        }
        long end = System.currentTimeMillis();

        System.out.println("Processed " + blockCount + " blocks for boundary check.");
        System.out.println("Destruction Processing Time (O(1) approach): " + (end - start) + "ms");
        System.out.println("========================================");
    }
}
