package com.nstut.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Utility for fast block modification by bypassing high-level Level methods.
 */
public class ChunkBlockModifier {

    /**
     * Sets a block state directly in the chunk section.
     * This bypasses neighbor updates, lighting updates, and packet sending.
     * Use this for mass destruction, followed by a manual chunk update.
     */
    public static void setBlockFast(ServerLevel level, BlockPos pos, BlockState state) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        
        LevelChunk chunk = level.getChunkAt(pos);
        int sectionIndex = chunk.getSectionIndex(y);
        
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return;
        }

        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            if (state.isAir()) return;
            section = new LevelChunkSection(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME));
            chunk.getSections()[sectionIndex] = section;
        }

        // Low-level access: bypass setBlockState to skip counts/ticking logic if we do it ourselves
        // or just use the simplified version.
        // To be TRULY fast, we can access the PalettedContainer directly.
        section.setBlockState(x & 15, y & 15, z & 15, state, false); // false for unchecked
    }

    /**
     * Marks a chunk as modified and notifies clients about the change.
     * Call this once per chunk after a batch of fast modifications.
     */
    public static void finalizeChunkChanges(ServerLevel level, LevelChunk chunk) {
        chunk.setUnsaved(true);
        
        // Mark all sections as needing a light check
        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSection(i);
            if (section != null) {
                SectionPos sectionPos = SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(i));
                level.getLightEngine().updateSectionStatus(sectionPos, false);
            }
        }

        // This triggers a sync of the whole chunk to nearby clients
        level.getChunkSource().blockChanged(chunk.getPos().getWorldPosition());
    }
}
