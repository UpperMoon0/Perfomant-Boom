package com.nstut.explosion;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChunkBlockModifierTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class);
        final LevelChunk chunk = mock(LevelChunk.class);
        final LevelChunkSection section = mock(LevelChunkSection.class);
        final LevelLightEngine light = mock(LevelLightEngine.class);
        final ChunkSkyLightSources sky = mock(ChunkSkyLightSources.class);
        final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean();
        final ChunkBlockModifier.MutationContext mutation;
        Fixture() {
            when(chunk.getSections()).thenReturn(new LevelChunkSection[]{section});
            when(chunk.getPos()).thenReturn(new ChunkPos(0,0));
            when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
            when(chunk.getSkyLightSources()).thenReturn(sky);
            when(chunk.getOrCreateHeightmapUnprimed(any())).thenAnswer(call -> mock(Heightmap.class));
            when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.NETHERRACK.defaultBlockState());
            when(level.getLightEngine()).thenReturn(light);
            doAnswer(call -> { dirty.set(call.getArgument(0)); return null; }).when(chunk).setUnsaved(anyBoolean());
            when(chunk.isUnsaved()).thenAnswer(call -> dirty.get());
            mutation = ChunkBlockModifier.begin(level, chunk);
        }
    }

    @Test void everyRemovalIsDirtyBeforeFinishEvenAfterInterveningSave() {
        var f = new Fixture();
        assertNotNull(f.mutation.remove(new BlockPos.MutableBlockPos(1,1,1)));
        assertTrue(f.chunk.isUnsaved(), "partial mutation must be save eligible without finish()");
        f.chunk.setUnsaved(false); // ChunkMap.save clears this flag.
        assertNotNull(f.mutation.remove(new BlockPos.MutableBlockPos(2,1,1)));
        assertTrue(f.chunk.isUnsaved(), "a subsequent slice must redirty after save");
        verify(f.chunk, times(2)).setUnsaved(true);
        verify(f.section, times(2)).setBlockState(anyInt(), anyInt(), anyInt(), eq(Blocks.AIR.defaultBlockState()), eq(false));
    }

    @Test void partialRemovalUpdatesSkylightAndQueuesVanillaLightBeforeFinish() {
        var f = new Fixture();
        var pos = new BlockPos.MutableBlockPos(1,1,1);
        f.mutation.remove(pos);
        verify(f.sky).update(f.chunk,1,1,1);
        verify(f.light).checkBlock(pos);
    }

    @Test void lastBlockReportsEmptySectionBeforeFinish() {
        var f = new Fixture();
        when(f.section.hasOnlyAir()).thenReturn(false, true);
        f.mutation.remove(new BlockPos.MutableBlockPos(1,1,1));
        verify(f.light).updateSectionStatus(SectionPos.of(new ChunkPos(0,0),0),true);
    }

    @Test void everyRemovalDispatchesNeighborsAndAllShapesBeforeFinish() {
        var f = new Fixture();
        var notifications = new java.util.ArrayList<BlockPos>();
        record Shape(Direction direction, BlockPos target, BlockPos source) {}
        var shapes = new java.util.ArrayList<Shape>();
        doAnswer(call -> {
            assertTrue(f.chunk.isUnsaved(), "write must be dirty before physics callbacks");
            BlockPos source = call.getArgument(0);
            assertFalse(source instanceof BlockPos.MutableBlockPos, "callbacks must not retain the reused cursor");
            notifications.add(source);
            return null;
        }).when(f.level).updateNeighborsAt(any(BlockPos.class), eq(Blocks.NETHERRACK));
        doAnswer(call -> {
            BlockPos target = call.getArgument(2), source = call.getArgument(3);
            shapes.add(new Shape(call.getArgument(0), target.immutable(), source));
            return null;
        }).when(f.level).neighborShapeChanged(any(Direction.class), eq(Blocks.AIR.defaultBlockState()),
            any(BlockPos.class), any(BlockPos.class), eq(2), eq(511));
        var cursor = new BlockPos.MutableBlockPos(1,1,1);
        f.mutation.remove(cursor);
        cursor.set(2,1,1);
        f.mutation.remove(cursor);
        cursor.set(9,9,9);
        assertEquals(java.util.List.of(new BlockPos(1,1,1), new BlockPos(2,1,1)), notifications);
        assertEquals(12, shapes.size(), "all six faces of BOTH writes need shape propagation before finish()");
        for (BlockPos source : notifications) for (Direction direction : Direction.values()) {
            assertTrue(shapes.contains(new Shape(direction.getOpposite(), source.relative(direction), source)));
        }
    }

    @Test void removalDispatchesOldStatesIndirectShapesBeforeFinish() {
        var f = new Fixture();
        var oldState = spy(Blocks.NETHERRACK.defaultBlockState());
        when(f.section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(oldState);
        var pos = new BlockPos.MutableBlockPos(1,1,1);
        f.mutation.remove(pos);
        verify(oldState).updateIndirectNeighbourShapes(f.level, pos.immutable(), 2, 511);
        verify(f.level).onBlockStateChange(pos.immutable(), oldState, Blocks.AIR.defaultBlockState());
    }

    @Test void removalHookReplacementDoesNotReceiveStaleAirShapes() {
        var f = new Fixture();
        when(f.chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());
        assertNotNull(f.mutation.remove(new BlockPos.MutableBlockPos(1,1,1)));
        verify(f.level, never()).updateNeighborsAt(any(), any());
        verify(f.level, never()).neighborShapeChanged(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test void noOpDoesNotDirtyTheChunk() {
        var f = new Fixture();
        when(f.section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.AIR.defaultBlockState());
        assertNull(f.mutation.remove(new BlockPos.MutableBlockPos(1,1,1)));
        verify(f.chunk,never()).setUnsaved(true);
        verifyNoInteractions(f.sky, f.light);
        verify(f.level, never()).updateNeighborsAt(any(), any());
        verify(f.level, never()).neighborShapeChanged(any(), any(), any(), any(), anyInt(), anyInt());
    }
}
