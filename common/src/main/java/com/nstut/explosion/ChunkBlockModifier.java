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
            // If the section is empty (all air), Minecraft might return null
            // We usually don't need to do anything if we are setting it to air anyway,
            // but for a general tool, we might need to initialize it.
            return; 
        }

        // setBlockState(x, y, z, state, unchecked)
        // unchecked=true avoids some internal safety checks but is faster
        section.setBlockState(x & 15, y & 15, z & 15, state, true);
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
