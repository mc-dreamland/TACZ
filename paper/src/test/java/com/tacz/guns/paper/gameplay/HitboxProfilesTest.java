package com.tacz.guns.paper.gameplay;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HitboxProfilesTest {
    @Test void emptyConfigurationSelectsPlayerHumanoidGenericAndBodyDefaults() {
        HitboxProfiles profiles = HitboxProfiles.load(new MemoryConfiguration());
        assertEquals(HitZones.Profile.DEFAULT_HUMANOID, profiles.forEntity(mock(Player.class)));
        assertEquals(HitZones.Profile.DEFAULT_HUMANOID, profiles.forEntity(entity(EntityType.ZOMBIE)));
        assertEquals(HitZones.Profile.DEFAULT_GENERIC, profiles.forEntity(entity(EntityType.COW)));
        assertEquals(HitZones.Shape.BODY, profiles.forEntity(entity(EntityType.SLIME)).shape());
        assertEquals(0, profiles.forEntity(entity(EntityType.SLIME)).legsHeight());
    }

    @Test void globalProfileParametersAreInheritedBeforePerEntityOverridesForEitherIdSpelling() {
        for (String playerId : List.of("player", "minecraft:player")) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set("hitboxes.profiles.humanoid.head-width", .6);
            config.set("hitboxes.profiles.humanoid.head-height", .2);
            config.set("hitboxes.profiles.humanoid.legs-height", .5);
            config.set("hitboxes.entities." + playerId + ".head-width", .9);
            config.set("hitboxes.entities.minecraft:cow.profile", "humanoid");
            config.set("hitboxes.entities.minecraft:cow.legs-height", .4);
            config.set("hitboxes.entities.minecraft:slime.profile", "generic");
            HitboxProfiles profiles = HitboxProfiles.load(config);
            assertEquals(new HitZones.Profile(HitZones.Shape.HUMANOID, .9, .2, .5), profiles.forEntity(mock(Player.class)));
            assertEquals(new HitZones.Profile(HitZones.Shape.HUMANOID, .6, .2, .5), profiles.forEntity(entity(EntityType.ZOMBIE)));
            assertEquals(new HitZones.Profile(HitZones.Shape.HUMANOID, .6, .2, .4), profiles.forEntity(entity(EntityType.COW)));
            assertEquals(HitZones.Profile.DEFAULT_GENERIC, profiles.forEntity(entity(EntityType.SLIME)));
            // Mutating the source configuration cannot partially change an active immutable catalog.
            config.set("hitboxes.profiles.humanoid.head-width", .1);
            assertEquals(.6, profiles.forEntity(entity(EntityType.ZOMBIE)).headWidth());
        }
    }

    @Test void unknownEntitiesNonlivingEntitiesAndUnknownProfilesAreRejectedWithTheirPath() {
        for (String key : List.of("minecraft:does_not_exist", "custom:player", "minecraft:item")) {
            MemoryConfiguration config = new MemoryConfiguration();
            String path = "hitboxes.entities." + key;
            config.set(path + ".profile", "body");
            rejected(config, path);
        }
        MemoryConfiguration global = new MemoryConfiguration();
        global.set("hitboxes.profiles.guessed.head-width", .5);
        rejected(global, "hitboxes.profiles.guessed");
        MemoryConfiguration perEntity = new MemoryConfiguration();
        perEntity.set("hitboxes.entities.player.profile", "guessed");
        rejected(perEntity, "hitboxes.entities.player.profile");
        MemoryConfiguration fallback = new MemoryConfiguration();
        fallback.set("hitboxes.default-profile", "guessed");
        rejected(fallback, "hitboxes.default-profile");
    }

    @Test void configurationDoesNotCoerceNumericStringsBooleansOrNonFiniteValues() {
        String path = "hitboxes.profiles.humanoid.head-width";
        for (Object value : List.of("0.5", true, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.01, -.2)) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set(path, value);
            rejected(config, path);
        }
    }

    @Test void headAndLegRangesCannotConsumeTheHumanoidTorsoIncludingThroughOverrides() {
        MemoryConfiguration global = new MemoryConfiguration();
        global.set("hitboxes.profiles.humanoid.head-height", .4);
        global.set("hitboxes.profiles.humanoid.legs-height", .6);
        rejected(global, "hitboxes.profiles.humanoid");

        MemoryConfiguration override = new MemoryConfiguration();
        override.set("hitboxes.profiles.humanoid.head-height", .4);
        override.set("hitboxes.profiles.humanoid.legs-height", .4);
        override.set("hitboxes.entities.player.legs-height", .6);
        rejected(override, "hitboxes.entities.player");

        MemoryConfiguration noLegs = new MemoryConfiguration();
        noLegs.set("hitboxes.entities.player.legs-height", 0);
        rejected(noLegs, "hitboxes.entities.player");
    }

    private static LivingEntity entity(EntityType type) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getType()).thenReturn(type);
        return entity;
    }

    private static void rejected(MemoryConfiguration config, String path) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> HitboxProfiles.load(config));
        assertTrue(error.getMessage().contains(path), error.getMessage());
    }
}
