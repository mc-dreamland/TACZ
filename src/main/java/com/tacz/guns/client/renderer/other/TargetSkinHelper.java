package com.tacz.guns.client.renderer.other;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.ResolvableProfile;

/** Uses vanilla's asynchronous profile and skin cache for named target heads. */
public final class TargetSkinHelper {
    private TargetSkinHelper() {
    }

    public static ResourceLocation texture(GameProfile owner) {
        ResolvableProfile profile;
        if (!owner.properties().isEmpty()) {
            profile = ResolvableProfile.createResolved(owner);
        } else if (!owner.name().isBlank()) {
            profile = ResolvableProfile.createUnresolved(owner.name());
        } else {
            profile = ResolvableProfile.createUnresolved(owner.id());
        }
        return Minecraft.getInstance().playerSkinRenderCache().getOrDefault(profile)
                .playerSkin().body().texturePath();
    }
}
