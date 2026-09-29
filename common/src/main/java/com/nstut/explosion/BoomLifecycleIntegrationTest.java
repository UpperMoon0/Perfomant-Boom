package com.nstut.explosion;

import com.nstut.testing.BoomServerIntegrationTest;
import com.nstut.testing.BoomStateDigest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
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
    // Keep the shutdown fixture independent of the destructive unload probes.
    private static final BlockPos PARTIAL_CENTER = CENTER.offset(0, 0, 128);
    private static final BlockPos PARTIAL_CONTROL = PARTIAL_CENTER.offset(64, 0, 0);
    private static final int RADIUS = 6;
    private static final List<Heightmap.Types> HEIGHTMAPS = List.of(
        Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    private static final TicketType<ChunkPos> OBSERVATION = TicketType.create(
        "perfomant_boom_lifecycle_observation", java.util.Comparator.comparingLong(ChunkPos::toLong));
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
        MUTATION_UNLOAD, PARTIAL_PREPARE, PARTIAL_BASELINE, RELOAD, PHYSICS_RELOAD, STOPPED }

    public record Partial(String token, String blocks, int air, int processed, int total,
                          int changed, long[][] heightmaps, int[] skySources,
                          boolean interiorSupport, boolean sandPendingRemoval,
                          boolean fastSandTick, boolean controlSandTick) {}

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        BlockPos center = CENTER;
        BlockPos control = CONTROL;
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
        int observationRadius;
        boolean fastSandTickRestored;
        boolean controlSandTickRestored;
        final java.util.Set<ChunkPos> observedChunks = new java.util.HashSet<>();

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
                            center = PARTIAL_CENTER;
                            control = PARTIAL_CONTROL;
                            saved = BoomStateDigest.JSON.fromJson(Files.readString(
                                BoomStateDigest.evidence().resolve("lifecycle-partial.json")), Partial.class);
                            require(BoomStateDigest.token().equals(saved.token()), "foreign partial evidence token");
                            require(saved.processed() > 0 && saved.processed() < saved.total(), "saved task was not partial");
                            holdObservationChunks(0);
                            move(Phase.RELOAD);
                        } else {
                            require("exercise".equals(mode), "unknown lifecycle mode");
                            fill(center);
                            move(Phase.RAY_BASELINE);
                        }
                    }
                    case RAY_BASELINE -> {
                        if (!settled()) break;
                        detached = level.getChunkAt(center);
                        rays = FastExplosionEngine.create(level, Vec3.atCenterOf(center), 10);
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
                        BlockPos.betweenClosed(center.offset(-RADIUS,-RADIUS,-RADIUS),
                            center.offset(RADIUS,RADIUS,RADIUS)).forEach(p -> level.setBlock(p, Blocks.BEDROCK.defaultBlockState(), 2));
                        require(rays.processUntil(Long.MAX_VALUE), "ray calculation did not finish");
                        require(rays.blockCount() == raySelected, "calculator read a detached chunk after unload/reload");
                        BoomStateDigest.write("lifecycle-ray-unload.json", Map.of("token", BoomStateDigest.token(),
                            "distinctChunk", true, "selectedBefore", raySelected, "selectedAfter", rays.blockCount()));
                        rays = null; detached = null;
                        fill(center);
                        move(Phase.MUTATION_BASELINE);
                    }
                    case MUTATION_BASELINE -> {
                        if (!settled()) break;
                        startPartial();
                        detached = level.getChunkAt(center);
                        mutationPrefix = BoomStateDigest.snapshot(level, center, RADIUS).blocks();
                        detachedAir = countAir(detached, center);
                        firstChanged = ExplosionScheduler.pendingMutation().changed();
                        move(Phase.MUTATION_UNLOAD);
                    }
                    case MUTATION_UNLOAD -> {
                        LevelChunk replacement = reloadedChunk();
                        if (replacement == null) break;
                        require(BoomStateDigest.snapshot(level, center, RADIUS).blocks().equals(mutationPrefix),
                            "partial removals lost during chunk unload/save/reload");
                        ExplosionScheduler.tickUntil(server, Long.MIN_VALUE);
                        var progress = ExplosionScheduler.pendingMutation();
                        require(progress != null && progress.changed() > firstChanged, "mutation did not resume");
                        require(countAir(detached, center) == detachedAir, "resumed task mutated the detached chunk");
                        require(countAir(replacement, center) > detachedAir, "resumed task did not mutate the live replacement");
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
                        center = PARTIAL_CENTER;
                        control = PARTIAL_CONTROL;
                        holdObservationChunks(2);
                        fill(center); fill(control);
                        level.setBlock(center.above(), Blocks.SAND.defaultBlockState(), 3);
                        level.setBlock(control.above(), Blocks.SAND.defaultBlockState(), 3);
                        move(Phase.PARTIAL_BASELINE);
                    }
                    case PARTIAL_BASELINE -> {
                        if (!settled()) break;
                        requireSameLight("baseline");
                        require(!level.getBlockTicks().hasScheduledTick(center.above(), Blocks.SAND)
                            && !level.getBlockTicks().hasScheduledTick(control.above(), Blocks.SAND),
                            "baseline sand placement ticks must have drained while supported");
                        require(BoomStateDigest.snapshot(level, center, RADIUS).equals(
                            BoomStateDigest.snapshot(level, control, RADIUS)), "partial/control baseline drift");
                        startPartial();
                        var progress = ExplosionScheduler.pendingMutation();
                        require(progress != null && !completed, "shutdown fixture finished instead of yielding");
                        // Apply exactly the committed removals to an independent fixture
                        // through VANILLA Level#setBlock. Both worlds stop in this tick.
                        int copied = 0;
                        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS,-RADIUS,-RADIUS),
                            center.offset(RADIUS,RADIUS,RADIUS))) {
                            BlockPos controlPos = pos.offset(64,0,0);
                            var state = level.getBlockState(pos);
                            if (state != level.getBlockState(controlPos)) {
                                require(state.isAir(), "unexpected non-removal in partial fixture");
                                level.setBlock(controlPos, state, 3);
                                copied++;
                            }
                        }
                        require(copied == progress.changed(), "control removal count mismatch");
                        require(level.getBlockState(center.above()).is(Blocks.SAND)
                            && level.getBlockState(control.above()).is(Blocks.SAND), "sand must survive the first slice");
                        boolean sandPending = ExplosionScheduler.pendingSelectionContains(center.above());
                        boolean interiorSupport = Arrays.stream(Direction.values()).allMatch(direction ->
                            ExplosionScheduler.pendingSelectionContains(center.relative(direction)));
                        require(sandPending && interiorSupport,
                            "fixture must cover a future candidate above a final-crater interior removal");
                        boolean fastSandTick = level.getBlockTicks().hasScheduledTick(center.above(), Blocks.SAND);
                        boolean controlSandTick = level.getBlockTicks().hasScheduledTick(control.above(), Blocks.SAND);
                        require(controlSandTick, "vanilla control did not schedule falling physics");
                        require(level.getBlockState(center.below()).is(Blocks.NETHERRACK), "sand landing support was removed");
                        System.out.println("PERFOMANT_BOOM_PHYSICS_PRE_STOP fastSandTick=" + fastSandTick
                            + " controlSandTick=" + controlSandTick + " sandPending=" + sandPending);
                        require(level.getBlockState(center).isAir(), "partial slice did not remove glowstone");
                        LevelChunk chunk = level.getChunkAt(center);
                        require(chunk.isUnsaved(), "partial mutation not dirty before normal shutdown");
                        checkMetadata(chunk);
                        var snapshot = BoomStateDigest.snapshot(level, center, RADIUS);
                        saved = new Partial(BoomStateDigest.token(), snapshot.blocks(), snapshot.air(),
                            progress.processed(), progress.total(), progress.changed(), heightmaps(chunk), skySources(chunk),
                            interiorSupport, sandPending, fastSandTick, controlSandTick);
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
                        var actual = BoomStateDigest.snapshot(level, center, RADIUS);
                        var expected = BoomStateDigest.snapshot(level, control, RADIUS);
                        require(actual.blocks().equals(saved.blocks()) && actual.air() == saved.air(),
                            "normal shutdown lost partial removals or completed pending work");
                        requireSameLight("reload");
                        require(actual.equals(expected), "partial-save block/light state differs from vanilla control: " + actual + " / " + expected);
                        require(actual.maxBlockLight() == 0, "removed partial-slice glowstone still emits light");
                        LevelChunk chunk = level.getChunkAt(center);
                        checkMetadata(chunk);
                        require(Arrays.deepEquals(heightmaps(chunk), saved.heightmaps()), "heightmap persistence drift");
                        require(Arrays.equals(skySources(chunk), saved.skySources()), "skylight source persistence drift");
                        BoomStateDigest.write("lifecycle-persistence.json", Map.of("token", BoomStateDigest.token(),
                            "partialMatched", true, "vanillaBlockLightMatched", true, "metadataMatched", true,
                            "processed", saved.processed(), "total", saved.total(), "changed", saved.changed()));
                        // FULL-only observation has not run any falling ticks. First prove
                        // the exact saved prefix/metadata, then allow normal physics to run.
                        fastSandTickRestored = level.getBlockTicks().hasScheduledTick(center.above(), Blocks.SAND);
                        controlSandTickRestored = level.getBlockTicks().hasScheduledTick(control.above(), Blocks.SAND);
                        require(level.getBlockState(center.above()).is(Blocks.SAND)
                            && level.getBlockState(control.above()).is(Blocks.SAND), "physics ran before saved-prefix verification");
                        holdObservationChunks(2);
                        move(Phase.PHYSICS_RELOAD);
                    }
                    case PHYSICS_RELOAD -> {
                        if (!settled()) break;
                        require(ExplosionScheduler.pendingTasks() == 0, "unfinished destruction resumed after restart");
                        var actual = BoomStateDigest.snapshot(level, center, RADIUS);
                        var expected = BoomStateDigest.snapshot(level, control, RADIUS);
                        int fastSandY = sandY(center), controlSandY = sandY(control);
                        BoomStateDigest.write("lifecycle-physics.json", Map.ofEntries(
                            Map.entry("token", BoomStateDigest.token()),
                            Map.entry("interiorSupport", saved.interiorSupport()),
                            Map.entry("sandPendingRemoval", saved.sandPendingRemoval()),
                            Map.entry("fastSandTick", saved.fastSandTick()),
                            Map.entry("controlSandTick", saved.controlSandTick()),
                            Map.entry("fastSandTickRestored", fastSandTickRestored),
                            Map.entry("controlSandTickRestored", controlSandTickRestored),
                            Map.entry("sourceY", center.getY()+1), Map.entry("landingY", center.getY()),
                            Map.entry("fastSandY", fastSandY), Map.entry("controlSandY", controlSandY),
                            Map.entry("tickingTicks", ticks), Map.entry("blockLightMatched", actual.equals(expected))));
                        System.out.println("PERFOMANT_BOOM_PHYSICS_RELOAD fastSandY=" + fastSandY
                            + " controlSandY=" + controlSandY + " ticks=" + ticks);
                        require(fastSandY == center.getY() && controlSandY == center.getY(),
                            "partial-save sand physics differs from vanilla: fast=" + fastSandY + " control=" + controlSandY);
                        require(saved.fastSandTick() && saved.controlSandTick()
                            && fastSandTickRestored && controlSandTickRestored,
                            "required falling ticks were not scheduled before stop and restored from disk");
                        require(level.getBlockState(center.above()).isAir()
                            && level.getBlockState(control.above()).isAir(), "sand remained floating after reload");
                        requireSameLight("physics-reload");
                        require(actual.equals(expected), "post-reload physics differs from vanilla block/light state");
                        checkMetadata(level.getChunkAt(center));
                        checkMetadata(level.getChunkAt(control));
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

        void holdObservationChunks(int radius) {
            // FULL_LEVEL - radius: 0 keeps chunks FULL without ticking, 2 enables
            // entities and block ticks. Both neighborhoods always use identical tickets.
            // No observation tickets exist during the deliberate unload scenarios.
            if (radius != observationRadius) {
                for (ChunkPos pos : observedChunks) {
                    level.getChunkSource().removeRegionTicket(OBSERVATION,pos,observationRadius,pos);
                }
                observedChunks.clear();
                observationRadius = radius;
            }
            for (BlockPos fixtureCenter : List.of(center, control)) {
                ChunkPos origin = new ChunkPos(fixtureCenter);
                for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++) {
                    ChunkPos pos = new ChunkPos(origin.x+x,origin.z+z);
                    if (observedChunks.add(pos)) level.getChunkSource().addRegionTicket(OBSERVATION,pos,radius,pos);
                    level.getChunk(pos.x,pos.z);
                }
            }
        }

        int sandY(BlockPos center) {
            int found = Integer.MIN_VALUE;
            for (int y=center.getY()-RADIUS; y<=center.getY()+RADIUS; y++) {
                if (level.getBlockState(new BlockPos(center.getX(),y,center.getZ())).is(Blocks.SAND)) {
                    require(found == Integer.MIN_VALUE, "sand fixture duplicated a block");
                    found = y;
                }
            }
            return found;
        }

        void requireSameLight(String stage) {
            var differences = new java.util.ArrayList<Map<String,Object>>();
            int count = 0;
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS,-RADIUS,-RADIUS),
                center.offset(RADIUS,RADIUS,RADIUS))) {
                BlockPos other = pos.offset(64,0,0);
                for (LightLayer layer : LightLayer.values()) {
                    int actual = level.getBrightness(layer,pos), control = level.getBrightness(layer,other);
                    if (actual != control) {
                        count++;
                        if (differences.size() < 32) differences.add(Map.of("position",pos.toShortString(),
                            "layer",layer.toString(),"actual",actual,"control",control));
                    }
                }
            }
            BoomStateDigest.write("lifecycle-light-" + stage + ".json", Map.of("token",BoomStateDigest.token(),
                "differenceCount",count,"examples",differences));
            require(count == 0, stage + " light mismatch (" + count + " positions): " + differences);
        }

        boolean settled() { return ticks >= 40 && !level.getLightEngine().hasLightWork(); }
        void move(Phase next) { phase = next; ticks = 0; }

        LevelChunk reloadedChunk() {
            ChunkPos pos = new ChunkPos(center);
            // getChunkNow does not issue an UNKNOWN ticket. Let normal server ticks
            // remove the holder; acquiring early could resurrect the same instance.
            if (level.getChunkSource().getChunkNow(pos.x,pos.z) != null) return null;
            LevelChunk replacement = level.getChunk(pos.x,pos.z);
            return replacement == detached ? null : replacement;
        }

        void startPartial() {
            LevelChunk chunk = level.getChunkAt(center);
            level.getChunkSource().save(true);
            require(!chunk.isUnsaved(), "fixture must be clean before partial mutation");
            completed = false;
            // Seed chosen against vanilla's full affected-position shuffle so the
            // support is in the first 8 processed positions, the sand is later, and
            // all six support neighbors remain future candidates.
            level.getRandom().setSeed(114L);
            ExplosionScheduler.scheduleTracked(level, Vec3.atCenterOf(center), 10, metrics -> completed = true);
            for (int i=0; i<10000 && ExplosionScheduler.pendingMutation() == null && !completed; i++) {
                ExplosionScheduler.tickUntil(server, Long.MIN_VALUE);
            }
            var progress = ExplosionScheduler.pendingMutation();
            require(progress != null && progress.processed() > 0 && progress.processed() < progress.total()
                && progress.changed() > 0 && !completed, "could not suspend inside a mutated chunk");
            require(progress.chunk().equals(new ChunkPos(center)), "mutation fixture escaped its single chunk");
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
            for (ChunkPos pos : observedChunks) level.getChunkSource().removeRegionTicket(OBSERVATION,pos,observationRadius,pos);
            observedChunks.clear();
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

    private static int countAir(LevelChunk chunk, BlockPos center) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS,-RADIUS,-RADIUS),
            center.offset(RADIUS,RADIUS,RADIUS))) if (chunk.getBlockState(pos).isAir()) count++;
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
