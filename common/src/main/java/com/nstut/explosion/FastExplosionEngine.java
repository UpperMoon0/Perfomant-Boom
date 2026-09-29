package com.nstut.explosion;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Set;

/**
 * Vanilla-compatible explosion ray calculation with a time-sliceable execution model.
 *
 * <p>The old implementation walked the world from a worker thread through live chunk
 * sections. Minecraft chunk palettes are mutable server-thread state, so that was both
 * unsafe and capable of racing chunk/block updates. This implementation keeps all world
 * reads on the server thread and makes the expensive work incremental instead.</p>
 */
public final class FastExplosionEngine {
    private static final int RAY_GRID = 16;
    private static final double RAY_STEP = (double)0.3F;
    private static final float RESISTANCE_STEP = 0.3F;
    private static final float AIR_STEP_DECAY = 0.22500001F;
    private static final double[][] RAY_DIRECTIONS = createRayDirections();

    private FastExplosionEngine() {
    }

    // Vanilla 1.20.1 Explosion#explode draws ray strengths from the LEVEL RNG.
    // Reserve the same sequence before yielding; never change gameplay RNG for a benchmark.
    public static IncrementalCalculation create(ServerLevel level, Vec3 center, float power) {
        return new IncrementalCalculation(
            new ServerWorldView(level),
            center,
            power,
            level.getRandom()
        );
    }

    static IncrementalCalculation create(BlockView world, Vec3 center, float power, RandomSource random) {
        return new IncrementalCalculation(world, center, power, random);
    }

    /**
     * Read-only block view used by the calculator. Runtime access is server-thread only;
     * tests can provide immutable synthetic views.
     */
    interface BlockView {
        boolean isInWorldBounds(int x, int y, int z);

        BlockState getBlockState(int x, int y, int z);
    }

    /**
     * Compatibility/testing view backed by pre-created chunks. It is intentionally not
     * used by the runtime async path anymore.
     */
    public record FastWorldView(
        Long2ObjectOpenHashMap<ChunkAccess> chunks,
        LevelHeightAccessor worldBounds
    ) implements BlockView {
        @Override
        public boolean isInWorldBounds(int x, int y, int z) {
            return !worldBounds.isOutsideBuildHeight(y)
                && x >= -30_000_000 && z >= -30_000_000
                && x < 30_000_000 && z < 30_000_000;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            ChunkAccess chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (chunk == null) {
                return Blocks.AIR.defaultBlockState();
            }

            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return Blocks.AIR.defaultBlockState();
            }

            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                return Blocks.AIR.defaultBlockState();
            }

            return section.getBlockState(x & 15, y & 15, z & 15);
        }
    }

    private static final class ServerWorldView implements BlockView {
        private final ServerLevel level;
        private final Long2ObjectOpenHashMap<LevelChunk> chunkCache = new Long2ObjectOpenHashMap<>();

        private ServerWorldView(ServerLevel level) {
            this.level = level;
        }

        @Override
        public boolean isInWorldBounds(int x, int y, int z) {
            if (level.isOutsideBuildHeight(y)) {
                return false;
            }
            return x >= -30_000_000 && z >= -30_000_000
                && x < 30_000_000 && z < 30_000_000;
        }

        @Override
        public BlockState getBlockState(int x, int y, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
            LevelChunk chunk = chunkCache.get(chunkKey);
            if (chunk == null) {
                chunk = level.getChunk(chunkX, chunkZ);
                chunkCache.put(chunkKey, chunk);
            }

            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return Blocks.AIR.defaultBlockState();
            }

            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                return Blocks.AIR.defaultBlockState();
            }

            return section.getBlockState(x & 15, y & 15, z & 15);
        }

        private void clearCache() {
            chunkCache.clear();
        }
    }

    public static final class IncrementalCalculation {
        private final BlockView world;
        private final Vec3 center;
        private final float power;
        private final float[] rayStrengths;
        // Vanilla 1.20.1 collects every affected position, including air, in a
        // java.util.HashSet before finalization. Preserve that exact collection shape:
        // its iteration order is the input to Util.shuffle and therefore affects both
        // destruction order and the level RNG state.
        private final HashSet<BlockPos> affectedBlocks = new HashSet<>();
        private final LongOpenHashSet blocks = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<LongArrayList> blocksByChunk = new Long2ObjectOpenHashMap<>();

        private int nextRayIndex;
        private boolean rayActive;
        private double rayX;
        private double rayY;
        private double rayZ;
        private double rayDx;
        private double rayDy;
        private double rayDz;
        private float rayStrength;
        private long sampleCount;

        private IncrementalCalculation(BlockView world, Vec3 center, float power, RandomSource random) {
            this.world = world;
            this.center = center;
            this.power = power;
            this.rayStrengths = new float[RAY_DIRECTIONS.length];
            for (int i = 0; i < rayStrengths.length; i++) {
                rayStrengths[i] = power * (0.7F + random.nextFloat() * 0.6F);
            }
        }

        /**
         * Processes work until the supplied deadline. Returns true when calculation is complete.
         */
        public boolean processUntil(long deadlineNanos) {
            // ServerChunkCache#getChunk's UNKNOWN ticket expires after one tick.
            // Cache only inside this call; never retain LevelChunks across a yield.
            try {
                return processSlice(deadlineNanos);
            } finally {
                if (world instanceof ServerWorldView serverWorldView) {
                    serverWorldView.clearCache();
                }
            }
        }

        private boolean processSlice(long deadlineNanos) {
            int checksUntilDeadline = 32;

            while (rayActive || nextRayIndex < RAY_DIRECTIONS.length) {
                if (!rayActive) {
                    beginNextRay();
                }

                processRayStep();
                sampleCount++;

                if (--checksUntilDeadline == 0) {
                    checksUntilDeadline = 32;
                    if (System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
            }

            return true;
        }

        public boolean isDone() {
            return !rayActive && nextRayIndex >= RAY_DIRECTIONS.length;
        }

        public int blockCount() {
            return blocks.size();
        }

        public long sampleCount() {
            return sampleCount;
        }

        public LongOpenHashSet blocks() {
            return blocks;
        }

        public Set<BlockPos> affectedBlocks() {
            return affectedBlocks;
        }

        public Long2ObjectOpenHashMap<LongArrayList> blocksByChunk() {
            return blocksByChunk;
        }

        public float power() {
            return power;
        }

        private void beginNextRay() {
            double[] direction = RAY_DIRECTIONS[nextRayIndex];
            rayStrength = rayStrengths[nextRayIndex];
            nextRayIndex++;

            rayX = center.x;
            rayY = center.y;
            rayZ = center.z;
            rayDx = direction[0];
            rayDy = direction[1];
            rayDz = direction[2];
            rayActive = rayStrength > 0.0F;
        }

        private void processRayStep() {
            int x = Mth.floor(rayX);
            int y = Mth.floor(rayY);
            int z = Mth.floor(rayZ);

            if (!world.isInWorldBounds(x, y, z)) {
                rayActive = false;
                return;
            }

            BlockState state = world.getBlockState(x, y, z);
            FluidState fluidState = state.getFluidState();

            if (!state.isAir() || !fluidState.isEmpty()) {
                float resistance = Math.max(
                    state.getBlock().getExplosionResistance(),
                    fluidState.getExplosionResistance()
                );
                rayStrength -= (resistance + 0.3F) * RESISTANCE_STEP;
            }

            if (rayStrength > 0.0F) {
                // Vanilla adds the position even when it is air. Do the same with the
                // same HashSet implementation so the later shuffled order and RNG state
                // match Explosion#finalizeExplosion exactly.
                BlockPos affected = new BlockPos(x, y, z);
                affectedBlocks.add(affected);

                // Keep the non-air indexes as a secondary fast lookup used by metrics,
                // light-boundary tests and existing diagnostics. Mutation order is NOT
                // derived from these grouped indexes.
                if (!state.isAir()) {
                    long packed = affected.asLong();
                    if (blocks.add(packed)) {
                        long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
                        blocksByChunk.computeIfAbsent(chunkKey, ignored -> new LongArrayList()).add(packed);
                    }
                }
            }

            rayX += rayDx * RAY_STEP;
            rayY += rayDy * RAY_STEP;
            rayZ += rayDz * RAY_STEP;
            rayStrength -= AIR_STEP_DECAY;
            if (rayStrength <= 0.0F) {
                rayActive = false;
            }
        }
    }

    private static double[][] createRayDirections() {
        double[][] directions = new double[RAY_GRID * RAY_GRID * RAY_GRID][];
        int count = 0;

        for (int x = 0; x < RAY_GRID; x++) {
            for (int y = 0; y < RAY_GRID; y++) {
                for (int z = 0; z < RAY_GRID; z++) {
                    if (x != 0 && x != RAY_GRID - 1
                        && y != 0 && y != RAY_GRID - 1
                        && z != 0 && z != RAY_GRID - 1) {
                        continue;
                    }

                    double dx = (float)x / (RAY_GRID - 1) * 2.0F - 1.0F;
                    double dy = (float)y / (RAY_GRID - 1) * 2.0F - 1.0F;
                    double dz = (float)z / (RAY_GRID - 1) * 2.0F - 1.0F;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    directions[count++] = new double[]{dx / length, dy / length, dz / length};
                }
            }
        }

        double[][] compact = new double[count][];
        System.arraycopy(directions, 0, compact, 0, count);
        return compact;
    }
}
