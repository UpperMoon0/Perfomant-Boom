package com.nstut.mixin;

import com.nstut.explosion.util.LightFlushable;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.spongepowered.asm.mixin.Final;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin implements LightFlushable {
    @Shadow @Final private ObjectList<?> lightTasks;
    
    @Shadow
    protected abstract void runUpdate();

    /**
     * Custom method to flush all pending light tasks synchronously.
     */
    public void perfomant_boom$flushAll() {
        while (!lightTasks.isEmpty()) {
            runUpdate();
        }
    }
}
