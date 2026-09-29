package com.nstut.explosion;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.Util;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastExplosionEngineTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void queuedExplosionsDoNotReserveLaterRayRngBeforeActiveShuffle() throws Exception {
        var level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        var actual = RandomSource.create(0x51A77E5EEDL);
        var expected = RandomSource.create(0x51A77E5EEDL);
        org.mockito.Mockito.when(level.getRandom()).thenReturn(actual);

        var queueField = ExplosionScheduler.class.getDeclaredField("TASK_QUEUE");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var queue = (java.util.Queue<Object>) queueField.get(null);
        var serverField = ExplosionScheduler.class.getDeclaredField("activeServer");
        serverField.setAccessible(true);

        queue.clear();
        serverField.set(null, null);
        try {
            ExplosionScheduler.scheduleTracked(level, new Vec3(0.5D, 96.5D, 0.5D), 10.0F, null);
            ExplosionScheduler.scheduleTracked(level, new Vec3(32.5D, 96.5D, 0.5D), 6.0F, null);

            assertEquals(2, queue.size());
            org.mockito.Mockito.verify(level, org.mockito.Mockito.never()).getRandom();
            assertEquals(expected.nextLong(), actual.nextLong(),
                "enqueuing later explosions must not consume Level.random before the active task runs");
        } finally {
            queue.clear();
            serverField.set(null, null);
        }
    }

    @Test
    void runtimeReservesVanillaLevelRandomRaySequence() {
        var level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        var actual = RandomSource.create(0xB00B5EEDL);
        var expected = RandomSource.create(0xB00B5EEDL);
        org.mockito.Mockito.when(level.getRandom()).thenReturn(actual);
        FastExplosionEngine.create(level, new Vec3(0.5, 96.5, 0.5), 10);
        // The 16-cube boundary contains 16^3 - 14^3 = 1352 rays.
        for (int i=0; i<1352; i++) expected.nextFloat();
        org.junit.jupiter.api.Assertions.assertEquals(expected.nextFloat(), actual.nextFloat());
        org.mockito.Mockito.verify(level).getRandom();
    }

    @Test
    void runtimeReacquiresReplacedChunkAfterEveryYield() {
        var level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        var first = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        var replacement = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        var section = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunkSection.class);
        var bedrock = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunkSection.class);
        org.mockito.Mockito.when(section.getBlockState(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(Blocks.NETHERRACK.defaultBlockState());
        org.mockito.Mockito.when(bedrock.getBlockState(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(Blocks.BEDROCK.defaultBlockState());
        org.mockito.Mockito.when(first.getSections()).thenReturn(new net.minecraft.world.level.chunk.LevelChunkSection[]{section});
        org.mockito.Mockito.when(replacement.getSections()).thenReturn(new net.minecraft.world.level.chunk.LevelChunkSection[]{bedrock});
        var current = new java.util.concurrent.atomic.AtomicReference<>(first);
        org.mockito.Mockito.when(level.getChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer(call -> current.get());
        org.mockito.Mockito.when(level.getRandom()).thenReturn(RandomSource.create(71));
        var calculation = FastExplosionEngine.create(level, new Vec3(8.5, 96.5, 8.5), 10);
        assertFalse(calculation.processUntil(Long.MIN_VALUE));
        current.set(replacement);
        org.mockito.Mockito.clearInvocations(first, replacement, level);
        assertFalse(calculation.processUntil(Long.MIN_VALUE));
        org.mockito.Mockito.verify(replacement, org.mockito.Mockito.atLeastOnce()).getSections();
        org.mockito.Mockito.verify(first, org.mockito.Mockito.never()).getSections();
    }

    @Test
    void matchesVanillaRaySelectionForNonAirBlocks() {
        FastExplosionEngine.BlockView world = world(pos -> {
            if (pos.getY() < -3) {
                return Blocks.DEEPSLATE.defaultBlockState();
            }
            if (pos.getY() <= 1) {
                return ((pos.getX() + pos.getZ()) & 3) == 0
                    ? Blocks.COBBLESTONE.defaultBlockState()
                    : Blocks.DIRT.defaultBlockState();
            }
            if (pos.getY() == 2 && (pos.getX() & 1) == 0) {
                return Blocks.OAK_PLANKS.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        });

        Vec3 center = new Vec3(0.5D, 1.5D, 0.5D);
        float power = 10.0F;
        long seed = 0x5EEDB00BL;

        FastExplosionEngine.IncrementalCalculation calculation =
            FastExplosionEngine.create(world, center, power, RandomSource.create(seed));
        assertTrue(calculation.processUntil(Long.MAX_VALUE));

        LongOpenHashSet expected = vanillaReferenceNonAir(world, center, power, seed);
        assertEquals(expected, calculation.blocks());
    }



    @Test
    void preservesVanillaAffectedShuffleAndLevelRandomStateIncludingAir() {
        FastExplosionEngine.BlockView world = world(pos ->
            ((pos.getX() * 31 + pos.getY() * 17 + pos.getZ()) & 7) == 0
                ? Blocks.STONE.defaultBlockState()
                : Blocks.AIR.defaultBlockState()
        );
        Vec3 center = new Vec3(0.5D, 4.5D, -0.5D);
        float power = 6.0F;
        long seed = 0x51A77E5EEDL;

        RandomSource actualRandom = RandomSource.create(seed);
        FastExplosionEngine.IncrementalCalculation calculation =
            FastExplosionEngine.create(world, center, power, actualRandom);
        assertTrue(calculation.processUntil(Long.MAX_VALUE));

        RandomSource vanillaRandom = RandomSource.create(seed);
        HashSet<BlockPos> expectedAffected =
            vanillaReferenceAffected(world, center, power, vanillaRandom);
        assertEquals(expectedAffected, calculation.affectedBlocks());
        assertTrue(calculation.affectedBlocks().size() > calculation.blockCount(),
            "fixture must include vanilla-selected air positions");

        ObjectArrayList<BlockPos> expectedOrder = new ObjectArrayList<>();
        expectedOrder.addAll(expectedAffected);
        Util.shuffle(expectedOrder, vanillaRandom);
        ObjectArrayList<BlockPos> actualOrder =
            ExplosionScheduler.vanillaDestructionOrder(calculation, actualRandom);

        assertEquals(expectedOrder, actualOrder,
            "destruction order must be vanilla's shuffled HashSet order");
        assertEquals(vanillaRandom.nextLong(), actualRandom.nextLong(),
            "level RNG must match vanilla after ray selection + full affected-position shuffle");
    }

    @Test
    void serverSideSoundPitchUsesIndependentRandomSource() {
        RandomSource levelRandom = RandomSource.create(0x51504CL);
        RandomSource expectedLevel = RandomSource.create(0x51504CL);
        RandomSource effectsRandom = RandomSource.create(0xEFFEC75L);

        float pitch = ExplosionScheduler.serverSideSoundPitch(effectsRandom);

        assertTrue(pitch >= 0.56F && pitch <= 0.84F);
        assertEquals(expectedLevel.nextLong(), levelRandom.nextLong(),
            "server-side sound generation must not consume ServerLevel.random");
    }

    @Test
    void centerBedrockIsNotDestroyedBeforeResistanceIsApplied() {
        FastExplosionEngine.BlockView world = world(pos ->
            pos.equals(BlockPos.ZERO)
                ? Blocks.BEDROCK.defaultBlockState()
                : Blocks.AIR.defaultBlockState()
        );

        FastExplosionEngine.IncrementalCalculation calculation =
            FastExplosionEngine.create(world, Vec3.ZERO, 500.0F, RandomSource.create(1L));
        assertTrue(calculation.processUntil(Long.MAX_VALUE));

        assertFalse(calculation.blocks().contains(BlockPos.ZERO.asLong()));
        assertEquals(0, calculation.blockCount());
    }

    @Test
    void timeSlicingDoesNotChangeTheResult() {
        FastExplosionEngine.BlockView world = world(pos ->
            pos.getY() <= 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()
        );
        Vec3 center = new Vec3(2.25D, 1.25D, -3.75D);
        float power = 14.0F;
        long seed = 123456789L;

        FastExplosionEngine.IncrementalCalculation oneShot =
            FastExplosionEngine.create(world, center, power, RandomSource.create(seed));
        assertTrue(oneShot.processUntil(Long.MAX_VALUE));

        FastExplosionEngine.IncrementalCalculation sliced =
            FastExplosionEngine.create(world, center, power, RandomSource.create(seed));
        while (!sliced.isDone()) {
            sliced.processUntil(System.nanoTime() - 1L);
        }

        assertEquals(oneShot.blocks(), sliced.blocks());
        assertEquals(oneShot.sampleCount(), sliced.sampleCount());
    }

    @Test
    void airOnlyExplosionDoesNotStoreNoOpBlockWrites() {
        FastExplosionEngine.BlockView world = world(pos -> Blocks.AIR.defaultBlockState());

        FastExplosionEngine.IncrementalCalculation calculation =
            FastExplosionEngine.create(world, Vec3.ZERO, 60.0F, RandomSource.create(7L));
        assertTrue(calculation.processUntil(Long.MAX_VALUE));

        assertEquals(0, calculation.blockCount());
        assertTrue(calculation.blocksByChunk().isEmpty());
        // The vanilla 16^3 surface-ray algorithm is linear in ray length, not
        // volumetric in explosion size like the previous BFS.
        assertTrue(calculation.sampleCount() < 1_000_000L);
    }

    private static FastExplosionEngine.BlockView world(Function<BlockPos, BlockState> stateAt) {
        return new FastExplosionEngine.BlockView() {
            @Override
            public boolean isInWorldBounds(int x, int y, int z) {
                return y >= -64 && y < 320
                    && x >= -30_000_000 && z >= -30_000_000
                    && x < 30_000_000 && z < 30_000_000;
            }

            @Override
            public BlockState getBlockState(int x, int y, int z) {
                return stateAt.apply(new BlockPos(x, y, z));
            }
        };
    }



    private static HashSet<BlockPos> vanillaReferenceAffected(
        FastExplosionEngine.BlockView world,
        Vec3 center,
        float power,
        RandomSource random
    ) {
        HashSet<BlockPos> result = new HashSet<>();

        for (int rayX = 0; rayX < 16; rayX++) {
            for (int rayY = 0; rayY < 16; rayY++) {
                rayLoop:
                for (int rayZ = 0; rayZ < 16; rayZ++) {
                    if (rayX != 0 && rayX != 15
                        && rayY != 0 && rayY != 15
                        && rayZ != 0 && rayZ != 15) {
                        continue;
                    }

                    double dx = (float)rayX / 15.0F * 2.0F - 1.0F;
                    double dy = (float)rayY / 15.0F * 2.0F - 1.0F;
                    double dz = (float)rayZ / 15.0F * 2.0F - 1.0F;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= length;
                    dy /= length;
                    dz /= length;

                    double x = center.x;
                    double y = center.y;
                    double z = center.z;

                    for (float strength = power * (0.7F + random.nextFloat() * 0.6F);
                         strength > 0.0F;
                         strength -= 0.22500001F) {
                        int blockX = Mth.floor(x);
                        int blockY = Mth.floor(y);
                        int blockZ = Mth.floor(z);
                        if (!world.isInWorldBounds(blockX, blockY, blockZ)) {
                            continue rayLoop;
                        }

                        BlockState state = world.getBlockState(blockX, blockY, blockZ);
                        var fluid = state.getFluidState();
                        if (!state.isAir() || !fluid.isEmpty()) {
                            strength -= (
                                Math.max(
                                    state.getBlock().getExplosionResistance(),
                                    fluid.getExplosionResistance()
                                ) + 0.3F
                            ) * 0.3F;
                        }

                        if (strength > 0.0F) {
                            result.add(new BlockPos(blockX, blockY, blockZ));
                        }

                        x += dx * (double)0.3F;
                        y += dy * (double)0.3F;
                        z += dz * (double)0.3F;
                    }
                }
            }
        }

        return result;
    }

    /**
     * Direct transcription of the relevant 1.20.1 vanilla Explosion#explode ray loop,
     * with air positions omitted because they produce no block mutation.
     */
    private static LongOpenHashSet vanillaReferenceNonAir(
        FastExplosionEngine.BlockView world,
        Vec3 center,
        float power,
        long seed
    ) {
        LongOpenHashSet result = new LongOpenHashSet();
        RandomSource random = RandomSource.create(seed);

        for (int rayX = 0; rayX < 16; rayX++) {
            for (int rayY = 0; rayY < 16; rayY++) {
                rayLoop:
                for (int rayZ = 0; rayZ < 16; rayZ++) {
                    if (rayX != 0 && rayX != 15
                        && rayY != 0 && rayY != 15
                        && rayZ != 0 && rayZ != 15) {
                        continue;
                    }

                    double dx = (float)rayX / 15.0F * 2.0F - 1.0F;
                    double dy = (float)rayY / 15.0F * 2.0F - 1.0F;
                    double dz = (float)rayZ / 15.0F * 2.0F - 1.0F;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= length;
                    dy /= length;
                    dz /= length;

                    double x = center.x;
                    double y = center.y;
                    double z = center.z;

                    for (float strength = power * (0.7F + random.nextFloat() * 0.6F);
                         strength > 0.0F;
                         strength -= 0.22500001F) {
                        int blockX = Mth.floor(x);
                        int blockY = Mth.floor(y);
                        int blockZ = Mth.floor(z);
                        if (!world.isInWorldBounds(blockX, blockY, blockZ)) {
                            continue rayLoop;
                        }

                        BlockState state = world.getBlockState(blockX, blockY, blockZ);
                        var fluid = state.getFluidState();
                        if (!state.isAir() || !fluid.isEmpty()) {
                            strength -= (
                                Math.max(
                                    state.getBlock().getExplosionResistance(),
                                    fluid.getExplosionResistance()
                                ) + 0.3F
                            ) * 0.3F;
                        }

                        if (strength > 0.0F && !state.isAir()) {
                            result.add(BlockPos.asLong(blockX, blockY, blockZ));
                        }

                        x += dx * (double)0.3F;
                        y += dy * (double)0.3F;
                        z += dz * (double)0.3F;
                    }
                }
            }
        }

        return result;
    }
}
