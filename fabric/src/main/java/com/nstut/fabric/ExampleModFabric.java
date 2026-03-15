package com.nstut.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.nstut.ExampleMod;

public final class ExampleModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Run our common setup.
        ExampleMod.init();

        // Register server tick event - Fabric passes MinecraftServer
        ServerTickEvents.END_SERVER_TICK.register(ExampleMod::onServerTick);

        // Register commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ExampleMod.registerCommands(dispatcher);
        });
    }
}
