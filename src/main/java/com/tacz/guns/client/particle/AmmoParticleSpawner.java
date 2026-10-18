package com.tacz.guns.client.particle;


import com.tacz.guns.client.resource.pojo.display.ammo.AmmoParticle;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

@OnlyIn(Dist.CLIENT)
public class AmmoParticleSpawner {
    public static void addParticle(EntityKineticBullet bullet) {
        bullet.emitClientTrailTo(bullet.position().add(bullet.getDeltaMovement()));
    }

    public static void addParticle(EntityKineticBullet bullet, AmmoParticle particle, Vec3 start, Vec3 end,
                                   Predicate<Vec3> accept, BiConsumer<Particle, Vec3> emitted) {
        ParticleOptions particleOptions = particle.getParticleOptions();
        if (particleOptions == null) {
            return;
        }
        int count = Math.max(0, Math.min(128, particle.getCount()));
        Vector3f delta = particle.getDelta();
        float particleSpeed = particle.getSpeed();
        ParticleEngine particleEngine = Minecraft.getInstance().particleEngine;
        if (count == 0) {
            double xSpeed = particleSpeed * delta.x();
            double ySpeed = particleSpeed * delta.y();
            double zSpeed = particleSpeed * delta.z();
            if (!accept.test(start)) return;
            Particle result = particleEngine.createParticle(particleOptions, start.x, start.y, start.z, xSpeed, ySpeed, zSpeed);
            if (result != null) {
                result.setLifetime(Math.max(1, Math.min(100, particle.getLifeTime())));
                emitted.accept(result, start);
            }
        } else {
            RandomSource random = bullet.getRandom();
            Entity owner = bullet.getOwner();
            for (int i = 0; i < count; ++i) {
                createParticle(particle, random, delta, particleSpeed, owner, particleEngine, particleOptions,
                        start, end, accept, emitted);
            }
        }
    }

    private static void createParticle(AmmoParticle particle, RandomSource random, Vector3f delta, float particleSpeed, Entity owner,
                                       ParticleEngine particleEngine, ParticleOptions particleOptions, Vec3 start, Vec3 end,
                                       Predicate<Vec3> accept, BiConsumer<Particle, Vec3> emitted) {
        Vec3 deltaMovement = end.subtract(start);
        double deltaMovementRandom = random.nextDouble();
        double offsetX = random.nextGaussian() * delta.x() + deltaMovementRandom * deltaMovement.x;
        double offsetY = random.nextGaussian() * delta.y() + deltaMovementRandom * deltaMovement.y;
        double offsetZ = random.nextGaussian() * delta.z() + deltaMovementRandom * deltaMovement.z;
        double xSpeed = random.nextGaussian() * particleSpeed;
        double ySpeed = random.nextGaussian() * particleSpeed;
        double zSpeed = random.nextGaussian() * particleSpeed;

        Vec3 point = start.add(offsetX, offsetY, offsetZ);
        double length = deltaMovement.length();
        if (length > 1.0e-8) {
            Vec3 direction = deltaMovement.scale(1 / length);
            double projection = point.subtract(start).dot(direction);
            point = point.add(direction.scale(Math.max(0, Math.min(length, projection)) - projection));
        }

        // 如果太贴近发射者，不进行粒子生成
        double minDistance = particle.getMinDistance();
        if ((owner == null || owner.distanceToSqr(point.x, point.y, point.z) > minDistance * minDistance) && accept.test(point)) {
            Particle result = particleEngine.createParticle(particleOptions, point.x, point.y, point.z, xSpeed, ySpeed, zSpeed);
            if (result != null) {
                result.setLifetime(Math.max(1, Math.min(100, particle.getLifeTime())));
                emitted.accept(result, point);
            }
        }
    }
}
