package com.nstut.testing;

import com.nstut.explosion.ExplosionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

/** Shared in-world assertions used by loader GameTests. */
public final class BoomGameTestLogic {
    private BoomGameTestLogic() {
    }

    public static void explosionMaintainsWorldBookkeeping(GameTestHelper helper) {
        var completed = new java.util.concurrent.atomic.AtomicInteger();
        BlockPos localCenter = new BlockPos(4, 4, 4);
        BlockPos localChest = localCenter.offset(1, 0, 0);
        BlockPos localControl = localCenter.offset(4, 4, 4);
        BlockPos center = helper.absolutePos(localCenter);
        BlockPos chest = helper.absolutePos(localChest);
        BlockPos control = helper.absolutePos(localControl);
        BlockPos vanillaLightControl = center.offset(16, 0, 0);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    boolean shell = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) == 4;
                    helper.getLevel().setBlock(
                        pos,
                        shell ? Blocks.BEDROCK.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState(),
                        3
                    );
                }
            }
        }

        helper.getLevel().setBlock(center, Blocks.GLOWSTONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);

        if (helper.getLevel().getBlockEntity(chest) == null) {
            helper.fail("fixture chest did not create a block entity");
            return;
        }

        helper.getLevel().setBlock(vanillaLightControl, Blocks.GLOWSTONE.defaultBlockState(), 3);

        helper.runAfterDelay(20, () -> {
            System.out.println(
                "PERFOMANT_BOOM_GAMETEST_VANILLA_LIGHT_BEFORE_REMOVE brightness="
                    + helper.getLevel().getBrightness(LightLayer.BLOCK, vanillaLightControl)
            );
            helper.getLevel().setBlock(vanillaLightControl, Blocks.AIR.defaultBlockState(), 3);

            ExplosionScheduler.scheduleTracked(
                helper.getLevel(),
                new net.minecraft.world.phys.Vec3(
                    center.getX() + 0.5D,
                    center.getY() + 0.5D,
                    center.getZ() + 0.5D
                ),
                5.0F,
                metrics -> {
                    if (completed.incrementAndGet() != 1) helper.fail("completion fired more than once");
                    if (metrics.changedBlocks() <= 0) {
                        helper.fail("explosion changed no blocks");
                    }
                }
            );
        });

        helper.succeedWhen(() -> {
            if (completed.get() != 1) throw new GameTestAssertException("scheduler has not completed all deferred updates");
            helper.assertBlockState(localCenter, state -> state.isAir(), () -> "center glowstone still present");
            helper.assertBlockState(localChest, state -> state.isAir(), () -> "center-adjacent chest still present");
            helper.assertBlockState(localControl, state -> state.is(Blocks.BEDROCK), () -> "bedrock boundary control was destroyed");

            if (helper.getLevel().getBlockEntity(chest) != null) {
                helper.fail("removed chest block entity still registered");
                return;
            }
            int vanillaControlLight = helper.getLevel().getBrightness(LightLayer.BLOCK, vanillaLightControl);
            if (vanillaControlLight != 0) {
                throw new GameTestAssertException(
                    "vanilla glowstone removal light has not settled: " + vanillaControlLight
                );
            }

            int fastLight = helper.getLevel().getBrightness(LightLayer.BLOCK, center);
            if (fastLight != 0) {
                if (helper.getTick() % 20L == 0L) {
                    System.out.println(
                        "PERFOMANT_BOOM_GAMETEST_FAST_LIGHT_PENDING tick="
                            + helper.getTick()
                            + " brightness=" + fastLight
                            + " lightWork=" + helper.getLevel().getLightEngine().hasLightWork()
                    );
                }
                throw new GameTestAssertException("removed glowstone light has not settled: " + fastLight);
            }
        });
    }
}
