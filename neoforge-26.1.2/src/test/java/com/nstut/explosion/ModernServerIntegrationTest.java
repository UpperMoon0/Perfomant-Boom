package com.nstut.explosion;

import com.nstut.explosion.terrain.TerrainMutationScope;
import com.nstut.explosion.terrain.TerrainOperations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real temporary 26.1.2 server, including the installed mod's mixins and tick listener. */
@ExtendWith(EphemeralTestServerProvider.class)
@Timeout(90)
class ModernServerIntegrationTest {
    @BeforeEach
    void createTestWorlds(MinecraftServer server) throws Exception {
        // The framework starts a ticking server with registries but deliberately no levels.
        // Invoke vanilla's protected world creation only in this test fixture.
        server.submit(() -> {
            if (server.overworld() == null) {
                try {
                    var create = MinecraftServer.class.getDeclaredMethod("createLevels");
                    create.setAccessible(true);
                    create.invoke(server);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Cannot initialize test worlds", error);
                }
            }
            return true;
        }).get(60, TimeUnit.SECONDS);
    }

    @Test
    void noDropReplacementRemovesInventoryAndRestoresScope(MinecraftServer server) throws Exception {
        server.submit(() -> {
            var level = server.overworld();
            BlockPos pos = new BlockPos(8, 220, 8);
            level.getChunkAt(pos);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
            ((Container) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 32));
            assertTrue(TerrainOperations.replaceWithoutDrops(level, pos, Blocks.AIR.defaultBlockState()));
            assertNull(level.getBlockEntity(pos));
            assertTrue(level.getBlockState(pos).isAir());
            assertFalse(TerrainMutationScope.active());
            assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3)).isEmpty());
            assertTrue(level.getChunkAt(pos).isUnsaved());
            return true;
        }).get(30, TimeUnit.SECONDS);
    }

    @Test
    void scheduledExplosionRunsThroughRealServerTicks(MinecraftServer server) throws Exception {
        var completed = new CompletableFuture<ExplosionScheduler.ExplosionMetrics>();
        BlockPos center = new BlockPos(40, 220, 40);
        BlockPos shield = center.offset(2, 0, 0);
        server.submit(() -> {
            var level = server.overworld();
            for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) level.getChunk(x, z);
            for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
                level.setBlock(center.offset(x, y, z), Blocks.NETHERRACK.defaultBlockState(), 3);
            level.setBlock(shield, Blocks.BEDROCK.defaultBlockState(), 3);
            ExplosionScheduler.scheduleTracked(level, Vec3.atCenterOf(center), 4, completed::complete);
            return true;
        }).get(30, TimeUnit.SECONDS);
        var metrics = completed.get(30, TimeUnit.SECONDS);
        assertTrue(metrics.changedBlocks() > 0);
        assertTrue(metrics.raySamples() >= 1352);
        assertTrue(metrics.workPasses() > 0);
        server.submit(() -> {
            assertTrue(server.overworld().getBlockState(center).isAir());
            assertTrue(server.overworld().getBlockState(shield).is(Blocks.BEDROCK));
            return true;
        }).get(30, TimeUnit.SECONDS);
    }
}
