package com.nstut.perfomantboom.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.nstut.perfomantboom.PerfomantBoom;

public final class PerfomantBoomFabric implements ModInitializer {
    @Override
    public void onInitialize() {

        // Register server tick events so live verification can measure whole-tick latency.
        ServerTickEvents.START_SERVER_TICK.register(PerfomantBoom::onServerTickStart);
        ServerTickEvents.END_SERVER_TICK.register(PerfomantBoom::onServerTick);

        // Register commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PerfomantBoom.registerCommands(dispatcher);
        });
    }
}
