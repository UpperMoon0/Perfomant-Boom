package com.nstut.explosion;

import com.nstut.testing.BoomServerIntegrationTest;
import com.nstut.testing.BoomStateDigest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Opt-in, isolated dedicated-server lifecycle regressions. No mock chunks or timed sleeps.
 * Both loaders invoke the same production driver with an expired deadline to force a yield.
 */
public final class BoomLifecycleIntegrationTest {
    private static final BlockPos CENTER = new BlockPos(10008, 96, 10008);
    private static final BlockPos CONTROL = CENTER.offset(64, 0, 0);
    private static final int RADIUS = 6;
    private static final List<Heightmap.Types> HEIGHTMAPS = List.of(
        Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    private static Run active;

    private BoomLifecycleIntegrationTest() {}

    public static boolean isArmed() {
        return BoomServerIntegrationTest.isArmed() && System.getenv("PERFOMANT_BOOM_LIFECYCLE") != null;
    }

    public static void tick(MinecraftServer server) {
        if (active == null || active.server != server) active = new Run(server);
        active.tick();
    }

    private enum Phase { PREPARE, RAY_BASELINE, RAY_UNLOAD, MUTATION_BASELINE,
        MUTATION_UNLOAD, PARTIAL_PREPARE, PARTIAL_BASELINE, RELOAD, STOPPED }

    public record Partial(String token, String blocks, int air, int processed, int total,
                          int changed, long[][] heightmaps, int[] skySources) {}

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        Phase phase = Phase.PREPARE;
        int ticks;
        LevelChunk detached;
        FastExplosionEngine.IncrementalCalculation rays;
        int raySelected;
        String mutationPrefix;
        int detachedAir;
        int firstChanged;
        boolean completed;
        Partial saved;

        Run(MinecraftServer server) { this.server = server; this.level = server.overworld(); }

        void tick() {
            if (phase == Phase.STOPPED) return;
            try {
                require(server.getPlayerList().getPlayers().isEmpty(), "lifecycle fixture requires an isolated player-free server");
                if (++ticks > 1200) throw new IllegalStateException("lifecycle phase timeout: " + phase);
                switch (phase) {
                    case PREPARE -> {
                        String mode = System.getenv("PERFOMANT_BOOM_LIFECYCLE");
                        if ("reload".equals(mode)) {
                            saved = BoomStateDigest.JSON.fromJson(Files.readString(
                                BoomStateDigest.evidence().resolve("lifecycle-partial.json")), Partial.class);
                            require(BoomStateDigest.token().equals(saved.token()), "foreign partial evidence token");
                            require(saved.processed() > 0 && saved.processed() < saved.total(), "saved task was not partial");
                            level.getChunkAt(CENTER); level.getChunkAt(CONTROL);
                            move(Phase.RELOAD);
                        } else {
                            require("exercise".equals(mode), "unknown lifecycle mode");
                            fill(CENTER);
                            move(Phase.RAY_BASELINE);
                        }
                    }
                    case RAY_BASELINE -> {
                        if (!settled()) break;
                        detached = level.getChunkAt(CENTER);
                        rays = FastExplosionEngine.create(level, Vec3.atCenterOf(CENTER), 10);
                        require(!rays.processUntil(Long.MIN_VALUE), "ray calculation did not yield");
                        raySelected = rays.blockCount();
                        require(raySelected > 0, "ray fixture selected nothing");
                        move(Phase.RAY_UNLOAD);
                    }
                    case RAY_UNLOAD -> {
                        LevelChunk replacement = reloadedChunk();
                        if (replacement == null) break;
                        // Change only the NEW live chunk. A leaked cache keeps seeing the
                        // detached netherrack instance and selects more positions.
                        BlockPos.betweenClosed(CENTER.offset(-RADIUS,-RADIUS,-RADIUS),
                            CENTER.offset(RADIUS,RADIUS,RADIUS)).forEach(p -> level.setBlock(p, Blocks.BEDROCK.defaultBlockState(), 2));
                        require(rays.processUntil(Long.MAX_VALUE), "ray calculation did not finish");
                        require(rays.blockCount() == raySelected, "calculator read a detached chunk after unload/reload");
                        BoomStateDigest.write("lifecycle-ray-unload.json", Map.of("token", BoomStateDigest.token(),
                            "distinctChunk", true, "selectedBefore", raySelected, "selectedAfter", rays.blockCount()));
                        rays = null; detached = null;
                        fill(CENTER);
                        move(Phase.MUTATION_BASELINE);
                    }
                    case MUTATION_BASELINE -> {
                        if (!settled()) break;
                        startPartial();
                        detached = level.getChunkAt(CENTER);
                        mutationPrefix = BoomStateDigest.snapshot(level, CENTER, RADIUS).blocks();
                        detachedAir = countAir(detached);
                        firstChanged = ExplosionScheduler.pendingMutation().changed();
                        move(Phase.MUTATION_UNLOAD);
                    }
                    case MUTATION_UNLOAD -> {
                        LevelChunk replacement = reloadedChunk();
                        if (replacement == null) break;
                        require(BoomStateDigest.snapshot(level, CENTER, RADIUS).blocks().equals(mutationPrefix),
                            "partial removals lost during chunk unload/save/reload");
                        ExplosionScheduler.tickUntil(server, Long.MIN_VALUE);
                        var progress = ExplosionScheduler.pendingMutation();
                        require(progress != null && progress.changed() > firstChanged, "mutation did not resume");
                        require(countAir(detached) == detachedAir, "resumed task mutated the detached chunk");
                        require(countAir(replacement) > detachedAir, "resumed task did not mutate the live replacement");
                        BoomStateDigest.write("lifecycle-mutation-unload.json", Map.of("token", BoomStateDigest.token(),
                            "distinctChunk", true, "savedPrefixMatched", true, "detachedUnchanged", true,
                            "changedBefore", firstChanged, "changedAfter", progress.changed()));
                        ExplosionScheduler.tickUntil(server, Long.MAX_VALUE);
                        require(completed && ExplosionScheduler.pendingTasks() == 0, "unload test task did not complete");
                        detached = null;
                        move(Phase.PARTIAL_PREPARE);
                    }
                    case PARTIAL_PREPARE -> {
                        if (!settled()) break;
                        fill(CENTER); fill(CONTROL);
                        move(Phase.PARTIAL_BASELINE);
                    }
                    case PARTIAL_BASELINE -> {
                        if (!settled()) break;
                        level.getChunkAt(CONTROL);
                        startPartial();
                        var progress = ExplosionScheduler.pendingMutation();
                        require(progress != null && !completed, "shutdown fixture finished instead of yielding");
                        // Apply exactly the committed removals to an independent fixture
                        // through VANILLA Level#setBlock. Both worlds stop in this tick.
                        int copied = 0;
                        for (BlockPos pos : BlockPos.betweenClosed(CENTER.offset(-RADIUS,-RADIUS,-RADIUS),
                            CENTER.offset(RADIUS,RADIUS,RADIUS))) {
                            BlockPos control = pos.offset(64,0,0);
                            var state = level.getBlockState(pos);
                            if (state != level.getBlockState(control)) {
                                require(state.isAir(), "unexpected non-removal in partial fixture");
                                level.setBlock(control, state, 2);
                                copied++;
                            }
                        }
                        require(copied == progress.changed(), "control removal count mismatch");
                        require(level.getBlockState(CENTER).isAir(), "partial slice did not remove glowstone");
                        LevelChunk chunk = level.getChunkAt(CENTER);
                        require(chunk.isUnsaved(), "partial mutation not dirty before normal shutdown");
                        checkMetadata(chunk);
                        var snapshot = BoomStateDigest.snapshot(level, CENTER, RADIUS);
                        saved = new Partial(BoomStateDigest.token(), snapshot.blocks(), snapshot.air(),
                            progress.processed(), progress.total(), progress.changed(), heightmaps(chunk), skySources(chunk));
                        BoomStateDigest.write("lifecycle-partial.json", saved);
                        System.out.println("PERFOMANT_BOOM_LIFECYCLE_PARTIAL_STOP_PASS processed=" + progress.processed()
                            + " total=" + progress.total() + " changed=" + progress.changed());
                        // Do not run another scheduler slice, save manually, or wait for
                        // lighting here. Minecraft's ordinary stop/save must persist it.
                        stop(0);
                    }
                    case RELOAD -> {
                        if (!settled()) break;
                        require(ExplosionScheduler.pendingTasks() == 0, "fresh JVM inherited scheduler tasks");
                        var actual = BoomStateDigest.snapshot(level, CENTER, RADIUS);
                        var control = BoomStateDigest.snapshot(level, CONTROL, RADIUS);
                        require(actual.blocks().equals(saved.blocks()) && actual.air() == saved.air(),
                            "normal shutdown lost partial removals or completed pending work");
                        require(actual.equals(control), "partial-save block/light state differs from vanilla control: " + actual + " / " + control);
                        require(actual.maxBlockLight() == 0, "removed partial-slice glowstone still emits light");
                        LevelChunk chunk = level.getChunkAt(CENTER);
                        checkMetadata(chunk);
                        require(Arrays.deepEquals(heightmaps(chunk), saved.heightmaps()), "heightmap persistence drift");
                        require(Arrays.equals(skySources(chunk), saved.skySources()), "skylight source persistence drift");
                        BoomStateDigest.write("lifecycle-persistence.json", Map.of("token", BoomStateDigest.token(),
                            "partialMatched", true, "vanillaBlockLightMatched", true, "metadataMatched", true,
                            "processed", saved.processed(), "total", saved.total(), "changed", saved.changed()));
                        System.out.println("PERFOMANT_BOOM_LIFECYCLE_RELOAD_PASS changed=" + saved.changed());
                        stop(0);
                    }
                    case STOPPED -> {}
                }
            } catch (Throwable failure) {
                System.err.println("PERFOMANT_BOOM_E2E_SERVER_FAIL lifecycle=" + phase + " " + failure);
                failure.printStackTrace();
                stop(1);
            }
        }

        boolean settled() { return ticks >= 40 && !level.getLightEngine().hasLightWork(); }
        void move(Phase next) { phase = next; ticks = 0; }

        LevelChunk reloadedChunk() {
            ChunkPos pos = new ChunkPos(CENTER);
            // getChunkNow does not issue an UNKNOWN ticket. Let normal server ticks
            // remove the holder; acquiring early could resurrect the same instance.
            if (level.getChunkSource().getChunkNow(pos.x,pos.z) != null) return null;
            LevelChunk replacement = level.getChunk(pos.x,pos.z);
            return replacement == detached ? null : replacement;
        }

        void startPartial() {
            LevelChunk chunk = level.getChunkAt(CENTER);
            level.getChunkSource().save(true);
            require(!chunk.isUnsaved(), "fixture must be clean before partial mutation");
            completed = false;
            level.getRandom().setSeed(0x51CE5L);
            ExplosionScheduler.scheduleTracked(level, Vec3.atCenterOf(CENTER), 10, metrics -> completed = true);
            for (int i=0; i<10000 && ExplosionScheduler.pendingMutation() == null && !completed; i++) {
                ExplosionScheduler.tickUntil(server, Long.MIN_VALUE);
            }
            var progress = ExplosionScheduler.pendingMutation();
            require(progress != null && progress.processed() > 0 && progress.processed() < progress.total()
                && progress.changed() > 0 && !completed, "could not suspend inside a mutated chunk");
            require(progress.chunk().equals(new ChunkPos(CENTER)), "mutation fixture escaped its single chunk");
            require(chunk.isUnsaved(), "successful writes did not dirty an otherwise clean chunk");
        }

        void fill(BlockPos center) {
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS,-RADIUS,-RADIUS),
                center.offset(RADIUS,RADIUS,RADIUS))) {
                // Open top: the first removed block changes column skylight metadata,
                // unlike the sealed bedrock shell in the ordinary E2E fixtures.
                level.setBlock(pos, pos.getY() <= center.getY() ? Blocks.NETHERRACK.defaultBlockState()
                    : Blocks.AIR.defaultBlockState(), 2);
            }
            level.setBlock(center, Blocks.GLOWSTONE.defaultBlockState(), 2);
        }

        void stop(int code) {
            if (phase == Phase.STOPPED) return;
            phase = Phase.STOPPED;
            Thread serverThread = Thread.currentThread();
            server.halt(false);
            Thread exit = new Thread(() -> {
                try {
                    serverThread.join(120_000);
                    if (serverThread.isAlive()) {
                        System.err.println("PERFOMANT_BOOM_E2E_SERVER_FAIL lifecycle shutdown timed out");
                        System.exit(1);
                    }
                    System.out.println("PERFOMANT_BOOM_LIFECYCLE_CLEAN_SHUTDOWN");
                    System.exit(code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); System.exit(1);
                }
            }, "boom-lifecycle-clean-exit");
            exit.setDaemon(true);
            exit.start();
        }
    }

    private static int countAir(LevelChunk chunk) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(CENTER.offset(-RADIUS,-RADIUS,-RADIUS),
            CENTER.offset(RADIUS,RADIUS,RADIUS))) if (chunk.getBlockState(pos).isAir()) count++;
        return count;
    }

    private static long[][] heightmaps(LevelChunk chunk) {
        return HEIGHTMAPS.stream().map(type -> chunk.getOrCreateHeightmapUnprimed(type).getRawData().clone()).toArray(long[][]::new);
    }

    private static int[] skySources(LevelChunk chunk) {
        int[] result = new int[256];
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) result[x*16+z] = chunk.getSkyLightSources().getLowestSourceY(x,z);
        return result;
    }

    private static void checkMetadata(LevelChunk chunk) {
        // Recompute independent skylight expectations WITHOUT modifying the live data.
        var expected = new ChunkSkyLightSources(chunk);
        expected.fillFrom(chunk);
        var pos = new BlockPos.MutableBlockPos();
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) {
            require(chunk.getSkyLightSources().getLowestSourceY(x,z) == expected.getLowestSourceY(x,z), "partial skylight sources stale");
            for (var type : HEIGHTMAPS) {
                int y = chunk.getMaxBuildHeight()-1;
                while (y >= chunk.getMinBuildHeight()) {
                    pos.set(chunk.getPos().getMinBlockX()+x,y,chunk.getPos().getMinBlockZ()+z);
                    if (type.isOpaque().test(chunk.getBlockState(pos))) break;
                    y--;
                }
                require(chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(x,z) == y+1,
                    "partial heightmap stale: " + type);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
