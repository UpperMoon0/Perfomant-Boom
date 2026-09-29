package com.nstut.explosion;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VanillaExplosionAdapterTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void nativePacketCarriesOnlyAdditiveImpulseAndNoTerrainReplay() {
        Vec3 velocity = new Vec3(0.75, 0.1, -0.5);
        Vec3 impulse = new Vec3(0.25, 0.5, -0.125);
        var packet = VanillaExplosionAdapter.clientPacket(Vec3.ZERO, 4, impulse);
        assertEquals(impulse, packet.playerKnockback().orElseThrow());
        // ClientPacketListener.handleExplosion calls addDeltaMovement with this value.
        assertEquals(new Vec3(1, 0.6, -0.625), velocity.add(packet.playerKnockback().orElseThrow()));
        assertEquals(0, packet.blockCount());
        assertSame(ParticleTypes.EXPLOSION_EMITTER, packet.explosionParticle());
    }

    @Test void unaffectedObserverStillReceivesEffectsWithoutKnockback() {
        var packet = VanillaExplosionAdapter.clientPacket(Vec3.ZERO, 1, null);
        assertTrue(packet.playerKnockback().isEmpty());
        assertSame(ParticleTypes.EXPLOSION, packet.explosionParticle());
    }
}
