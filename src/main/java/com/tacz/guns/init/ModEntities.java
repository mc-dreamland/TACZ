package com.tacz.guns.init;

import com.tacz.guns.GunMod;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.entity.TargetMinecart;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister.Entities ENTITY_TYPES = DeferredRegister.createEntities(GunMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<EntityKineticBullet>> BULLET =
            ENTITY_TYPES.register("bullet", () -> EntityKineticBullet.TYPE);
    public static final DeferredHolder<EntityType<?>, EntityType<TargetMinecart>> TARGET_MINECART =
            ENTITY_TYPES.register("target_minecart", () -> TargetMinecart.TYPE);
}
