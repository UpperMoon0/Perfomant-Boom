package com.nstut.fabric.client;

import com.nstut.testing.BoomClientIntegrationTest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ExampleModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> BoomClientIntegrationTest.tick());
    }
}
