package com.tacz.guns.init;

import com.tacz.guns.GunMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;

/** Painting variants are supplied by data packs in modern Minecraft. */
public final class ModPainting {
    public static final ResourceKey<PaintingVariant> BLOOD_STRIKE_1 = ResourceKey.create(
            Registries.PAINTING_VARIANT, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "blood_strike_1"));
    private ModPainting() {}
}
