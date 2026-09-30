package com.nstut.perfomantboom.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.nstut.perfomantboom.PerfomantBoom;
import com.nstut.testing.BoomClientIntegrationTest;
import com.nstut.perfomantboom.forge.gametest.BoomForgeGameTests;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

@Mod(PerfomantBoom.MOD_ID)
public final class PerfomantBoomForge {
    public PerfomantBoomForge() {
        // Register the Forge event bus before wiring Boom lifecycle handlers.
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(PerfomantBoom.MOD_ID, modEventBus);
        modEventBus.addListener(this::registerGameTests);

        // Wire explosion scheduling, commands and client verification.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientHooks::register);
    }

    private void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.START) {
            PerfomantBoom.onServerTickStart(event.getServer());
        } else {
            PerfomantBoom.onServerTick(event.getServer());
        }
    }


    private static final class ClientHooks {
        private static void register() {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ClientHooks::onRender);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ClientHooks::onClientTick);
        }

        private static void onRender(net.minecraftforge.client.event.RenderLevelStageEvent event) {
            if (event.getStage() == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                BoomClientIntegrationTest.onRenderedFrame();
            }
        }

        private static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
                BoomClientIntegrationTest.tick();
            }
        }
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        event.register(BoomForgeGameTests.class);
    }

    private void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        PerfomantBoom.registerCommands(event.getDispatcher());
    }
}
