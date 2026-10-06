package com.tacz.guns.paper.gameplay;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable, validated server hit regions; model animations are deliberately not authoritative. */
public final class HitboxProfiles {
    private static final Set<String> HUMANOIDS = Set.of("player", "zombie", "husk", "drowned", "zombie_villager",
            "zombified_piglin", "piglin", "piglin_brute", "skeleton", "stray", "bogged", "wither_skeleton",
            "villager", "wandering_trader", "witch", "pillager", "vindicator", "evoker", "illusioner", "enderman", "armor_stand");
    private static final Set<String> BODY_ONLY = Set.of("slime", "magma_cube", "spider", "cave_spider", "shulker",
            "squid", "glow_squid", "ghast", "allay", "vex");
    private static final Set<String> FIELDS = Set.of("profile", "head-width", "head-height", "legs-height");
    private final HitZones.Profile fallback;
    private final Map<String, HitZones.Profile> entities;

    private HitboxProfiles(HitZones.Profile fallback, Map<String, HitZones.Profile> entities) {
        this.fallback = fallback;
        this.entities = Map.copyOf(entities);
    }

    public static HitboxProfiles load(ConfigurationSection config) {
        Map<HitZones.Shape, HitZones.Profile> profiles = new HashMap<>();
        profiles.put(HitZones.Shape.HUMANOID, HitZones.Profile.DEFAULT_HUMANOID);
        profiles.put(HitZones.Shape.GENERIC, HitZones.Profile.DEFAULT_GENERIC);
        profiles.put(HitZones.Shape.BODY, new HitZones.Profile(HitZones.Shape.BODY, .85, .25, 0));
        ConfigurationSection root = section(config, "hitboxes");
        ConfigurationSection configured = section(root, "profiles");
        if (configured != null) for (String name : configured.getKeys(false)) {
            HitZones.Shape shape = shape(name, "hitboxes.profiles." + name);
            profiles.put(shape, read(requiredSection(configured, name), profiles.get(shape), false));
        }
        HitZones.Shape defaultShape = root != null && root.contains("default-profile")
                ? shape(root.getString("default-profile", ""), "hitboxes.default-profile") : HitZones.Shape.GENERIC;
        Map<String, HitZones.Profile> entities = new HashMap<>();
        for (String name : HUMANOIDS) entities.put("minecraft:" + name, profiles.get(HitZones.Shape.HUMANOID));
        for (String name : BODY_ONLY) entities.put("minecraft:" + name, profiles.get(HitZones.Shape.BODY));
        ConfigurationSection overrides = section(root, "entities");
        Set<String> configuredEntities = new HashSet<>();
        if (overrides != null) for (String key : overrides.getKeys(false)) {
            String id = key.contains(":") ? key : "minecraft:" + key;
            validateEntity(id, "hitboxes.entities." + key);
            if (!configuredEntities.add(id)) throw invalid("hitboxes.entities." + key, "duplicate entity ID: " + id);
            ConfigurationSection values = requiredSection(overrides, key);
            HitZones.Profile base = entities.getOrDefault(id, profiles.get(defaultShape));
            if (values.contains("profile")) base = profiles.get(shape(values.getString("profile", ""), values.getCurrentPath() + ".profile"));
            entities.put(id, read(values, base, true));
        }
        return new HitboxProfiles(profiles.get(defaultShape), entities);
    }

    public HitZones.Profile forEntity(LivingEntity entity) {
        if (entity instanceof Player) return entities.getOrDefault("minecraft:player", fallback);
        EntityType type = entity.getType();
        return type == null || type == EntityType.UNKNOWN ? fallback : entities.getOrDefault(type.getKey().toString(), fallback);
    }

    private static HitZones.Profile read(ConfigurationSection section, HitZones.Profile base, boolean allowProfile) {
        for (String key : section.getKeys(false)) if (!FIELDS.contains(key) || key.equals("profile") && !allowProfile)
            throw invalid(section.getCurrentPath() + "." + key, "unknown hitbox setting");
        double width = number(section, "head-width", base.headWidth(), .05, 1);
        double height = number(section, "head-height", base.headHeight(), .05, .5);
        double legs = number(section, "legs-height", base.legsHeight(), 0, .8);
        if (base.shape() == HitZones.Shape.HUMANOID && (legs <= 0 || height + legs >= .95))
            throw invalid(section.getCurrentPath(), "humanoid legs-height must be positive and head-height + legs-height must be below 0.95");
        return new HitZones.Profile(base.shape(), width, height, legs);
    }

    private static double number(ConfigurationSection section, String key, double fallback, double min, double max) {
        Object value = section.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < min || number.doubleValue() > max)
            throw invalid(section.getCurrentPath() + "." + key, "must be a finite number within " + min + ".." + max);
        return number.doubleValue();
    }

    private static HitZones.Shape shape(String name, String path) {
        try { return HitZones.Shape.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw invalid(path, "profile must be humanoid, generic or body"); }
    }

    private static void validateEntity(String id, String path) {
        if (!id.matches("minecraft:[a-z0-9_]+")) throw invalid(path, "expected a Minecraft entity ID");
        try {
            if (!EntityType.valueOf(id.substring("minecraft:".length()).toUpperCase(Locale.ROOT)).isAlive())
                throw invalid(path, "entity must be a living entity");
        } catch (IllegalArgumentException failure) { throw invalid(path, "unknown living entity ID: " + id); }
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        return parent == null || !parent.contains(key) ? null : requiredSection(parent, key);
    }
    private static ConfigurationSection requiredSection(ConfigurationSection parent, String key) {
        ConfigurationSection result = parent.getConfigurationSection(key);
        if (result == null) throw invalid(parent.getCurrentPath() + "." + key, "must be a configuration section");
        return result;
    }
    private static IllegalArgumentException invalid(String path, String reason) { return new IllegalArgumentException(path + ": " + reason); }
}
