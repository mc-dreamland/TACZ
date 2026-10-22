package com.tacz.guns.client.event;

import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import com.tacz.guns.client.resource.InternalAssetLoader;
import com.tacz.guns.client.sound.SoundPlayManager;
import net.minecraft.resources.Identifier;

public class ReloadResourceEvent {
    public static final Identifier BLOCK_ATLAS_TEXTURE = Identifier.withDefaultNamespace("textures/atlas/blocks.png");

    public static void onTextureAtlasStitched(TextureAtlasStitchedEvent event) {
        if (BLOCK_ATLAS_TEXTURE.equals(event.getAtlas().location())) {
            // InternalAssetLoader 需要加载一些默认的动画、模型，需要先于枪包加载。
            InternalAssetLoader.onResourceReload();
            SoundPlayManager.clearSoundResourceCache();
//            ClientReloadManager.reloadAllPack();
        }
    }
}
