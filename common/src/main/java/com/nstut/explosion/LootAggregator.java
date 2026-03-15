package com.nstut.explosion;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Aggregates loot from destroyed blocks to prevent entity spam.
 */
public class LootAggregator {
    private final Object2IntMap<Item> combinedDrops = new Object2IntOpenHashMap<>();

    public void addBlockDrops(ServerLevel level, BlockPos pos, BlockState state) {
        // Simplified loot logic: just drop the block's item representation
        // For a more accurate system, we would use level.getLootTable(...)
        // but for millions of blocks, that's too slow.
        Item item = state.getBlock().asItem();
        if (item != net.minecraft.world.item.Items.AIR) {
            combinedDrops.mergeInt(item, 1, Integer::sum);
        }
    }

    public void spawnDrops(ServerLevel level, Vec3 pos) {
        for (Object2IntMap.Entry<Item> entry : combinedDrops.object2IntEntrySet()) {
            Item item = entry.getKey();
            int count = entry.getIntValue();

            while (count > 0) {
                int stackSize = Math.min(count, item.getMaxStackSize());
                ItemStack stack = new ItemStack(item, stackSize);
                ItemEntity entity = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
                entity.setDefaultPickUpDelay();
                level.addFreshEntity(entity);
                count -= stackSize;
            }
        }
    }
}
