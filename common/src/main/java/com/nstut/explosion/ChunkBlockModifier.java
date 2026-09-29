package com.nstut.explosion;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.List;

/**
 * Performs direct chunk-section writes while restoring the world bookkeeping that
 * {@link LevelChunk#setBlockState} normally maintains.
 */
public final class ChunkBlockModifier {
    private ChunkBlockModifier() {
    }

    public static MutationContext begin(ServerLevel level, LevelChunk chunk) {
        return new MutationContext(level, chunk);
    }

    public static final class MutationContext {
        private final ServerLevel level;
        private final LevelChunk chunk;
        private final Heightmap worldSurface;
        private final Heightmap oceanFloor;
        private final Heightmap motionBlocking;
        private final Heightmap motionBlockingNoLeaves;
        private final Int2ObjectOpenHashMap<ShortOpenHashSet> changedBySection = new Int2ObjectOpenHashMap<>();

        private MutationContext(ServerLevel level, LevelChunk chunk) {
            this.level = level;
            this.chunk = chunk;
            this.worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            this.oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR);
            this.motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
            this.motionBlockingNoLeaves = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        }

        /**
         * Removes a block and completes its vanilla neighbor/shape notifications before
         * returning. General explosion loot is not processed here. Returns the previous
         * state, or {@code null} if there was nothing to change.
         */
        public BlockState remove(BlockPos.MutableBlockPos pos) {
            int y = pos.getY();
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return null;
            }

            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                return null;
            }

            int localX = pos.getX() & 15;
            int localY = y & 15;
            int localZ = pos.getZ() & 15;
            BlockState oldState = section.getBlockState(localX, localY, localZ);
            if (oldState.isAir()) {
                return null;
            }

            BlockState air = Blocks.AIR.defaultBlockState();
            section.setBlockState(localX, localY, localZ, air, false);
            // A normal save may run between ANY two scheduler slices and clear this
            // flag. Dirty every successful write, not just the first or final slice.
            chunk.setUnsaved(true);

            // Mirror LevelChunk#setBlockState's heightmap maintenance. These calls are
            // O(1) for blocks below the current height and only scan downward when the
            // removed block was the column's current top.
            worldSurface.update(localX, y, localZ, air);
            oceanFloor.update(localX, y, localZ, air);
            motionBlocking.update(localX, y, localZ, air);
            motionBlockingNoLeaves.update(localX, y, localZ, air);

            // Mirror the decompiled 1.20.1 LevelChunk#setBlockState light path.
            // Deferring these until the whole crater finishes leaves a partial save
            // with stale skylight/section metadata and no queued interior light checks.
            if (section.hasOnlyAir()) {
                level.getLightEngine().updateSectionStatus(
                    SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(sectionIndex)), true
                );
            }
            if (LightEngine.hasDifferentLightProperties(chunk, pos, oldState, air)) {
                chunk.getSkyLightSources().update(chunk, localX, y, localZ);
                level.getLightEngine().checkBlock(pos);
            }

            // This is the vanilla cleanup hook invoked by LevelChunk#setBlockState after
            // the palette write. The base implementation removes block entities/tickers;
            // specialized blocks also clear rails/redstone/sensors and preserve container
            // contents. Calling it here is both more correct and cheaper than full setBlock.
            oldState.onRemove(level, pos, air, false);

            changedBySection
                .computeIfAbsent(sectionIndex, ignored -> new ShortOpenHashSet())
                .add(packLocal(localX, localY, localZ));

            // Mirror Level#setBlock(flags=3), including the post-onRemove state check.
            // A selected block is NOT absent until its removal actually commits. In
            // particular, sand above an interior support can survive this slice. These
            // notifications must run before any deadline check/save, not at the final
            // crater boundary: vanilla's resulting scheduled ticks are then saveable.
            if (chunk.getBlockState(pos) == air) {
                BlockPos physicsPos = pos.immutable();
                level.updateNeighborsAt(physicsPos, oldState.getBlock());
                oldState.updateIndirectNeighbourShapes(level, physicsPos, 2, 511);
                air.updateNeighbourShapes(level, physicsPos, 2, 511);
                air.updateIndirectNeighbourShapes(level, physicsPos, 2, 511);

                // Keep the persistent POI index synchronized, as Level#setBlock does
                // after its neighbor/shape notifications.
                level.onBlockStateChange(physicsPos, oldState, air);
            }

            return oldState;
        }

        /**
         * Publishes this slice's compact section packets. Save/light bookkeeping and
         * neighbor/shape dispatch are already complete for every successful write.
         * The scheduler discards the context before returning control to Minecraft.
         */
        public void finish() {
            if (!changedBySection.isEmpty()) {
                syncChangedSections();
            }
        }

        public int changedBlockCount() {
            int total = 0;
            for (ShortOpenHashSet changed : changedBySection.values()) {
                total += changed.size();
            }
            return total;
        }

        private void syncChangedSections() {
            List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
            if (players.isEmpty()) {
                return;
            }

            for (Int2ObjectMap.Entry<ShortOpenHashSet> entry : changedBySection.int2ObjectEntrySet()) {
                int sectionIndex = entry.getIntKey();
                LevelChunkSection section = chunk.getSection(sectionIndex);
                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(
                    SectionPos.of(chunk.getPos(), sectionY),
                    entry.getValue(),
                    section
                );

                for (ServerPlayer player : players) {
                    player.connection.send(packet);
                }
            }
        }

        private static short packLocal(int x, int y, int z) {
            return (short)((x & 15) << 8 | (z & 15) << 4 | (y & 15));
        }
    }
}
