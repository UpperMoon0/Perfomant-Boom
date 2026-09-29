package com.nstut.testing;

import com.nstut.explosion.ExplosionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BoomServerIntegrationTest {
    public static final String PROPERTY = "perfomant_boom.liveTest";
    public static final String FIXTURE_READY_MARKER = "PERFOMANT_BOOM_E2E_FIXTURE_READY";
    public static final String SERVER_PASS_MARKER = "PERFOMANT_BOOM_E2E_SERVER_PASS";
    public static final String SERVER_FAIL_MARKER = "PERFOMANT_BOOM_E2E_SERVER_FAIL";

    public static final int FIXTURE_RADIUS = 12;
    public static final int SHELL_RADIUS = FIXTURE_RADIUS + 1;
    public static final float POWER = 10.0F;
    public static final int WARMUP_PAIRS = 3;
    public static final int MEASURED_TRIALS = 5;
    public static final int TOTAL_PAIRS = WARMUP_PAIRS + MEASURED_TRIALS;
    public static final BlockPos CLIENT_POSITION = new BlockPos(0, 120, 0);
    public static final BlockPos CLIENT_DONE_MARKER = new BlockPos(0, 118, 4);

    private static final int PAIR_SETTLE_TICKS = 5;
    private static final int FAST_POST_SETTLE_TICKS = 5;
    private static final int BASELINE_TICKS = 100;

    private static final BlockPos[] NO_DROPS_VANILLA = fixtureRow(0);
    private static final BlockPos[] NO_DROPS_FAST = fixtureRow(1);
    private static final BlockPos[] DEFAULT_VANILLA = fixtureRow(2);
    private static final BlockPos[] DEFAULT_FAST = fixtureRow(3);
    private static final BlockPos[] ALL_FIXTURES = concatFixtures();

    private static MinecraftServer activeServer;
    private static Phase phase = Phase.PREPARE;
    private static long tickStartNanos;
    private static int phaseTicks;
    private static int pairIndex;
    private static boolean teleported;
    private static boolean clientReady;
    private static boolean originalDoBlockDrops;

    private static double currentVanillaMs;
    private static double currentVanillaTickMs;
    private static int currentVanillaBlocks;
    private static ExplosionScheduler.ExplosionMetrics currentFastMetrics;
    private static double currentFastTickTotalMs;
    private static double currentFastMaxTickMs;
    private static int currentFastTickCount;

    private static final List<Double> baselineTicks = new ArrayList<>();
    private static final List<Double> noDropsVanillaMs = new ArrayList<>();
    private static final List<Double> noDropsVanillaTickMs = new ArrayList<>();
    private static final List<Double> noDropsFastCpuMs = new ArrayList<>();
    private static final List<Double> noDropsFastWallMs = new ArrayList<>();
    private static final List<Double> noDropsFastMaxSliceMs = new ArrayList<>();
    private static final List<Double> noDropsFastMaxServerTickMs = new ArrayList<>();
    private static final List<Double> noDropsFastObservedTickTotalMs = new ArrayList<>();
    private static final List<Integer> noDropsFastObservedTickCount = new ArrayList<>();
    private static final List<Integer> noDropsVanillaBlocks = new ArrayList<>();
    private static final List<Integer> noDropsFastBlocks = new ArrayList<>();
    private static final List<Long> noDropsRaySamples = new ArrayList<>();
    private static final List<Double> defaultVanillaMs = new ArrayList<>();
    private static final List<Double> defaultVanillaTickMs = new ArrayList<>();
    private static final List<Double> defaultFastCpuMs = new ArrayList<>();
    private static final List<Double> defaultFastWallMs = new ArrayList<>();
    private static final List<Double> defaultFastMaxSliceMs = new ArrayList<>();
    private static final List<Double> defaultFastMaxServerTickMs = new ArrayList<>();
    private static final List<Double> defaultFastObservedTickTotalMs = new ArrayList<>();
    private static final List<Integer> defaultFastObservedTickCount = new ArrayList<>();
    private static final List<Integer> defaultVanillaBlocks = new ArrayList<>();
    private static final List<Integer> defaultFastBlocks = new ArrayList<>();
    private static final List<Long> defaultRaySamples = new ArrayList<>();
    private static final List<Integer> defaultVanillaDropCounts = new ArrayList<>();

    private BoomServerIntegrationTest() {}

    public static boolean isArmed() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    public static BlockPos[] fixtureCenters() { return ALL_FIXTURES.clone(); }
    public static BlockPos finalNoDropsVanillaCenter() { return NO_DROPS_VANILLA[NO_DROPS_VANILLA.length - 1]; }
    public static BlockPos finalNoDropsFastCenter() { return NO_DROPS_FAST[NO_DROPS_FAST.length - 1]; }
    public static BlockPos finalDefaultVanillaCenter() { return DEFAULT_VANILLA[DEFAULT_VANILLA.length - 1]; }
    public static BlockPos finalDefaultFastCenter() { return DEFAULT_FAST[DEFAULT_FAST.length - 1]; }

    public static void onServerTickStart(MinecraftServer server) {
        if (!isArmed()) return;
        if (activeServer != server) reset(server);
        tickStartNanos = System.nanoTime();
    }

    public static void tick(MinecraftServer server) {
        if (!isArmed()) return;
        if (activeServer != server) reset(server);

        double currentTickMs = tickStartNanos == 0L
            ? 0.0D
            : (System.nanoTime() - tickStartNanos) / 1_000_000.0D;

        if (phase == Phase.WAIT_NO_DROPS_FAST
            || phase == Phase.SETTLE_NO_DROPS_FAST
            || phase == Phase.WAIT_DEFAULT_FAST
            || phase == Phase.SETTLE_DEFAULT_FAST) {
            currentFastTickTotalMs += currentTickMs;
            currentFastMaxTickMs = Math.max(currentFastMaxTickMs, currentTickMs);
            currentFastTickCount++;
        }

        try {
            switch (phase) {
                case PREPARE -> prepare(server.overworld());
                case WAIT_FOR_CLIENT -> waitForClient(server);
                case SETTLE_CLIENT -> settleClient();
                case COLLECT_BASELINE -> collectBaseline(currentTickMs);
                case RUN_NO_DROPS_VANILLA -> runVanilla(
                    server.overworld(), NO_DROPS_VANILLA[pairIndex], false, currentTickMs);
                case WAIT_AFTER_NO_DROPS_VANILLA -> waitThenRunFast(
                    server.overworld(), NO_DROPS_FAST[pairIndex], Phase.WAIT_NO_DROPS_FAST);
                case WAIT_NO_DROPS_FAST -> awaitFast(Phase.SETTLE_NO_DROPS_FAST);
                case SETTLE_NO_DROPS_FAST -> settleNoDrops(server.overworld());
                case RUN_DEFAULT_VANILLA -> runDefaultVanilla(server.overworld(), currentTickMs);
                case WAIT_AFTER_DEFAULT_VANILLA -> waitThenRunDefaultFast(server.overworld());
                case WAIT_DEFAULT_FAST -> awaitFast(Phase.SETTLE_DEFAULT_FAST);
                case SETTLE_DEFAULT_FAST -> settleDefault(server.overworld());
                case DONE, FAILED -> {}
            }
        } catch (Throwable t) {
            fail("exception=" + t.getClass().getName() + " message=" + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    public static void markClientReady() {
        if (isArmed()) {
            clientReady = true;
            System.out.println("PERFOMANT_BOOM_E2E_SERVER_CLIENT_READY");
        }
    }

    private static void reset(MinecraftServer server) {
        activeServer = server;
        phase = Phase.PREPARE;
        tickStartNanos = 0L;
        phaseTicks = 0;
        pairIndex = 0;
        teleported = false;
        clientReady = false;
        clearSamples();
    }

    private static void clearSamples() {
        baselineTicks.clear();
        noDropsVanillaMs.clear();
        noDropsVanillaTickMs.clear();
        noDropsFastCpuMs.clear();
        noDropsFastWallMs.clear();
        noDropsFastMaxSliceMs.clear();
        noDropsFastMaxServerTickMs.clear();
        noDropsFastObservedTickTotalMs.clear();
        noDropsFastObservedTickCount.clear();
        noDropsVanillaBlocks.clear();
        noDropsFastBlocks.clear();
        noDropsRaySamples.clear();
        defaultVanillaMs.clear();
        defaultVanillaTickMs.clear();
        defaultFastCpuMs.clear();
        defaultFastWallMs.clear();
        defaultFastMaxSliceMs.clear();
        defaultFastMaxServerTickMs.clear();
        defaultFastObservedTickTotalMs.clear();
        defaultFastObservedTickCount.clear();
        defaultVanillaBlocks.clear();
        defaultFastBlocks.clear();
        defaultRaySamples.clear();
        defaultVanillaDropCounts.clear();
    }

    private static void prepare(ServerLevel level) {
        if (NO_DROPS_VANILLA.length != TOTAL_PAIRS
            || NO_DROPS_FAST.length != TOTAL_PAIRS
            || DEFAULT_VANILLA.length != TOTAL_PAIRS
            || DEFAULT_FAST.length != TOTAL_PAIRS) {
            fail("fixture row length mismatch expected=" + TOTAL_PAIRS
                + " actual=" + NO_DROPS_VANILLA.length + "," + NO_DROPS_FAST.length
                + "," + DEFAULT_VANILLA.length + "," + DEFAULT_FAST.length);
            return;
        }
        originalDoBlockDrops = level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS);
        for (BlockPos center : ALL_FIXTURES) fillFixture(level, center);
        level.setBlock(CLIENT_DONE_MARKER, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        System.out.println(FIXTURE_READY_MARKER
            + " power=" + POWER
            + " radius=" + FIXTURE_RADIUS
            + " fixtures=" + ALL_FIXTURES.length
            + " warmupPairs=" + WARMUP_PAIRS
            + " measuredTrials=" + MEASURED_TRIALS
            + " runtimePairs=" + TOTAL_PAIRS
            + " codeSource=" + String.valueOf(BoomServerIntegrationTest.class.getProtectionDomain().getCodeSource()));
        phase = Phase.WAIT_FOR_CLIENT;
    }

    private static void waitForClient(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        if (!teleported) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                player.teleportTo(
                    server.overworld(),
                    CLIENT_POSITION.getX() + 0.5D,
                    CLIENT_POSITION.getY(),
                    CLIENT_POSITION.getZ() + 0.5D,
                    180.0F,
                    25.0F
                );
            }
            teleported = true;
        }
        if (clientReady) {
            phase = Phase.SETTLE_CLIENT;
            phaseTicks = 0;
        }
    }

    private static void settleClient() {
        if (++phaseTicks < 20) return;
        phase = Phase.COLLECT_BASELINE;
        phaseTicks = 0;
    }

    private static void collectBaseline(double currentTickMs) {
        baselineTicks.add(currentTickMs);
        if (++phaseTicks < BASELINE_TICKS) return;
        pairIndex = 0;
        phase = Phase.RUN_NO_DROPS_VANILLA;
    }

    private static void runVanilla(
        ServerLevel level, BlockPos center, boolean blockDrops, double tickMsBeforeExplosion
    ) {
        setBlockDrops(level, blockDrops);
        long start = System.nanoTime();
        level.explode(
            null,
            center.getX() + 0.5D,
            center.getY() + 0.5D,
            center.getZ() + 0.5D,
            POWER,
            false,
            Level.ExplosionInteraction.BLOCK
        );
        currentVanillaMs = (System.nanoTime() - start) / 1_000_000.0D;
        currentVanillaTickMs = tickMsBeforeExplosion + currentVanillaMs;
        currentVanillaBlocks = destroyedCount(level, center);
        phase = Phase.WAIT_AFTER_NO_DROPS_VANILLA;
        phaseTicks = 0;
    }

    private static void waitThenRunFast(ServerLevel level, BlockPos center, Phase waitPhase) {
        if (++phaseTicks < PAIR_SETTLE_TICKS) return;
        resetFastObservation();
        ExplosionScheduler.scheduleTracked(
            level, centerVec(center), POWER, metrics -> currentFastMetrics = metrics);
        phase = waitPhase;
        phaseTicks = 0;
    }

    private static void awaitFast(Phase settlePhase) {
        if (currentFastMetrics == null) {
            if (++phaseTicks > 1200) fail("fast explosion timed out in " + phase);
            return;
        }
        phase = settlePhase;
        phaseTicks = 0;
    }

    private static void settleNoDrops(ServerLevel level) {
        if (++phaseTicks < FAST_POST_SETTLE_TICKS) return;

        int fastBlocks = destroyedCount(level, NO_DROPS_FAST[pairIndex]);
        if (!similarCounts(currentVanillaBlocks, fastBlocks)) {
            fail("no-drops crater count diverged pair=" + pairIndex
                + " vanilla=" + currentVanillaBlocks + " fast=" + fastBlocks);
            return;
        }

        if (isMeasuredPair()) {
            noDropsVanillaMs.add(currentVanillaMs);
            noDropsVanillaTickMs.add(currentVanillaTickMs);
            noDropsFastCpuMs.add(currentFastMetrics.cpuMs());
            noDropsFastWallMs.add(currentFastMetrics.wallMs());
            noDropsFastMaxSliceMs.add(currentFastMetrics.maxSliceMs());
            noDropsFastMaxServerTickMs.add(currentFastMaxTickMs);
            noDropsFastObservedTickTotalMs.add(currentFastTickTotalMs);
            noDropsFastObservedTickCount.add(currentFastTickCount);
            noDropsVanillaBlocks.add(currentVanillaBlocks);
            noDropsFastBlocks.add(fastBlocks);
            noDropsRaySamples.add(currentFastMetrics.raySamples());
        }

        pairIndex++;
        if (pairIndex < NO_DROPS_VANILLA.length) {
            phase = Phase.RUN_NO_DROPS_VANILLA;
        } else {
            pairIndex = 0;
            phase = Phase.RUN_DEFAULT_VANILLA;
        }
        phaseTicks = 0;
    }

    private static void runDefaultVanilla(ServerLevel level, double currentTickMs) {
        BlockPos center = DEFAULT_VANILLA[pairIndex];
        setBlockDrops(level, true);
        long start = System.nanoTime();
        level.explode(
            null,
            center.getX() + 0.5D,
            center.getY() + 0.5D,
            center.getZ() + 0.5D,
            POWER,
            false,
            Level.ExplosionInteraction.BLOCK
        );
        currentVanillaMs = (System.nanoTime() - start) / 1_000_000.0D;
        currentVanillaTickMs = currentTickMs + currentVanillaMs;
        currentVanillaBlocks = destroyedCount(level, center);

        int dropCount = level.getEntitiesOfClass(
            ItemEntity.class,
            new AABB(center).inflate(FIXTURE_RADIUS + 4.0D)
        ).size();
        if (isMeasuredPair()) defaultVanillaDropCounts.add(dropCount);

        phase = Phase.WAIT_AFTER_DEFAULT_VANILLA;
        phaseTicks = 0;
    }

    private static void waitThenRunDefaultFast(ServerLevel level) {
        if (++phaseTicks < PAIR_SETTLE_TICKS) return;

        BlockPos vanillaCenter = DEFAULT_VANILLA[pairIndex];
        level.getEntitiesOfClass(
            ItemEntity.class,
            new AABB(vanillaCenter).inflate(FIXTURE_RADIUS + 6.0D)
        ).forEach(ItemEntity::discard);

        resetFastObservation();
        ExplosionScheduler.scheduleTracked(
            level,
            centerVec(DEFAULT_FAST[pairIndex]),
            POWER,
            metrics -> currentFastMetrics = metrics
        );
        phase = Phase.WAIT_DEFAULT_FAST;
        phaseTicks = 0;
    }

    private static void settleDefault(ServerLevel level) {
        if (++phaseTicks < FAST_POST_SETTLE_TICKS) return;

        int fastBlocks = destroyedCount(level, DEFAULT_FAST[pairIndex]);
        if (!similarCounts(currentVanillaBlocks, fastBlocks)) {
            fail("default crater count diverged pair=" + pairIndex
                + " vanilla=" + currentVanillaBlocks + " fast=" + fastBlocks);
            return;
        }

        if (isMeasuredPair()) {
            defaultVanillaMs.add(currentVanillaMs);
            defaultVanillaTickMs.add(currentVanillaTickMs);
            defaultFastCpuMs.add(currentFastMetrics.cpuMs());
            defaultFastWallMs.add(currentFastMetrics.wallMs());
            defaultFastMaxSliceMs.add(currentFastMetrics.maxSliceMs());
            defaultFastMaxServerTickMs.add(currentFastMaxTickMs);
            defaultFastObservedTickTotalMs.add(currentFastTickTotalMs);
            defaultFastObservedTickCount.add(currentFastTickCount);
            defaultVanillaBlocks.add(currentVanillaBlocks);
            defaultFastBlocks.add(fastBlocks);
            defaultRaySamples.add(currentFastMetrics.raySamples());
        }

        pairIndex++;
        if (pairIndex < DEFAULT_VANILLA.length) {
            phase = Phase.RUN_DEFAULT_VANILLA;
            phaseTicks = 0;
            return;
        }

        setBlockDrops(level, originalDoBlockDrops);
        level.setBlock(CLIENT_DONE_MARKER, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        reportSuccess();
        phase = Phase.DONE;
    }

    private static void resetFastObservation() {
        currentFastMetrics = null;
        currentFastTickTotalMs = 0.0D;
        currentFastMaxTickMs = 0.0D;
        currentFastTickCount = 0;
    }

    private static boolean isMeasuredPair() {
        return pairIndex >= WARMUP_PAIRS;
    }

    private static void reportSuccess() {
        Stats baseline = stats(baselineTicks);
        Stats ndVanilla = stats(noDropsVanillaMs);
        Stats ndVanillaTick = stats(noDropsVanillaTickMs);
        Stats ndFastCpu = stats(noDropsFastCpuMs);
        Stats ndFastWall = stats(noDropsFastWallMs);
        Stats ndFastSlice = stats(noDropsFastMaxSliceMs);
        Stats ndFastTick = stats(noDropsFastMaxServerTickMs);
        Stats defVanilla = stats(defaultVanillaMs);
        Stats defVanillaTick = stats(defaultVanillaTickMs);
        Stats defFastCpu = stats(defaultFastCpuMs);
        Stats defFastWall = stats(defaultFastWallMs);
        Stats defFastSlice = stats(defaultFastMaxSliceMs);
        Stats defFastTick = stats(defaultFastMaxServerTickMs);

        System.out.println(SERVER_PASS_MARKER
            + " samples=" + MEASURED_TRIALS
            + " baselineTickMedianMs=" + fmt(baseline.median())
            + " noDropsVanillaMedianMs=" + fmt(ndVanilla.median())
            + " noDropsVanillaP25Ms=" + fmt(ndVanilla.p25())
            + " noDropsVanillaP75Ms=" + fmt(ndVanilla.p75())
            + " noDropsFastCpuMedianMs=" + fmt(ndFastCpu.median())
            + " noDropsFastCpuP25Ms=" + fmt(ndFastCpu.p25())
            + " noDropsFastCpuP75Ms=" + fmt(ndFastCpu.p75())
            + " noDropsCpuSpeedup=" + fmt(ndVanilla.median() / ndFastCpu.median())
            + " noDropsVanillaTickMedianMs=" + fmt(ndVanillaTick.median())
            + " noDropsFastMaxServerTickMedianMs=" + fmt(ndFastTick.median())
            + " noDropsFastWorstServerTickMs=" + fmt(max(noDropsFastMaxServerTickMs))
            + " noDropsLatencyImprovement=" + fmt(ndVanillaTick.median() / ndFastTick.median())
            + " noDropsFastWallMedianMs=" + fmt(ndFastWall.median())
            + " noDropsFastMaxSliceMedianMs=" + fmt(ndFastSlice.median())
            + " noDropsFastObservedTickTotalMedianMs=" + fmt(stats(noDropsFastObservedTickTotalMs).median())
            + " noDropsFastObservedTickCountMedian=" + medianInt(noDropsFastObservedTickCount)
            + " noDropsVanillaBlocksMedian=" + medianInt(noDropsVanillaBlocks)
            + " noDropsFastBlocksMedian=" + medianInt(noDropsFastBlocks)
            + " noDropsRaySamplesMedian=" + medianLong(noDropsRaySamples)
            + " defaultVanillaMedianMs=" + fmt(defVanilla.median())
            + " defaultVanillaP25Ms=" + fmt(defVanilla.p25())
            + " defaultVanillaP75Ms=" + fmt(defVanilla.p75())
            + " defaultFastCpuMedianMs=" + fmt(defFastCpu.median())
            + " defaultFastCpuP25Ms=" + fmt(defFastCpu.p25())
            + " defaultFastCpuP75Ms=" + fmt(defFastCpu.p75())
            + " defaultCpuSpeedup=" + fmt(defVanilla.median() / defFastCpu.median())
            + " defaultVanillaTickMedianMs=" + fmt(defVanillaTick.median())
            + " defaultFastMaxServerTickMedianMs=" + fmt(defFastTick.median())
            + " defaultFastWorstServerTickMs=" + fmt(max(defaultFastMaxServerTickMs))
            + " defaultLatencyImprovement=" + fmt(defVanillaTick.median() / defFastTick.median())
            + " defaultFastWallMedianMs=" + fmt(defFastWall.median())
            + " defaultFastMaxSliceMedianMs=" + fmt(defFastSlice.median())
            + " defaultFastObservedTickTotalMedianMs=" + fmt(stats(defaultFastObservedTickTotalMs).median())
            + " defaultFastObservedTickCountMedian=" + medianInt(defaultFastObservedTickCount)
            + " defaultVanillaBlocksMedian=" + medianInt(defaultVanillaBlocks)
            + " defaultFastBlocksMedian=" + medianInt(defaultFastBlocks)
            + " defaultRaySamplesMedian=" + medianLong(defaultRaySamples)
            + " defaultVanillaDropsMedian=" + medianInt(defaultVanillaDropCounts));
    }

    private static void setBlockDrops(ServerLevel level, boolean enabled) {
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(enabled, level.getServer());
    }

    private static void fillFixture(ServerLevel level, BlockPos center) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -SHELL_RADIUS; x <= SHELL_RADIUS; x++) {
            for (int y = -SHELL_RADIUS; y <= SHELL_RADIUS; y++) {
                for (int z = -SHELL_RADIUS; z <= SHELL_RADIUS; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    boolean shell =
                        Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) == SHELL_RADIUS;
                    level.setBlock(
                        pos,
                        shell ? Blocks.BEDROCK.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState(),
                        2
                    );
                }
            }
        }
        level.setBlock(center, Blocks.GLOWSTONE.defaultBlockState(), 2);
    }

    private static int destroyedCount(ServerLevel level, BlockPos center) {
        int result = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -FIXTURE_RADIUS; x <= FIXTURE_RADIUS; x++) {
            for (int y = -FIXTURE_RADIUS; y <= FIXTURE_RADIUS; y++) {
                for (int z = -FIXTURE_RADIUS; z <= FIXTURE_RADIUS; z++) {
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

    private static Vec3 centerVec(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static BlockPos[] fixtureRow(int row) {
        BlockPos[] result = new BlockPos[TOTAL_PAIRS];
        int startX = -80;
        int spacing = 32;
        int z = -48 + row * spacing;
        for (int i = 0; i < TOTAL_PAIRS; i++) {
            result[i] = new BlockPos(startX + i * spacing, 96, z);
        }
        return result;
    }

    private static BlockPos[] concatFixtures() {
        BlockPos[] result = new BlockPos[TOTAL_PAIRS * 4];
        int offset = 0;
        for (BlockPos[] row : List.of(NO_DROPS_VANILLA, NO_DROPS_FAST, DEFAULT_VANILLA, DEFAULT_FAST)) {
            System.arraycopy(row, 0, result, offset, row.length);
            offset += row.length;
        }
        return result;
    }

    private static Stats stats(List<Double> values) {
        if (values.isEmpty()) return new Stats(Double.NaN, Double.NaN, Double.NaN);
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int last = sorted.length - 1;
        return new Stats(
            sorted[last / 2],
            sorted[(int)Math.floor(last * 0.25D)],
            sorted[(int)Math.ceil(last * 0.75D)]
        );
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    private static int medianInt(List<Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    private static long medianLong(List<Long> values) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void fail(String reason) {
        if (phase == Phase.FAILED) return;
        phase = Phase.FAILED;
        System.err.println(SERVER_FAIL_MARKER + " " + reason);
    }

    private record Stats(double median, double p25, double p75) {}

    private enum Phase {
        PREPARE,
        WAIT_FOR_CLIENT,
        SETTLE_CLIENT,
        COLLECT_BASELINE,
        RUN_NO_DROPS_VANILLA,
        WAIT_AFTER_NO_DROPS_VANILLA,
        WAIT_NO_DROPS_FAST,
        SETTLE_NO_DROPS_FAST,
        RUN_DEFAULT_VANILLA,
        WAIT_AFTER_DEFAULT_VANILLA,
        WAIT_DEFAULT_FAST,
        SETTLE_DEFAULT_FAST,
        DONE,
        FAILED
    }
}
