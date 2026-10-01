package com.nstut.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FastExplosionEngineChunkCacheTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test
    void runtimeChunkFastPathReacquiresReplacementAfterYield() {
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
        var center = new Vec3(8.5, 96.5, 8.5);
        try (var adapter = org.mockito.Mockito.mockStatic(VanillaExplosionAdapter.class)) {
            adapter.when(() -> VanillaExplosionAdapter.chunkKey(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenCallRealMethod();
            adapter.when(() -> VanillaExplosionAdapter.create(level, center, 10))
                .thenReturn(org.mockito.Mockito.mock(net.minecraft.world.level.Explosion.class));
            var calculation = FastExplosionEngine.create(level, center, 10);
            assertFalse(calculation.processUntil(Long.MIN_VALUE));
            current.set(replacement);
            org.mockito.Mockito.clearInvocations(first, replacement, level);
            assertFalse(calculation.processUntil(Long.MIN_VALUE));
            org.mockito.Mockito.verify(replacement, org.mockito.Mockito.atLeastOnce()).getSections();
            org.mockito.Mockito.verify(first, org.mockito.Mockito.never()).getSections();
        }
    }

}
