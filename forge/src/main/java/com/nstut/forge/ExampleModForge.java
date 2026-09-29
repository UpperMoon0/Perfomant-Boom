package com.nstut.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.nstut.ExampleMod;
import com.nstut.testing.BoomClientIntegrationTest;
import com.nstut.forge.gametest.BoomForgeGameTests;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

@Mod(ExampleMod.MOD_ID)
public final class ExampleModForge {
    public ExampleModForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(ExampleMod.MOD_ID, modEventBus);
        modEventBus.addListener(this::registerGameTests);

        // Run our common setup.
        ExampleMod.init();

        // Register events on the Forge bus
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientHooks::register);
    }

    private void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.START) {
            ExampleMod.onServerTickStart(event.getServer());
        } else {
            ExampleMod.onServerTick(event.getServer());
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
        ExampleMod.registerCommands(event.getDispatcher());
    }
}
