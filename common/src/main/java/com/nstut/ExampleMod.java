package com.nstut;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.nstut.explosion.ExplosionScheduler;
import com.nstut.explosion.FastExplosionEngine;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class ExampleMod {
    public static final String MOD_ID = "perfomant_boom";

    public static void init() {
        // Since we are in "common", we rely on the platform-specific loaders 
        // to call this init. For Architectury, we can use their API if available,
        // or just provide these hooks for the fabric/forge modules.
    }

    /**
     * Call this from ServerTickEvents.END_SERVER_TICK or similar.
     */
    public static void onServerTick(ServerLevel level) {
        ExplosionScheduler.tick();
    }

    /**
     * Call this from CommandRegistrationEvent.
     */
    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("boom")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0f, 500.0f))
                .executes(context -> {
                    float radius = FloatArgumentType.getFloat(context, "radius");
                    ServerLevel level = context.getSource().getLevel();
                    Vec3 pos = context.getSource().getPosition();

                    context.getSource().sendSuccess(() -> Component.literal("Calculating explosion of radius " + radius + "..."), false);

                    FastExplosionEngine.calculateResistantExplosionAsync(level, pos, radius)
                        .thenAccept(blocks -> {
                            level.getServer().execute(() -> {
                                ExplosionScheduler.scheduleDestruction(level, pos, blocks);
                                context.getSource().sendSuccess(() -> Component.literal("Explosion of " + blocks.size() + " blocks scheduled."), false);
                            });
                        });

                    return 1;
                })
            )
        );
    }
}
