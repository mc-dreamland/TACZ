package com.tacz.guns.util;

import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.util.block.ProjectileExplosion;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Optional;

public class ExplodeUtil {
    private static final WeightedList<ExplosionParticleInfo> BLOCK_PARTICLES = WeightedList.<ExplosionParticleInfo>builder()
            .add(new ExplosionParticleInfo(ParticleTypes.POOF, 0.5F, 1.0F))
            .add(new ExplosionParticleInfo(ParticleTypes.SMOKE, 1.0F, 1.0F))
            .build();

    public static void createExplosion(Entity owner, Entity exploder, float damage, float radius, boolean knockback, boolean destroy, Vec3 hitPos) {
        if (!(exploder.level() instanceof ServerLevel level) || radius <= 0) {
            return;
        }
        Explosion.BlockInteraction mode = destroy ? Explosion.BlockInteraction.DESTROY : Explosion.BlockInteraction.KEEP;
        ProjectileExplosion explosion = new ProjectileExplosion(level, owner, exploder,
                exploder.damageSources().explosion(exploder, owner), null,
                hitPos.x(), hitPos.y(), hitPos.z(), damage, radius, knockback, mode);
        if (EventHooks.onExplosionStart(level, explosion)) {
            return;
        }
        int affectedBlocks = explosion.explode();
        double visibleDistance = AmmoConfig.EXPLOSIVE_AMMO_VISIBLE_DISTANCE.get();
        level.players().stream().filter(player -> player.distanceToSqr(hitPos) < visibleDistance * visibleDistance).forEach(player ->
                player.connection.send(new ClientboundExplodePacket(hitPos, radius, affectedBlocks,
                        Optional.ofNullable(explosion.getHitPlayers().get(player)),
                        explosion.isSmall() ? ParticleTypes.EXPLOSION : ParticleTypes.EXPLOSION_EMITTER,
                        SoundEvents.GENERIC_EXPLODE, BLOCK_PARTICLES)));
    }
}
