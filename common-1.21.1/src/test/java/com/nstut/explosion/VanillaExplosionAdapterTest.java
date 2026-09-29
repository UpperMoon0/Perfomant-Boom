package com.nstut.explosion;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VanillaExplosionAdapterTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void sendsImpulseOnceToMovingPlayerAndEffectsToSelectedObserver() {
        var level = mock(ServerLevel.class);
        var moving = player(4);
        var observer = player(100);
        when(moving.level()).thenReturn(level);
        when(observer.level()).thenReturn(level);
        Vec3 velocity = new Vec3(0.75, 0.1, -0.5);
        when(moving.getDeltaMovement()).thenReturn(velocity);
        Vec3 impulse = new Vec3(0.25, 0.5, -0.125);

        moving.connection.send(VanillaExplosionAdapter.clientPacket(Vec3.ZERO, 4, impulse));
        VanillaExplosionAdapter.sendEffects(level, List.of(moving, observer), Vec3.ZERO, 4, java.util.Set.of(moving));

        var packet = ArgumentCaptor.forClass(ClientboundExplodePacket.class);
        verify(moving.connection, times(1)).send(packet.capture());
        var actual = packet.getValue();
        Vec3 received = new Vec3(actual.getKnockbackX(), actual.getKnockbackY(), actual.getKnockbackZ());
        assertEquals(impulse, received);
        // Vanilla's client adds the packet impulse to its independently predicted velocity.
        assertEquals(new Vec3(1, 0.6, -0.625), velocity.add(received));
        assertTrue(actual.getToBlow().isEmpty(), "client must not replay queued terrain destruction");
        verify(observer.connection, times(1)).send(packet.capture());
        assertEquals(0, packet.getValue().getKnockbackX());
        assertEquals(0, packet.getValue().getKnockbackY());
        assertEquals(0, packet.getValue().getKnockbackZ());
        verifyNoMoreInteractions(moving.connection, observer.connection);
    }

    private static ServerPlayer player(double distanceSquared) {
        var player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(player.distanceToSqr(Vec3.ZERO)).thenReturn(distanceSquared);
        return player;
    }

    @Test void damagePathRecordsImpulseRatherThanResultingServerVelocity() {
        var level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        var explosion = mock(net.minecraft.world.level.Explosion.class);
        when(explosion.center()).thenReturn(Vec3.ZERO);
        when(explosion.radius()).thenReturn(4F);
        var player = player(4);
        when(player.getX()).thenReturn(2D);
        when(player.getDeltaMovement()).thenReturn(new Vec3(0.5, 0, 0));
        var hits = new java.util.HashMap<ServerPlayer, Vec3>();
        try (var exposure = mockStatic(net.minecraft.world.level.Explosion.class)) {
            exposure.when(() -> net.minecraft.world.level.Explosion.getSeenPercent(Vec3.ZERO, player)).thenReturn(1F);
            VanillaExplosionAdapter.hurt(level, explosion, player, hits);
            assertEquals(new Vec3(0.75, 0, 0), hits.get(player));
            verify(player).setDeltaMovement(new Vec3(1.25, 0, 0));
            hits.clear();
            when(player.isSpectator()).thenReturn(true);
            VanillaExplosionAdapter.hurt(level, explosion, player, hits);
            assertTrue(hits.isEmpty(), "spectators must not receive a client impulse");
            when(player.isSpectator()).thenReturn(false);
            when(player.isCreative()).thenReturn(true);
            var abilities = new net.minecraft.world.entity.player.Abilities();
            abilities.flying = true;
            when(player.getAbilities()).thenReturn(abilities);
            VanillaExplosionAdapter.hurt(level, explosion, player, hits);
            assertTrue(hits.isEmpty(), "flying creative players must not receive a client impulse");
        }
        verifyNoInteractions(player.connection); // The scheduler sends the recorded impulse before yielding.
    }
}
