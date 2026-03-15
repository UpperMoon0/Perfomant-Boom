package com.nstut.explosion;

import com.nstut.explosion.util.LightFlushable;
import net.minecraft.core.BlockPos;
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
     * Marks a chunk as modified and notifies clients about the change using a full chunk packet.
     */
    public static void finalizeChunkChanges(ServerLevel level, LevelChunk chunk) {
        chunk.setUnsaved(true);
        syncChunkToClients(level, chunk);
    }

    /**
     * Sends a full chunk data packet with light to all players tracking this chunk.
     */
    public static void syncChunkToClients(ServerLevel level, LevelChunk chunk) {
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet = 
            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        
        level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).forEach(player -> {
            player.connection.send(packet);
        });
    }

    /**
     * Triggers lighting recalculation for a list of positions.
     * Should be called for the boundary of the explosion.
     */
    public static void triggerLightingUpdates(ServerLevel level, java.util.Collection<BlockPos> boundaries) {
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        for (BlockPos pos : boundaries) {
            lightEngine.checkBlock(pos);
        }
    }
}
