package com.nstut.perfomantboom.neoforge;
import com.nstut.perfomantboom.PerfomantBoom;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
@Mod(PerfomantBoom.MOD_ID)
public final class PerfomantBoomNeoForge {
    public PerfomantBoomNeoForge() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::commands);
    }
    private void tick(ServerTickEvent.Post event) { PerfomantBoom.onServerTick(event.getServer()); }
    private void commands(RegisterCommandsEvent event) { PerfomantBoom.registerCommands(event.getDispatcher()); }
}
