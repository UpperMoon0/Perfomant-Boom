package com.nstut.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Manages mass destruction by splitting it across multiple ticks.
 */
public class ExplosionScheduler {
    private static final Queue<DestructionTask> TASK_QUEUE = new ArrayDeque<>();
    private static final int BLOCKS_PER_TICK = 50000; // Configurable

    public static void scheduleDestruction(ServerLevel level, Vec3 center, List<BlockPos> blocks) {
        TASK_QUEUE.add(new DestructionTask(level, center, blocks));
    }

    public static void tick() {
        if (TASK_QUEUE.isEmpty()) return;

        DestructionTask currentTask = TASK_QUEUE.peek();
        if (currentTask.processBatch(BLOCKS_PER_TICK)) {
            TASK_QUEUE.poll();
            // Trigger final cleanup/lighting for this task if needed
        }
    }

    private static class DestructionTask {
        private final ServerLevel level;
        private final Vec3 center;
        private final List<BlockPos> blocks;
        private final Set<ChunkPos> affectedChunks = new HashSet<>();
        private final Set<BlockPos> boundaries = new HashSet<>();
        private int currentIndex = 0;

        public DestructionTask(ServerLevel level, Vec3 center, List<BlockPos> blocks) {
            this.level = level;
            this.center = center;
            this.blocks = blocks;
            // Immediate entity damage at the start of the explosion
            damageEntities();
        }

        private void damageEntities() {
            float radius = 8.0f; // Default or calculated from block count
            if (blocks.size() > 100) radius = (float) Math.pow(blocks.size() * 0.75 / Math.PI, 1.0/3.0);
            
            float doubleRadius = radius * 2.0F;
            int x1 = net.minecraft.util.Mth.floor(center.x - (double)doubleRadius - 1.0);
            int x2 = net.minecraft.util.Mth.floor(center.x + (double)doubleRadius + 1.0);
            int y1 = net.minecraft.util.Mth.floor(center.y - (double)doubleRadius - 1.0);
            int y2 = net.minecraft.util.Mth.floor(center.y + (double)doubleRadius + 1.0);
            int z1 = net.minecraft.util.Mth.floor(center.z - (double)doubleRadius - 1.0);
            int z2 = net.minecraft.util.Mth.floor(center.z + (double)doubleRadius + 1.0);
            
            List<net.minecraft.world.entity.Entity> entities = level.getEntities(null, new net.minecraft.world.phys.AABB(x1, y1, z1, x2, y2, z2));
            net.minecraft.world.damagesource.DamageSource damageSource = level.damageSources().explosion(null, null);

            for (net.minecraft.world.entity.Entity entity : entities) {
                if (entity.ignoreExplosion()) continue;

                double distanceRatio = Math.sqrt(entity.distanceToSqr(center)) / (double)doubleRadius;
                if (distanceRatio <= 1.0) {
                    double dx = entity.getX() - center.x;
                    double dy = (entity instanceof net.minecraft.world.entity.item.PrimedTnt ? entity.getY() : entity.getEyeY()) - center.y;
                    double dz = entity.getZ() - center.z;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    
                    if (dist != 0.0) {
                        dx /= dist; dy /= dist; dz /= dist;
                        double exposure = getOptimizedExposure(center, entity);
                        double impact = (1.0 - distanceRatio) * exposure;
                        entity.hurt(damageSource, (float)((int)((impact * impact + impact) / 2.0 * 7.0 * (double)doubleRadius + 1.0)));
                        
                        double knockback = impact;
                        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                            knockback = net.minecraft.world.item.enchantment.ProtectionEnchantment.getExplosionKnockbackAfterDampener(living, impact);
                        }
                        entity.setDeltaMovement(entity.getDeltaMovement().add(dx * knockback, dy * knockback, dz * knockback));
                    }
                }
            }
        }

        private double getOptimizedExposure(Vec3 source, net.minecraft.world.entity.Entity entity) {
            net.minecraft.world.phys.AABB box = entity.getBoundingBox();
            double dx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
            double dy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
            double dz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
            
            if (dx < 0.0 || dy < 0.0 || dz < 0.0) return 0.0;

            int missed = 0;
            int total = 0;
            // Optimized: only check corners and center if large, or simplified sampling
            for (double x = 0.0; x <= 1.0; x += dx) {
                for (double y = 0.0; y <= 1.0; y += dy) {
                    for (double z = 0.0; z <= 1.0; z += dz) {
                        Vec3 target = new Vec3(
                            net.minecraft.util.Mth.lerp(x, box.minX, box.maxX),
                            net.minecraft.util.Mth.lerp(y, box.minY, box.maxY),
                            net.minecraft.util.Mth.lerp(z, box.minZ, box.maxZ)
                        );
                        if (level.clip(new net.minecraft.world.level.ClipContext(target, source, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, entity)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                            missed++;
                        }
                        total++;
                    }
                }
            }
            return (double)missed / (double)total;
        }

        /**
         * Processes a batch of blocks. Returns true if the task is finished.
         */
        public boolean processBatch(int count) {
            int end = Math.min(currentIndex + count, blocks.size());
            BlockState air = Blocks.AIR.defaultBlockState();

            for (int i = currentIndex; i < end; i++) {
                BlockPos pos = blocks.get(i);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    ChunkBlockModifier.setBlockFast(level, pos, air);
                    affectedChunks.add(new ChunkPos(pos));
                    // Check if neighbors are on the boundary of the explosion
                    for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                        BlockPos neighbor = pos.relative(dir);
                        if (!blocks.contains(neighbor)) {
                            boundaries.add(neighbor);
                        }
                    }
                }
            }

            currentIndex = end;
            if (currentIndex >= blocks.size()) {
                finalizeDestruction();
                return true;
            }
            return false;
        }

        private void finalizeDestruction() {
            // Finalize each chunk
            for (ChunkPos chunkPos : affectedChunks) {
                ChunkBlockModifier.finalizeChunkChanges(level, level.getChunk(chunkPos.x, chunkPos.z));
            }
            // Trigger lighting updates for the boundaries
            ChunkBlockModifier.triggerLightingUpdates(level, boundaries);
        }
    }
}
