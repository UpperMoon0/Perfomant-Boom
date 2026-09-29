package com.nstut.explosion;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.phys.Vec3;

/** Matches the inspected vanilla 1.21.1 explosion entity path. */
final class VanillaExplosionAdapter {
    private static final ExplosionDamageCalculator DAMAGE = new ExplosionDamageCalculator();
    static long chunkKey(int x, int z) { return net.minecraft.world.level.ChunkPos.asLong(x,z); }
    static Explosion create(ServerLevel level, Vec3 center, float power) {
        return new Explosion(level, null, center.x, center.y, center.z, power, false, Explosion.BlockInteraction.DESTROY);
    }
    static void shuffle(List<BlockPos> order, RandomSource random) { net.minecraft.Util.shuffle(order, random); }
    static void hurt(ServerLevel level, Explosion explosion, Entity entity) {
        if (entity.ignoreExplosion(explosion)) return;
        Vec3 center=explosion.center();
        double distance=Math.sqrt(entity.distanceToSqr(center))/(explosion.radius()*2.0F);
        if (distance > 1) return;
        double dx=entity.getX()-center.x;
        double dy=(entity instanceof PrimedTnt ? entity.getY() : entity.getEyeY())-center.y;
        double dz=entity.getZ()-center.z;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if (length==0) return;
        Vec3 direction=new Vec3(dx/length, dy/length, dz/length);
        if (DAMAGE.shouldDamageEntity(explosion, entity))
            entity.hurt(level.damageSources().explosion(explosion), DAMAGE.getEntityDamageAmount(explosion, entity));
        float exposure=Explosion.getSeenPercent(center, entity);
        double resistance=entity instanceof LivingEntity living
                ? living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE) : 0;
        Vec3 knockback=direction.scale((1-distance)*exposure*DAMAGE.getKnockbackMultiplier(entity)*(1-resistance));
        entity.setDeltaMovement(entity.getDeltaMovement().add(knockback));
        if (entity instanceof ServerPlayer player && player.distanceToSqr(center)<4096
                && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying))
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        entity.onExplosionHit(null);
    }
}
