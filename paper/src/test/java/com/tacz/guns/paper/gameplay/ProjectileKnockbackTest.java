package com.tacz.guns.paper.gameplay;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProjectileKnockbackTest {
    @ParameterizedTest
    @CsvSource({"0.875, 3, -4, 5", "0.15, -2, 7, 9", "0.075, 0, -1, 0"})
    void nativeKnockbackReceivesTheConfiguredStrengthAndHorizontalSourceDirection(
            double strength, double dx, double dy, double dz) {
        LivingEntity target = mock(LivingEntity.class);
        Vector before = new Vector(.2, -.1, .3);
        Vector direction = new Vector(dx, dy, dz);

        PaperBallistics.applyProjectileKnockback(target, before, strength, direction);

        // Restore pre-hit motion before invoking Minecraft's damping/resistance-aware routine.
        // No extra setVelocity may overwrite that routine or bypass a cancelled knockback event.
        var calls = inOrder(target);
        calls.verify(target).setVelocity(new Vector(.2, -.1, .3));
        calls.verify(target).knockback(strength, -dx, -dz);
        calls.verifyNoMoreInteractions();
        assertEquals(new Vector(.2, -.1, .3), before);
        assertEquals(new Vector(dx, dy, dz), direction);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -.1})
    void zeroStrengthGunsRemoveGenericHurtKnockbackAndPreserveExistingMovement(double strength) {
        LivingEntity target = mock(LivingEntity.class);
        Vector before = new Vector(.2, .3, -.1);

        PaperBallistics.applyProjectileKnockback(target, before, strength, new Vector(1, -1, 2));

        verify(target).setVelocity(before);
        verifyNoMoreInteractions(target);
    }
}
