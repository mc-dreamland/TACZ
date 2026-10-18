package com.tacz.guns.client.particle;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayDeque;
import java.util.Deque;

/** Client-only particles shared by tracked bullets and untracked Paper replicas. */
@OnlyIn(Dist.CLIENT)
public final class ClientBulletTrail {
    private static final TrailParticleBudget BUDGET = new TrailParticleBudget(512);
    private final ClientTrailProgress progress = new ClientTrailProgress();
    private final Deque<Emitted> particles = new ArrayDeque<>();

    private record Emitted(Particle particle, Vec3 point) { }

    public void emitTo(EntityKineticBullet bullet, Vec3 end, boolean terminal) {
        if (!bullet.level().isClientSide || bullet.isRemoved() || !ElectricArcPath.finite(end)) return;
        particles.removeIf(entry -> !entry.particle().isAlive());
        if (terminal) {
            particles.removeIf(entry -> {
                if (!ClientTrailProgress.beyond(entry.point(), end, bullet.getDeltaMovement())) return false;
                entry.particle().remove();
                return true;
            });
        }
        var display = TimelessAPI.getGunDisplay(bullet.getGunDisplayId(), bullet.getGunId()).orElse(null);
        if (display == null) return;
        var arc = display.getElectricArc();
        var ammoParticle = display.getParticle();
        if (ammoParticle == null) ammoParticle = TimelessAPI.getClientAmmoIndex(bullet.getAmmoId())
                .map(index -> index.getParticle()).orElse(null);
        if (arc == null && ammoParticle == null) return;

        var segment = progress.advance(bullet.position(), end, bullet.getDeltaMovement(),
                (start, requested) -> clip(bullet, start, requested));
        if (segment == null) return;
        Entity camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null || camera.position().distanceToSqr(segment.start()) > 64 * 64) return;
        int available = BUDGET.remaining(bullet.level(), bullet.level().getGameTime());
        if (available == 0) return;

        if (arc != null) {
            for (Vec3 point : ElectricArcPath.points(segment.start(), segment.end(), arc,
                    bullet.getRandom()::nextDouble, available)) {
                if (!accept(bullet, segment, point)) continue;
                Particle particle = Minecraft.getInstance().particleEngine.createParticle(ParticleTypes.ELECTRIC_SPARK,
                        point.x, point.y, point.z, 0, 0, 0);
                if (particle != null) {
                    particle.setLifetime(arc.lifeTime());
                    remember(particle, point);
                }
            }
        }
        if (ammoParticle != null) {
            AmmoParticleSpawner.addParticle(bullet, ammoParticle, segment.start(), segment.end(),
                    point -> accept(bullet, segment, point), this::remember);
        }
    }

    private boolean accept(EntityKineticBullet bullet, ClientTrailProgress.Segment segment, Vec3 point) {
        if (!ElectricArcPath.finite(point)) return false;
        if (!BUDGET.claim(bullet.level(), bullet.level().getGameTime())) return false;
        // A zigzag or Gaussian flame offset must not place a particle behind a nearby wall.
        var hit = bullet.level().clip(new ClipContext(segment.start(), point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bullet));
        if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(point) > 1.0e-8) return false;
        return true;
    }

    private void remember(Particle particle, Vec3 point) {
        particles.addLast(new Emitted(particle, point));
        // Bound retained handles as well as emissions, even for extreme external display settings.
        while (particles.size() > 512) particles.removeFirst().particle().remove();
    }

    private static Vec3 clip(EntityKineticBullet bullet, Vec3 start, Vec3 requested) {
        var hit = bullet.level().clip(new ClipContext(start, requested,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bullet));
        Vec3 end = hit.getType() == HitResult.Type.MISS ? requested : hit.getLocation();
        // This is cosmetic stopping only. It never posts hit events or changes bullet movement/damage.
        Entity owner = bullet.getOwner();
        for (Entity entity : bullet.level().getEntities(bullet, new AABB(start, end).inflate(.05),
                entity -> entity.isAlive() && entity.isPickable() && !entity.isSpectator())) {
            if (entity == owner || owner != null && entity.isPassengerOfSameVehicle(owner)) continue;
            Vec3 point = entity.getBoundingBox().clip(start, end).orElse(null);
            if (point != null && start.distanceToSqr(point) < start.distanceToSqr(end)) end = point;
        }
        return end;
    }
}
