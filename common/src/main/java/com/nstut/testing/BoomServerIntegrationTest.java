package com.nstut.testing;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nstut.explosion.ExplosionScheduler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
import java.util.*;
import java.lang.management.ManagementFactory;

/** Real Level.explode versus the production scheduler, with exact seeded craters and
 * a network command acknowledgement of every client block/light digest. No timing assertions.
 */
public final class BoomServerIntegrationTest {
    public static final String PROPERTY = "perfomant_boom.liveTest";
    public static final int WARMUPS=3, MEASURED=5;
    public record Trial(int index, String scenario, int pair, boolean warmup, boolean fast,
                        int x, int y, int z, int radius, float power, long seed) {
        public BlockPos center() { return new BlockPos(x,y,z); }
    }
    public record Expected(String token, Trial trial, BoomStateDigest.Snapshot expected) {}
    private enum Phase { PREPARE, JOIN, BASELINE, BEGIN, RUN, ENGINE, SETTLE, ACK, SHUTDOWN, PERSIST, FAILED }
    private static final List<Trial> TRIALS=trials();
    private static final List<Map<String,Object>> samples=new ArrayList<>();
    private static final List<Double> tickMs=new ArrayList<>();
    private static final List<Double> tickCpuMs=new ArrayList<>();
    private static final List<Double> tickAllocatedKiB=new ArrayList<>();
    private static com.sun.management.ThreadMXBean resourceCounters;
    private static long tickCpuStart, tickAllocationStart;
    private static final Map<String,BoomStateDigest.Snapshot> paired=new HashMap<>();
    private static MinecraftServer activeServer;
    private static ServerPlayer observer;
    private static Phase phase=Phase.PREPARE;
    private static int next, ticks, shutdownTicks;
    private static boolean ready, begun, originalDrops, clientDone, stopping;
    private static long tickStart, explosionStart;
    private static double activeWorkMs, scheduleWallMs;
    private static ExplosionScheduler.ExplosionMetrics metrics;
    private static Expected expected;
    private BoomServerIntegrationTest() {}
    public static boolean isArmed() { return Boolean.getBoolean(PROPERTY); }
    public static List<Trial> fixtures() { return TRIALS; }
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!isArmed()) return;
        dispatcher.register(Commands.literal("perfomant_boom_live_ready").executes(c -> {
            if (observer != null && c.getSource().getEntity()==observer) ready=true;
            return 1;
        }));
        dispatcher.register(Commands.literal("perfomant_boom_live_begin")
            .then(Commands.argument("index",IntegerArgumentType.integer(0)).executes(c -> {
                if (c.getSource().getEntity()==observer && phase==Phase.BEGIN && IntegerArgumentType.getInteger(c,"index")==next) begun=true;
                return 1;
            })));
        dispatcher.register(Commands.literal("perfomant_boom_live_ack")
            .then(Commands.argument("index",IntegerArgumentType.integer(0))
                .then(Commands.argument("token",StringArgumentType.word())
                    .then(Commands.argument("blocks",StringArgumentType.word())
                        .then(Commands.argument("light",StringArgumentType.word()).executes(c -> {
                            if (c.getSource().getEntity()!=observer) return 0;
                            acknowledge(IntegerArgumentType.getInteger(c,"index"),StringArgumentType.getString(c,"token"),
                                StringArgumentType.getString(c,"blocks"),StringArgumentType.getString(c,"light"));
                            return 1;
                        }))))));
        dispatcher.register(Commands.literal("perfomant_boom_live_done").executes(c -> {
            if (c.getSource().getEntity()==observer && phase==Phase.SHUTDOWN) {
                clientDone=true;
                BoomStateDigest.write("client-receipt.json",Map.of("token",BoomStateDigest.token(),"samples",samples.size()));
            }
            return 1;
        }));
    }
    public static void onServerTickStart(MinecraftServer server) {
        if (!isArmed()) return;
        if (activeServer!=server) {
            activeServer=server; observer=null; phase=Phase.PREPARE; next=0; ticks=0;
            ready=false; begun=false; clientDone=false; stopping=false; samples.clear(); paired.clear(); tickMs.clear();
        }
        if (resourceCounters==null) {
            var base=ManagementFactory.getThreadMXBean();
            if (!(base instanceof com.sun.management.ThreadMXBean counters)
                    || !counters.isCurrentThreadCpuTimeSupported() || !counters.isThreadAllocatedMemorySupported())
                throw new IllegalStateException("Live benchmark requires CPU and allocation counters");
            resourceCounters=counters;
            counters.setThreadCpuTimeEnabled(true);
            counters.setThreadAllocatedMemoryEnabled(true);
            BoomStateDigest.write("resource-environment.json",Map.of(
                "javaVersion",System.getProperty("java.version"),"javaVm",System.getProperty("java.vm.name"),
                "os",System.getProperty("os.name"),"architecture",System.getProperty("os.arch"),
                "logicalProcessors",Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes",Runtime.getRuntime().maxMemory()));
        }
        tickCpuStart=resourceCounters.getCurrentThreadCpuTime();
        tickAllocationStart=resourceCounters.getCurrentThreadAllocatedBytes();
        tickStart=System.nanoTime();
    }
    public static void tick(MinecraftServer server) {
        if (!isArmed()) return;
        // Measured START -> END hook duration excludes the oracle/hash/file-I/O below.
        double observed=(System.nanoTime()-tickStart)/1_000_000.0;
        double observedCpu=(resourceCounters.getCurrentThreadCpuTime()-tickCpuStart)/1_000_000.0;
        double observedAllocated=(resourceCounters.getCurrentThreadAllocatedBytes()-tickAllocationStart)/1024.0;
        boolean measure=phase==Phase.RUN || phase==Phase.ENGINE || phase==Phase.SETTLE;
        double inlineWork=0, inlineCpu=0, inlineAllocated=0;
        try {
            ServerLevel level=server.overworld();
            ticks++;
            switch (phase) {
                case PREPARE -> {
                    originalDrops=level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS);
                    if ("1".equals(System.getenv("PERFOMANT_BOOM_RELOAD"))) {
                        for (Trial trial:TRIALS) {
                            int r=trial.radius();
                            for (int x=(trial.x()-r)>>4;x<=((trial.x()+r)>>4);x++)
                                for (int z=(trial.z()-r)>>4;z<=((trial.z()+r)>>4);z++) level.getChunk(x,z);
                        }
                        phase=Phase.PERSIST; ticks=0;
                        break;
                    }
                    for (Trial trial:TRIALS) fill(level,trial);
                    BoomStateDigest.write("fixtures.json",TRIALS);
                    System.out.println("PERFOMANT_BOOM_E2E_FIXTURE_READY fixtures="+TRIALS.size()+" warmupPairs="+WARMUPS+" measuredTrials="+MEASURED+" stressPairs=1");
                    phase=Phase.JOIN; ticks=0;
                }
                case JOIN -> {
                    if (observer==null && !server.getPlayerList().getPlayers().isEmpty()) {
                        observer=server.getPlayerList().getPlayers().get(0);
                        observer.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                        observer.teleportTo(level,32.5,130,0.5,180,25);
                    }
                    if (ready) { phase=Phase.BASELINE; ticks=0; }
                    else if (ticks>2400) throw new IllegalStateException("Real client did not become ready");
                }
                case BASELINE -> { if (ticks>=100) begin(); }
                case BEGIN -> {
                    // Two coordination commands per trial cost 40 spam-counter units.
                    // Pace outside measurement instead of granting the observer operator powers.
                    if (begun && ticks>=40) { phase=Phase.RUN; ticks=0; }
                    else if (ticks>1200) throw new IllegalStateException("Client begin acknowledgement timed out sample="+next);
                }
                case RUN -> {
                    Trial t=TRIALS.get(next);
                    drops(level,!t.scenario().contains("no-drops"));
                    // Test-only isolated worlds; identical ray RNG sequence for BOTH implementations.
                    level.getRandom().setSeed(t.seed());
                    metrics=null; tickMs.clear(); tickCpuMs.clear(); tickAllocatedKiB.clear();
                    long callCpu=resourceCounters.getCurrentThreadCpuTime();
                    long callAllocated=resourceCounters.getCurrentThreadAllocatedBytes();
                    explosionStart=System.nanoTime();
                    if (t.fast()) {
                        ExplosionScheduler.scheduleTracked(level,Vec3.atCenterOf(t.center()),t.power(),m -> metrics=m);
                        inlineWork=(System.nanoTime()-explosionStart)/1_000_000.0;
                        phase=Phase.ENGINE;
                    } else {
                        level.explode(null,t.x()+0.5,t.y()+0.5,t.z()+0.5,t.power(),false,Level.ExplosionInteraction.BLOCK);
                        activeWorkMs=(System.nanoTime()-explosionStart)/1_000_000.0;
                        inlineWork=activeWorkMs; scheduleWallMs=activeWorkMs; phase=Phase.SETTLE;
                    }
                    inlineCpu=(resourceCounters.getCurrentThreadCpuTime()-callCpu)/1_000_000.0;
                    inlineAllocated=(resourceCounters.getCurrentThreadAllocatedBytes()-callAllocated)/1024.0;
                    ticks=0;
                }
                case ENGINE -> {
                    if (metrics!=null) { activeWorkMs=metrics.workMs(); scheduleWallMs=metrics.wallMs(); phase=Phase.SETTLE; ticks=0; }
                    else if (ticks>2400) throw new IllegalStateException("Scheduler did not complete sample="+next);
                }
                case SETTLE -> {
                    if (ticks>=10 && !level.getLightEngine().hasLightWork()) publishExpected(level);
                    else if (ticks>1200) throw new IllegalStateException("Deferred light work did not settle");
                }
                case ACK -> { if (ticks>1200) throw new IllegalStateException("Client state did not match authoritative snapshot sample="+next); }
                case SHUTDOWN -> {
                    if (clientDone && ++shutdownTicks>=20) {
                        System.out.println("PERFOMANT_BOOM_E2E_SERVER_PASS samples="+samples.size()+" exactClientAcks="+samples.size()+" exactPairedCraters="+paired.size());
                        stopServer(server);
                    } else if (ticks>600) throw new IllegalStateException("Missing final client receipt");
                }
                case PERSIST -> {
                    if (ticks>=100 && !level.getLightEngine().hasLightWork()) {
                        for (Trial t:TRIALS) {
                            var path=BoomStateDigest.evidence().resolve(String.format(Locale.ROOT,"expected-%02d.json",t.index()));
                            var saved=BoomStateDigest.JSON.fromJson(java.nio.file.Files.readString(path),Expected.class);
                            var actual=BoomStateDigest.snapshot(level,t.center(),t.radius());
                            if (!actual.equals(saved.expected())) throw new IllegalStateException("Save/reload drift sample="+t.index()+" saved="+saved.expected()+" actual="+actual);
                        }
                        BoomStateDigest.write("persistence.json",Map.of("exactFixtures",TRIALS.size(),"token",BoomStateDigest.token()));
                        System.out.println("PERFOMANT_BOOM_E2E_PERSISTENCE_PASS exactFixtures="+TRIALS.size());
                        stopServer(server);
                    } else if (ticks>1200) throw new IllegalStateException("Reload lighting did not settle");
                }
                case FAILED -> stopServer(server);
            }
        } catch (Throwable e) {
            phase=Phase.FAILED;
            System.err.println("PERFOMANT_BOOM_E2E_SERVER_FAIL "+e);
            e.printStackTrace();
        } finally {
            if (measure) {
                tickMs.add(observed+inlineWork);
                tickCpuMs.add(observedCpu+inlineCpu);
                tickAllocatedKiB.add(observedAllocated+inlineAllocated);
            }
        }
    }
    private static void stopServer(MinecraftServer server) {
        if (stopping) return;
        stopping=true;
        int exitCode=phase==Phase.FAILED ? 1 : 0;
        Thread serverThread=Thread.currentThread();
        server.halt(false);
        // Architectury's development file watcher keeps a JVM alive after Minecraft
        // exits. Wait for the ENTIRE server thread (including finally/save/close),
        // then exit normally. Never substitute this for completion or saved-state checks.
        Thread exit=new Thread(() -> {
            try {
                serverThread.join(120_000);
                if (serverThread.isAlive()) {
                    System.err.println("PERFOMANT_BOOM_E2E_SERVER_FAIL server thread did not stop");
                    System.exit(1);
                }
                System.out.println("PERFOMANT_BOOM_E2E_SERVER_CLEAN_SHUTDOWN");
                System.exit(exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); System.exit(1);
            }
        },"boom-test-clean-exit");
        exit.setDaemon(true); exit.start();
    }

    private static void begin() {
        begun=false; ticks=0; phase=Phase.BEGIN;
        // A client can hold the current file open while Windows rejects replacement.
        // Publish each trial once under its own name instead of replacing a read file.
        BoomStateDigest.write(String.format(Locale.ROOT,"begin-%02d.json",next),TRIALS.get(next));
    }
    private static void publishExpected(ServerLevel level) {
        Trial t=TRIALS.get(next);
        BoomStateDigest.requireShell(level,t.center(),t.radius());
        var snapshot=BoomStateDigest.snapshot(level,t.center(),t.radius());
        if (snapshot.air()==0 || snapshot.maxBlockLight()!=0 || snapshot.blockEntities()!=0)
            throw new IllegalStateException("Fixture did not converge sample="+next+" "+snapshot);
        String key=t.scenario()+":"+t.pair();
        var other=paired.putIfAbsent(key,snapshot);
        if (other!=null && !other.blocks().equals(snapshot.blocks()))
            throw new IllegalStateException("Exact seeded vanilla/fast crater mismatch "+key+" expected="+other+" actual="+snapshot);
        expected=new Expected(BoomStateDigest.token(),t,snapshot);
        BoomStateDigest.write(String.format(Locale.ROOT,"expected-%02d.json",next),expected);
        phase=Phase.ACK; ticks=0;
    }
    private static void acknowledge(int index,String token,String blocks,String light) {
        if (phase!=Phase.ACK || index!=next || expected==null) return;
        if (!Objects.equals(token,BoomStateDigest.token()) || !expected.expected().blocks().equals(blocks) || !expected.expected().light().equals(light))
            throw new IllegalArgumentException("Wrong client state receipt");
        Trial t=TRIALS.get(next);
        Map<String,Object> sample=new LinkedHashMap<>();
        sample.put("trial",t); sample.put("authoritative",expected.expected());
        sample.put("activeWorkMs",activeWorkMs); sample.put("schedulerCompletionWallMs",scheduleWallMs);
        sample.put("clientAcknowledgedWallMs",(System.nanoTime()-explosionStart)/1_000_000.0);
        sample.put("observedServerTickMs",List.copyOf(tickMs));
        sample.put("observedServerThreadCpuMs",List.copyOf(tickCpuMs));
        sample.put("observedServerThreadAllocatedKiB",List.copyOf(tickAllocatedKiB));
        sample.put("totalObservedServerThreadCpuMs",tickCpuMs.stream().mapToDouble(Double::doubleValue).sum());
        sample.put("totalObservedServerThreadAllocatedKiB",tickAllocatedKiB.stream().mapToDouble(Double::doubleValue).sum());
        sample.put("maxObservedServerTickMs",tickMs.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        if (metrics!=null) sample.put("scheduler",metrics);
        samples.add(sample);
        BoomStateDigest.write(String.format(Locale.ROOT,"server-sample-%02d.json",next),sample);
        System.out.println("PERFOMANT_BOOM_E2E_SAMPLE index="+next+" fast="+t.fast()+" scenario="+t.scenario()+" air="+expected.expected().air());
        ServerLevel level=activeServer.overworld();
        level.getEntitiesOfClass(ItemEntity.class,new AABB(t.center()).inflate(t.radius()+4)).forEach(ItemEntity::discard);
        next++;
        if (next==TRIALS.size()) {
            drops(level,originalDrops);
            BoomStateDigest.write("server-samples.json",samples);
            BoomStateDigest.write("complete.json",Map.of("samples",samples.size(),"token",BoomStateDigest.token()));
            phase=Phase.SHUTDOWN; ticks=0; shutdownTicks=0;
        } else begin();
    }
    private static void drops(ServerLevel level,boolean value) { level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(value,level.getServer()); }
    private static void fill(ServerLevel level,Trial t) {
        var p=new BlockPos.MutableBlockPos(); int r=t.radius();
        for (int x=-r;x<=r;x++) for (int y=-r;y<=r;y++) for (int z=-r;z<=r;z++) {
            p.set(t.x()+x,t.y()+y,t.z()+z);
            boolean shell=Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))==r;
            level.setBlock(p,shell?Blocks.BEDROCK.defaultBlockState():Blocks.NETHERRACK.defaultBlockState(),2);
        }
        level.setBlock(t.center(),Blocks.GLOWSTONE.defaultBlockState(),2);
    }
    private static List<Trial> trials() {
        List<Trial> result=new ArrayList<>();
        for (int scenario=0;scenario<2;scenario++) for (int pair=0;pair<WARMUPS+MEASURED;pair++) {
            for (int order=0;order<2;order++) {
                boolean fast=(order==(pair%2==0?1:0));
                result.add(new Trial(result.size(),scenario==0?"no-drops":"default-loot",pair,pair<WARMUPS,fast,
                    -80+pair*32,96,-48+(scenario*2+(fast?1:0))*32,13,10,0xB00B5EEDL+pair));
            }
        }
        for (boolean fast:new boolean[]{true,false}) result.add(new Trial(result.size(),"stress-no-drops",0,false,fast,
            fast?0:64,176,0,29,24,0x57E55L));
        return List.copyOf(result);
    }
}
