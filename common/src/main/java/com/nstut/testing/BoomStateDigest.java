package com.nstut.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Test-only out-of-band oracle. The client hashes its real network-populated world;
 * it NEVER applies this manifest to its world. Relative positions make paired craters comparable.
 */
public final class BoomStateDigest {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public record Snapshot(String blocks, String light, int air, int blockEntities, int maxBlockLight) {}
    private BoomStateDigest() {}
    public static Path evidence() {
        String value = System.getenv("PERFOMANT_BOOM_EVIDENCE");
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing isolated evidence directory");
        return Path.of(value);
    }
    public static String token() { return System.getenv("PERFOMANT_BOOM_TEST_RUN"); }
    public static Snapshot snapshot(Level level, BlockPos center, int radius) {
        MessageDigest blocks = digest(), light = digest();
        int air = 0, blockEntities = 0, max = 0;
        var pos = new BlockPos.MutableBlockPos();
        for (int x=-radius; x<=radius; x++) for (int y=-radius; y<=radius; y++) for (int z=-radius; z<=radius; z++) {
            pos.set(center.getX()+x, center.getY()+y, center.getZ()+z);
            var state = level.getBlockState(pos);
            int id = Block.getId(state);
            blocks.update((byte)(id >>> 24)); blocks.update((byte)(id >>> 16));
            blocks.update((byte)(id >>> 8)); blocks.update((byte)id);
            if (state.isAir()) air++;
            if (level.getBlockEntity(pos) != null) blockEntities++;
            int value = level.getBrightness(LightLayer.BLOCK, pos);
            light.update((byte)value);
            light.update((byte)level.getBrightness(LightLayer.SKY, pos));
            max = Math.max(max, value);
        }
        return new Snapshot(HexFormat.of().formatHex(blocks.digest()), HexFormat.of().formatHex(light.digest()), air, blockEntities, max);
    }
    public static void requireShell(Level level, BlockPos center, int radius) {
        var p = new BlockPos.MutableBlockPos();
        for (int x=-radius; x<=radius; x++) for (int y=-radius; y<=radius; y++) for (int z=-radius; z<=radius; z++) {
            if (Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z)) != radius) continue;
            p.set(center.getX()+x,center.getY()+y,center.getZ()+z);
            if (!level.getBlockState(p).is(Blocks.BEDROCK)) throw new IllegalStateException("Broken bedrock shell at "+p);
        }
    }
    public static void write(String filename, Object value) {
        try {
            Path target=evidence().resolve(filename), temp=evidence().resolve(filename+".tmp");
            Files.writeString(temp,JSON.toJson(value)+"\n");
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot persist benchmark evidence",e); }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
