package com.nstut.perfomantboom.neoforge;
import com.nstut.perfomantboom.ExampleMod;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
@Mod(ExampleMod.MOD_ID)
public final class PerfomantBoomNeoForge {
    public PerfomantBoomNeoForge() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::commands);
    }
    private void tick(ServerTickEvent.Post event) { ExampleMod.onServerTick(event.getServer()); }
    private void commands(RegisterCommandsEvent event) { ExampleMod.registerCommands(event.getDispatcher()); }
}
