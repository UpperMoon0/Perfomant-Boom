package com.nstut.explosion;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExplosionSchedulerKnockbackTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void sendsHitImpulseBeforeYieldEvenIfPlayerLaterLeavesRange() {
        checkYieldedExplosion(true, false);
    }

    @Test void doesNotRepeatHitEffectsForPlayerStillInRange() {
        checkYieldedExplosion(false, false);
    }

    @Test void freezesObserversAcrossRangeCrossingsAndLevelArrivals() {
        checkYieldedExplosion(false, true);
    }

    private void checkYieldedExplosion(boolean leavesRange, boolean observersMove) {
        var server = mock(MinecraftServer.class);
        var level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        var explosion = mock(Explosion.class);
        when(explosion.center()).thenReturn(Vec3.ZERO);
        when(explosion.radius()).thenReturn(4F);
        var player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(player.distanceToSqr(Vec3.ZERO)).thenReturn(4D);
        when(player.getX()).thenReturn(2D);
        when(player.getDeltaMovement()).thenReturn(new Vec3(0.5, 0, 0));
        var mob = mock(Entity.class);
        when(mob.ignoreExplosion(explosion)).thenReturn(true);
        var observer = mock(ServerPlayer.class);
        observer.connection = mock(ServerGamePacketListenerImpl.class);
        when(observer.distanceToSqr(Vec3.ZERO)).thenReturn(2500D);
        var outside = mock(ServerPlayer.class);
        outside.connection = mock(ServerGamePacketListenerImpl.class);
        when(outside.distanceToSqr(Vec3.ZERO)).thenReturn(4096D);
        var arrival = mock(ServerPlayer.class);
        arrival.connection = mock(ServerGamePacketListenerImpl.class);
        when(arrival.distanceToSqr(Vec3.ZERO)).thenReturn(100D);
        var levelPlayers = new java.util.ArrayList<>(List.of(player, observer, outside));
        when(level.players()).thenReturn(levelPlayers);
        when(level.getEntities(isNull(), any(AABB.class))).thenReturn(List.of(player, mob));
        var calculation = mock(FastExplosionEngine.IncrementalCalculation.class);
        when(calculation.processUntil(anyLong())).thenReturn(true);
        when(calculation.affectedBlocks()).thenReturn(Set.of());
        var completed = new java.util.concurrent.atomic.AtomicBoolean();

        try (var engine = mockStatic(FastExplosionEngine.class);
             var adapter = mockStatic(VanillaExplosionAdapter.class, CALLS_REAL_METHODS);
             var exposure = mockStatic(Explosion.class)) {
            engine.when(() -> FastExplosionEngine.create(level, Vec3.ZERO, 4F)).thenReturn(calculation);
            adapter.when(() -> VanillaExplosionAdapter.create(level, Vec3.ZERO, 4F)).thenReturn(explosion);
            exposure.when(() -> Explosion.getSeenPercent(Vec3.ZERO, player)).thenReturn(1F);
            ExplosionScheduler.scheduleTracked(level, Vec3.ZERO, 4F, metrics -> completed.set(true));

            // An expired deadline deterministically yields immediately after the first entity.
            ExplosionScheduler.tickUntil(server, Long.MIN_VALUE);
            verify(player).setDeltaMovement(new Vec3(1.25, 0, 0));
            var packets = ArgumentCaptor.forClass(ClientboundExplodePacket.class);
            verify(player.connection).send(packets.capture());
            assertEquals(0.75F, packets.getValue().getKnockbackX());
            assertTrue(packets.getValue().getToBlow().isEmpty());
            verify(mob, never()).ignoreExplosion(explosion);
            verifyNoInteractions(observer.connection);
            assertFalse(completed.get());

            // Moving out of range while the task is suspended cannot lose the earlier impulse.
            when(player.distanceToSqr(Vec3.ZERO)).thenReturn(leavesRange ? 10000D : 4D);
            if (observersMove) {
                when(observer.distanceToSqr(Vec3.ZERO)).thenReturn(10000D);
                when(outside.distanceToSqr(Vec3.ZERO)).thenReturn(100D);
                levelPlayers.add(arrival);
            }
            ExplosionScheduler.tickUntil(server, Long.MIN_VALUE);
            verify(mob).ignoreExplosion(explosion);
            assertFalse(completed.get());
            ExplosionScheduler.tickUntil(server, Long.MAX_VALUE);
            assertTrue(completed.get());
            verifyNoMoreInteractions(player.connection);
            verify(observer.connection).send(packets.capture());
            assertEquals(0F, packets.getValue().getKnockbackX());
            assertEquals(0F, packets.getValue().getKnockbackY());
            assertEquals(0F, packets.getValue().getKnockbackZ());
            verifyNoMoreInteractions(observer.connection);
            verifyNoInteractions(outside.connection, arrival.connection);
        }
    }
}
