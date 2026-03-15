package com.nstut;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.nstut.explosion.ExplosionScheduler;
import com.nstut.explosion.FastExplosionEngine;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExampleMod {
    public static final String MOD_ID = "perfomant_boom";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
    }

    public static void onServerTick(MinecraftServer server) {
        ExplosionScheduler.tick();
    }

    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("boom")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0f, 500.0f))
                .executes(context -> {
                    float radius = FloatArgumentType.getFloat(context, "radius");
                    ServerLevel level = context.getSource().getLevel();
                    Vec3 pos = context.getSource().getPosition();

                    context.getSource().sendSuccess(() -> Component.literal("Calculating explosion of radius " + radius + "..."), false);

                    // PRE-FETCH
                    Long2ObjectOpenHashMap<ChunkAccess> chunkMap = new Long2ObjectOpenHashMap<>();
                    int chunkRadius = ((int) Math.ceil(radius) >> 4) + 1;
                    int centerCX = ((int) Math.floor(pos.x)) >> 4;
                    int centerCZ = ((int) Math.floor(pos.z)) >> 4;

                    for (int x = -chunkRadius; x <= chunkRadius; x++) {
                        for (int z = -chunkRadius; z <= chunkRadius; z++) {
                            int cx = centerCX + x;
                            int cz = centerCZ + z;
                            chunkMap.put(ChunkPos.asLong(cx, cz), level.getChunk(cx, cz));
                        }
                    }

                    FastExplosionEngine.FastWorldView worldView = new FastExplosionEngine.FastWorldView(chunkMap, level);

                    FastExplosionEngine.calculateResistantExplosionAsync(worldView, pos, radius)
                        .thenAccept(blocks -> {
                            level.getServer().execute(() -> {
                                ExplosionScheduler.scheduleDestruction(level, pos, blocks);
                                context.getSource().sendSuccess(() -> Component.literal("Explosion of " + blocks.size() + " blocks scheduled."), false);
                                LOGGER.info("Explosion of {} blocks scheduled at {} (radius {})", blocks.size(), pos, radius);
                            });
                        });

                    return 1;
                })
            )
        );
    }
}
