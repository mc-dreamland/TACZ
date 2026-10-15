package com.tacz.guns.bridge;

import java.util.List;
import java.util.Map;

/** Forge ingredient aliases used by both the Paper craft authority and the native client recipe view. */
public final class BridgeRecipeIngredients {
    public static final Map<String, String> FORGE_ITEMS = Map.ofEntries(
            Map.entry("forge:ingots/iron", "minecraft:iron_ingot"), Map.entry("forge:ingots/gold", "minecraft:gold_ingot"),
            Map.entry("forge:ingots/copper", "minecraft:copper_ingot"), Map.entry("forge:ingots/netherite", "minecraft:netherite_ingot"),
            Map.entry("forge:gems/diamond", "minecraft:diamond"), Map.entry("forge:gems/lapis", "minecraft:lapis_lazuli"),
            Map.entry("forge:gems/quartz", "minecraft:quartz"), Map.entry("forge:gems/amethyst", "minecraft:amethyst_shard"),
            Map.entry("forge:dusts/glowstone", "minecraft:glowstone_dust"), Map.entry("forge:dusts/redstone", "minecraft:redstone"),
            Map.entry("forge:gunpowder", "minecraft:gunpowder"), Map.entry("forge:leather", "minecraft:leather"),
            Map.entry("forge:nuggets/iron", "minecraft:iron_nugget"), Map.entry("forge:ores/netherite_scrap", "minecraft:ancient_debris"),
            Map.entry("forge:rods/blaze", "minecraft:blaze_rod"));
    public static final List<String> GLASS_ITEMS = List.of(
            "minecraft:glass", "minecraft:white_stained_glass", "minecraft:orange_stained_glass", "minecraft:magenta_stained_glass",
            "minecraft:light_blue_stained_glass", "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass", "minecraft:pink_stained_glass",
            "minecraft:gray_stained_glass", "minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass", "minecraft:purple_stained_glass",
            "minecraft:blue_stained_glass", "minecraft:brown_stained_glass", "minecraft:green_stained_glass", "minecraft:red_stained_glass",
            "minecraft:black_stained_glass");

    private BridgeRecipeIngredients() {}
}
