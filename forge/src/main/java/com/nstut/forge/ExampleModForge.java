package com.nstut.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.nstut.ExampleMod;

@Mod(ExampleMod.MOD_ID)
public final class ExampleModForge {
    public ExampleModForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(ExampleMod.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        // Run our common setup.
        ExampleMod.init();

        // Register events on the Forge bus
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
            ExampleMod.onServerTick(event.getServer().overworld().getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD));
        }
    }

    private void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        ExampleMod.registerCommands(event.getDispatcher());
    }
}
