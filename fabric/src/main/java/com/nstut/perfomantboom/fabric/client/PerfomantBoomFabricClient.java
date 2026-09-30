package com.nstut.perfomantboom.fabric.client;

import com.nstut.testing.BoomClientIntegrationTest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class PerfomantBoomFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register(context -> BoomClientIntegrationTest.onRenderedFrame());
        ClientTickEvents.END_CLIENT_TICK.register(client -> BoomClientIntegrationTest.tick());
    }
}
