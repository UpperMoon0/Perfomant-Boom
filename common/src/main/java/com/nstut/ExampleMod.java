package com.nstut.perfomantboom;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.nstut.explosion.ExplosionScheduler;
import com.nstut.explosion.BoomLifecycleIntegrationTest;
import com.nstut.testing.BoomServerIntegrationTest;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExampleMod {
    public static final String MOD_ID = "perfomant_boom";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ExampleMod() {
    }

    public static void init() {
    }

    public static void onServerTickStart(MinecraftServer server) {
        if (!BoomLifecycleIntegrationTest.isArmed()) BoomServerIntegrationTest.onServerTickStart(server);
    }

    public static void onServerTick(MinecraftServer server) {
        if (BoomLifecycleIntegrationTest.isArmed()) {
            BoomLifecycleIntegrationTest.tick(server);
            return;
        }
        ExplosionScheduler.tick(server);
        BoomServerIntegrationTest.tick(server);
    }

    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        BoomServerIntegrationTest.registerCommands(dispatcher);

        dispatcher.register(Commands.literal("boom")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("radius", FloatArgumentType.floatArg(1.0f, 500.0f))
                .executes(context -> {
                    float radius = FloatArgumentType.getFloat(context, "radius");
                    ServerLevel level = context.getSource().getLevel();
                    Vec3 pos = context.getSource().getPosition();

                    ExplosionScheduler.schedule(level, pos, radius);
                    context.getSource().sendSuccess(
                        () -> Component.literal(
                            "Queued explosion at "
                                + String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z)
                                + " with power " + radius
                                + ". Work will be spread across server ticks."
                        ),
                        false
                    );
                    LOGGER.info("Queued explosion at {} (power {})", pos, radius);
                    return 1;
                })
            )
        );
    }
}
