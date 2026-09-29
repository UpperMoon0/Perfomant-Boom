package com.nstut.testing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

public final class BoomClientIntegrationTest {
    public static final String PASS_MARKER = "PERFOMANT_BOOM_E2E_CLIENT_PASS";
    public static final String FAIL_MARKER = "PERFOMANT_BOOM_E2E_CLIENT_FAIL";

    private static int ticksWithLevel;
    private static boolean initialFixtureObserved;
    private static boolean readySent;
    private static boolean finished;

    private BoomClientIntegrationTest() {}

    public static void tick() {
        if (!BoomServerIntegrationTest.isArmed() || finished) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) return;

        ticksWithLevel++;

        if (!allFixtureChunksLoaded(level)) {
            if (ticksWithLevel > 2400) fail(minecraft, "fixture chunks never loaded");
            return;
        }

        if (!initialFixtureObserved && anyCenterIsGlowstone(level)) {
            initialFixtureObserved = true;
            System.out.println("PERFOMANT_BOOM_E2E_CLIENT_FIXTURE_SEEN");
        }

        if (initialFixtureObserved && !readySent && minecraft.getConnection() != null) {
            readySent = true;
            minecraft.getConnection().sendCommand("perfomant_boom_live_ready");
            System.out.println("PERFOMANT_BOOM_E2E_CLIENT_READY_SENT");
        }

        if (!readySent || !level.getBlockState(BoomServerIntegrationTest.CLIENT_DONE_MARKER).is(Blocks.EMERALD_BLOCK)
            || !allCentersAir(level)) {
            if (ticksWithLevel > 4000) fail(minecraft, "explosions did not converge on client");
            return;
        }

        int noDropsVanilla = destroyedCount(level, BoomServerIntegrationTest.finalNoDropsVanillaCenter());
        int noDropsFast = destroyedCount(level, BoomServerIntegrationTest.finalNoDropsFastCenter());
        int defaultVanilla = destroyedCount(level, BoomServerIntegrationTest.finalDefaultVanillaCenter());
        int defaultFast = destroyedCount(level, BoomServerIntegrationTest.finalDefaultFastCenter());

        if (!similarCounts(noDropsVanilla, noDropsFast)
            || !similarCounts(defaultVanilla, defaultFast)) {
            if (ticksWithLevel > 4000) {
                fail(minecraft, "client crater count diverged noDrops="
                    + noDropsVanilla + "/" + noDropsFast
                    + " default=" + defaultVanilla + "/" + defaultFast);
            }
            return;
        }

        if (!controlsRemain(level)) {
            fail(minecraft, "fixture bedrock shell was unexpectedly destroyed");
            return;
        }

        int maxBlockLight = maxFixtureBlockLight(level);
        if (maxBlockLight != 0) {
            if (ticksWithLevel > 4000) {
                fail(minecraft, "block light did not settle max=" + maxBlockLight);
            }
            return;
        }

        finished = true;
        System.out.println(PASS_MARKER
            + " ticks=" + ticksWithLevel
            + " initialFixtureObserved=" + initialFixtureObserved
            + " fixtures=" + BoomServerIntegrationTest.fixtureCenters().length
            + " noDropsBlocks=" + noDropsFast
            + " defaultBlocks=" + defaultFast
            + " maxBlockLight=" + maxBlockLight);
        // tools/live_boom_test.py terminates the dev client after consuming this marker.
    }

    private static boolean allFixtureChunksLoaded(ClientLevel level) {
        int r = BoomServerIntegrationTest.SHELL_RADIUS;
        for (BlockPos center : BoomServerIntegrationTest.fixtureCenters()) {
            if (!level.hasChunkAt(center.offset(-r, 0, -r))
                || !level.hasChunkAt(center.offset(-r, 0, r))
                || !level.hasChunkAt(center.offset(r, 0, -r))
                || !level.hasChunkAt(center.offset(r, 0, r))) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyCenterIsGlowstone(ClientLevel level) {
        for (BlockPos center : BoomServerIntegrationTest.fixtureCenters()) {
            if (level.getBlockState(center).is(Blocks.GLOWSTONE)) return true;
        }
        return false;
    }

    private static boolean allCentersAir(ClientLevel level) {
        for (BlockPos center : BoomServerIntegrationTest.fixtureCenters()) {
            if (!level.getBlockState(center).isAir()) return false;
        }
        return true;
    }

    private static boolean controlsRemain(ClientLevel level) {
        int r = BoomServerIntegrationTest.SHELL_RADIUS;
        for (BlockPos center : BoomServerIntegrationTest.fixtureCenters()) {
            if (!level.getBlockState(center.offset(r, r, r)).is(Blocks.BEDROCK)) return false;
        }
        return true;
    }

    private static int maxFixtureBlockLight(ClientLevel level) {
        int max = 0;
        for (BlockPos center : BoomServerIntegrationTest.fixtureCenters()) {
            max = Math.max(max, level.getBrightness(LightLayer.BLOCK, center));
        }
        return max;
    }

    private static int destroyedCount(ClientLevel level, BlockPos center) {
        int radius = BoomServerIntegrationTest.FIXTURE_RADIUS;
        int result = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (level.getBlockState(pos).isAir()) result++;
                }
            }
        }
        return result;
    }

    private static boolean similarCounts(int vanilla, int fast) {
        int tolerance = Math.max(32, (int)Math.ceil(vanilla * 0.20D));
        return Math.abs(vanilla - fast) <= tolerance;
    }

    private static void fail(Minecraft minecraft, String reason) {
        finished = true;
        System.err.println(FAIL_MARKER + " " + reason);
        // tools/live_boom_test.py terminates the dev client after consuming this marker.
    }
}
