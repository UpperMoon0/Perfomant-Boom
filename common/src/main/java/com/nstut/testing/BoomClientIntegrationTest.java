package com.nstut.testing;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.Files;
import java.util.Map;

/** Hash only the actual ClientLevel. No oracle writes and no loose crater-count tolerance. */
public final class BoomClientIntegrationTest {
    private static int next, tick, active=-1, convergedFrames=-1, frames;
    private static boolean ready, finished, doneSent;
    private static long lastFrame;
    private static double maxFrameGapMs;
    private BoomClientIntegrationTest() {}
    public static void onRenderedFrame() {
        if (!BoomServerIntegrationTest.isArmed() || finished) return;
        long now=System.nanoTime();
        if (active>=0 && lastFrame!=0) maxFrameGapMs=Math.max(maxFrameGapMs,(now-lastFrame)/1_000_000.0);
        lastFrame=now; frames++;
    }
    public static void tick() {
        if (!BoomServerIntegrationTest.isArmed() || finished) return;
        Minecraft mc=Minecraft.getInstance();
        if (mc.level==null || mc.player==null || mc.getConnection()==null) return;
        if (++tick%3!=0) return;
        try {
            if (!ready) {
                for (var t:BoomServerIntegrationTest.fixtures()) {
                    if (!mc.level.hasChunkAt(t.center().offset(-t.radius(),0,-t.radius()))
                        || !mc.level.hasChunkAt(t.center().offset(t.radius(),0,t.radius()))
                        || !mc.level.getBlockState(t.center()).is(Blocks.GLOWSTONE)) return;
                }
                ready=true;
                mc.getConnection().sendCommand("perfomant_boom_live_ready");
                System.out.println("PERFOMANT_BOOM_E2E_CLIENT_FIXTURE_SEEN");
            }
            var begin=BoomStateDigest.evidence().resolve(String.format(java.util.Locale.ROOT,"begin-%02d.json",next));
            if (Files.exists(begin)) {
                var t=BoomStateDigest.JSON.fromJson(Files.readString(begin),BoomServerIntegrationTest.Trial.class);
                if (t.index()==next && active!=next) {
                    active=next; frames=0; convergedFrames=-1; lastFrame=0; maxFrameGapMs=0;
                    mc.getConnection().sendCommand("perfomant_boom_live_begin "+next);
                }
            }
            var path=BoomStateDigest.evidence().resolve(String.format(java.util.Locale.ROOT,"expected-%02d.json",next));
            if (active==next && Files.exists(path)) {
                var e=BoomStateDigest.JSON.fromJson(Files.readString(path),BoomServerIntegrationTest.Expected.class);
                if (!java.util.Objects.equals(e.token(),BoomStateDigest.token()) || e.trial().index()!=next)
                    throw new IllegalStateException("Wrong run/sample oracle");
                var actual=BoomStateDigest.snapshot(mc.level,e.trial().center(),e.trial().radius());
                if (actual.equals(e.expected())) {
                    if (convergedFrames<0) convergedFrames=frames;
                    // Observe rendering after convergence, not merely a packet or log marker.
                    if (frames-convergedFrames>=2) {
                        BoomStateDigest.requireShell(mc.level,e.trial().center(),e.trial().radius());
                        BoomStateDigest.write(String.format(java.util.Locale.ROOT,"client-sample-%02d.json",next),Map.of(
                            "index",next,"actual",actual,"renderFrames",frames,"maxObservedFrameGapMs",maxFrameGapMs,
                            "postConvergenceFrames",frames-convergedFrames));
                        mc.getConnection().sendCommand("perfomant_boom_live_ack "+next+" "+e.token()+" "+actual.blocks()+" "+actual.light());
                        next++; active=-1; convergedFrames=-1;
                    }
                } else {
                    convergedFrames=-1;
                    if (tick%120==0) System.out.println("PERFOMANT_BOOM_CLIENT_PENDING sample="+next+" expected="+e.expected()+" actual="+actual);
                }
            }
            if (next==BoomServerIntegrationTest.fixtures().size() && Files.exists(BoomStateDigest.evidence().resolve("complete.json"))) {
                if (!doneSent) { mc.getConnection().sendCommand("perfomant_boom_live_done"); doneSent=true; }
                // Wait for the server to RECEIVE the final receipt before disconnecting.
                if (Files.exists(BoomStateDigest.evidence().resolve("client-receipt.json"))) {
                    System.out.println("PERFOMANT_BOOM_E2E_CLIENT_PASS exactSamples="+next+" realRenderedFrames=true");
                    finished=true;
                    mc.stop();
                }
            }
        } catch (Throwable e) {
            finished=true;
            System.err.println("PERFOMANT_BOOM_E2E_CLIENT_FAIL "+e);
            e.printStackTrace(); mc.stop();
        }
    }
}
