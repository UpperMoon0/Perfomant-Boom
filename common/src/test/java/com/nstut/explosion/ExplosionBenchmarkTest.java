package com.nstut.explosion;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.function.Function;

import static org.mockito.Mockito.mock;

/**
 * High-performance realistic benchmark using lightweight custom classes.
 * Satisfies the exact ChunkAccess signature requirement.
 */
public class ExplosionBenchmarkTest {

    private static Registry<Biome> mockBiomeRegistry;

    @BeforeAll
    @SuppressWarnings("unchecked")
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        mockBiomeRegistry = mock(Registry.class);
    }

    private static class LightweightDummyChunk extends ChunkAccess {
        public LightweightDummyChunk(int cx, int cz, Function<BlockPos, BlockState> layout) {
            super(
                new ChunkPos(cx, cz), 
                UpgradeData.EMPTY, 
                new LevelHeightAccessor() {
                    @Override public int getHeight() { return 384; }
                    @Override public int getMinBuildHeight() { return -64; }
                }, 
                mockBiomeRegistry, 
                0L, 
                createSections(cx, cz, layout), 
                null
            );
        }

        private static LevelChunkSection[] createSections(int cx, int cz, Function<BlockPos, BlockState> layout) {
            LevelChunkSection[] sections = new LevelChunkSection[24];
            for (int i = 0; i < 24; i++) {
                sections[i] = new DummySection(i, cx, cz, layout);
            }
            return sections;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            int sectionIndex = getSectionIndex(pos.getY());
            if (sectionIndex < 0 || sectionIndex >= sections.length) return Blocks.AIR.defaultBlockState();
            return sections[sectionIndex].getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState setBlockState(BlockPos pos, BlockState state, boolean moved) { return null; }
        @Override public void setBlockEntity(BlockEntity entity) {}
        @Override public void addEntity(net.minecraft.world.entity.Entity entity) {}
        @Override public ChunkStatus getStatus() { return ChunkStatus.FULL; }
        @Override public void removeBlockEntity(BlockPos pos) {}
        @Override public net.minecraft.nbt.CompoundTag getBlockEntityNbtForSaving(BlockPos pos) { return null; }
        @Override public net.minecraft.world.ticks.TickContainerAccess<net.minecraft.world.level.block.Block> getBlockTicks() { return net.minecraft.world.ticks.BlackholeTickAccess.emptyContainer(); }
        @Override public net.minecraft.world.ticks.TickContainerAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() { return net.minecraft.world.ticks.BlackholeTickAccess.emptyContainer(); }
        @Override public TicksToSave getTicksForSerialization() { return new TicksToSave(null, null); }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
    }

    private static class DummySection extends LevelChunkSection {
        private final int sectionY;
        private final int cx, cz;
        private final Function<BlockPos, BlockState> layout;

        public DummySection(int index, int cx, int cz, Function<BlockPos, BlockState> layout) {
            super(mockBiomeRegistry); 
            this.sectionY = (index - 4) << 4;
            this.cx = cx;
            this.cz = cz;
            this.layout = layout;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            return layout.apply(new BlockPos((cx << 4) + x, sectionY + y, (cz << 4) + z));
        }

        @Override
        public boolean hasOnlyAir() { return false; }

        @Override public FluidState getFluidState(int x, int y, int z) { return Fluids.EMPTY.defaultFluidState(); }
    }

    private static FastExplosionEngine.FastWorldView createRealisticWorldView(Function<BlockPos, BlockState> layout) {
        Long2ObjectOpenHashMap<ChunkAccess> chunks = new Long2ObjectOpenHashMap<>();
        int range = 10;
        for (int x = -range; x <= range; x++) {
            for (int z = -range; z <= range; z++) {
                chunks.put(ChunkPos.asLong(x, z), new LightweightDummyChunk(x, z, layout));
            }
        }

        return new FastExplosionEngine.FastWorldView(chunks, new LevelHeightAccessor() {
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        });
    }

    @Test
    public void benchmarkForestExplosion() {
        System.out.println("=== Forest Scenario Benchmark (Dirt/Grass/Air) ===");
        FastExplosionEngine.FastWorldView worldView = createRealisticWorldView(pos -> {
            if (pos.getY() > 64) return Blocks.AIR.defaultBlockState();
            if (pos.getY() == 64) return Blocks.GRASS_BLOCK.defaultBlockState();
            return Blocks.DIRT.defaultBlockState();
        });

        runBenchmark(worldView, new Vec3(0, 64, 0), 10.0f, "Forest");
    }

    @Test
    public void benchmarkUndergroundExplosion() {
        System.out.println("=== Underground Scenario Benchmark (Deepslate/Stone) ===");
        FastExplosionEngine.FastWorldView worldView = createRealisticWorldView(pos -> {
            if (pos.getY() < 0) return Blocks.DEEPSLATE.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        });

        runBenchmark(worldView, new Vec3(0, -10, 0), 15.0f, "Underground");
    }

    @Test
    public void benchmarkMassiveExplosion() {
        System.out.println("=== Massive Scale Benchmark (Radius 60) ===");
        FastExplosionEngine.FastWorldView worldView = createRealisticWorldView(pos -> Blocks.STONE.defaultBlockState());

        runBenchmark(worldView, new Vec3(0, 0, 0), 60.0f, "Massive");
    }

    private void runBenchmark(FastExplosionEngine.FastWorldView worldView, Vec3 pos, float radius, String name) {
        // Warmup
        FastExplosionEngine.calculateResistantExplosionAsync(worldView, pos, radius).join();

        int iterations = 3;
        long totalTime = 0;
        int lastCount = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            List<BlockPos> blocks = FastExplosionEngine.calculateResistantExplosionAsync(worldView, pos, radius).join();
            long end = System.nanoTime();
            
            totalTime += (end - start);
            lastCount = blocks.size();
            System.out.printf("  Iteration %d: %d blocks in %.2fms\n", i + 1, lastCount, (end - start) / 1_000_000.0);
        }

        System.out.printf("Average Time [%s]: %.2fms for %d blocks\n", name, (totalTime / (double)iterations) / 1_000_000.0, lastCount);
        System.out.println("==========================================");
    }
}
