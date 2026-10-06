package com.tacz.guns.client.resource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.client.animation.gltf.AnimationStructure;
import com.tacz.guns.api.vmlib.LuaAnimationConstant;
import com.tacz.guns.api.vmlib.LuaGunAnimationConstant;
import com.tacz.guns.api.vmlib.LuaLibrary;
import com.tacz.guns.client.resource.manager.DisplayManager;
import com.tacz.guns.client.resource.manager.GltfManager;
import com.tacz.guns.client.resource.manager.PackInfoManager;
import com.tacz.guns.client.resource.pojo.CommonTransformObject;
import com.tacz.guns.client.resource.pojo.PackInfo;
import com.tacz.guns.client.resource.pojo.animation.bedrock.AnimationKeyframes;
import com.tacz.guns.client.resource.pojo.animation.bedrock.BedrockAnimationFile;
import com.tacz.guns.client.resource.pojo.animation.bedrock.SoundEffectKeyframes;
import com.tacz.guns.client.resource.pojo.display.ammo.AmmoDisplay;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.client.resource.pojo.display.block.BlockDisplay;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.CubesItem;
import com.tacz.guns.client.resource.serialize.AnimationKeyframesSerializer;
import com.tacz.guns.client.resource.serialize.ItemStackSerializer;
import com.tacz.guns.client.resource.serialize.SoundEffectKeyframesSerializer;
import com.tacz.guns.client.resource.serialize.Vector3fSerializer;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.manager.JsonDataManager;
import com.tacz.guns.resource.manager.LazyJsonDataManager;
import com.tacz.guns.resource.manager.ScriptManager;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier;
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.luaj.vm2.LuaTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 客户端资源管理器<br/>
 * 所有枪包资源缓存在此
 */
public enum ClientAssetsManager {
    INSTANCE;
    public static final Gson GSON = new GsonBuilder()
            .setStrictness(com.google.gson.Strictness.LENIENT)
            .registerTypeAdapter(ResourceLocation.class, new IdentifierSerializer())
            .registerTypeAdapter(CubesItem.class, new CubesItem.Deserializer())
            .registerTypeAdapter(Vector3f.class, new Vector3fSerializer())
            .registerTypeAdapter(CommonTransformObject.class, new CommonTransformObject.Serializer())
            .registerTypeAdapter(ItemStack.class, new ItemStackSerializer())
            .registerTypeAdapter(AnimationKeyframes.class, new AnimationKeyframesSerializer())
            .registerTypeAdapter(SoundEffectKeyframes.class, new SoundEffectKeyframesSerializer())
            .create();

    private DisplayManager<GunDisplay> gunDisplay;
    private DisplayManager<AmmoDisplay> ammoDisplay;
    private DisplayManager<AttachmentDisplay> attachmentDisplay;
    private DisplayManager<BlockDisplay> blockDisplay;
    private LazyJsonDataManager<BedrockModelPOJO> bedrockModel;
    private LazyJsonDataManager<BedrockAnimationFile> bedrockAnimation;
    private GltfManager gltfAnimation;
    private final List<LuaLibrary> libList = List.of(new LuaAnimationConstant(), new LuaGunAnimationConstant());
    private ScriptManager scriptManager;
    private PackInfoManager packInfo;

    private List<PreparableReloadListener> listeners;
    private boolean registered;

    public void reloadAndRegister(AddClientReloadListenersEvent event) {
        if (listeners == null) {
            listeners = new ArrayList<>();
            gunDisplay = remember(new DisplayManager<>(GunDisplay.class, GSON, "display/guns", "GunDisplayLoader"));
            ammoDisplay = remember(new DisplayManager<>(AmmoDisplay.class, GSON, "display/ammo", "AmmoDisplayLoader"));
            attachmentDisplay = remember(new DisplayManager<>(AttachmentDisplay.class, GSON, "display/attachments", "AttachmentDisplayLoader"));
            blockDisplay = remember(new DisplayManager<>(BlockDisplay.class, GSON, "display/blocks", "BlockDisplayLoader"));
            bedrockModel = remember(new LazyJsonDataManager<>(BedrockModelPOJO.class, GSON, "geo_models", "BedrockModelLoader",
                    id -> GunMod.MOD_ID.equals(id.getNamespace())));
            bedrockAnimation = remember(new LazyJsonDataManager<>(BedrockAnimationFile.class, GSON,
                    new FileToIdConverter("animations", ".animation.json"),
                    "BedrockAnimationLoader", id -> GunMod.MOD_ID.equals(id.getNamespace())));
            gltfAnimation = remember(new GltfManager());
            scriptManager = remember(new ScriptManager(new FileToIdConverter("scripts", ".lua"), libList));
            packInfo = remember(new PackInfoManager());
            remember(new ClientIndexReloadListener());
        }
        if (!registered) {
            for (PreparableReloadListener listener : listeners) {
                event.addListener(keyOf(listener), listener);
            }
            registered = true;
        }
    }

    private ResourceLocation keyOf(PreparableReloadListener listener) {
        if (listener instanceof JsonDataManager<?> json) {
            return json.ID;
        }
        if (listener instanceof LazyJsonDataManager<?> lazy) {
            return lazy.ID;
        }
        if (listener instanceof ScriptManager) {
            return ScriptManager.ID;
        }
        if (listener instanceof PackInfoManager) {
            return PackInfoManager.ID;
        }
        if (listener instanceof ClientIndexReloadListener) {
            return ClientIndexReloadListener.ID;
        }
        return ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, listener.getClass().getSimpleName().toLowerCase());
    }

    private <T extends PreparableReloadListener> T remember(T listener) {
        listeners.add(listener);
        return listener;
    }

    private static final class ClientIndexReloadListener implements PreparableReloadListener {
        static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "client_index_manager_reload");

        @Override
        public CompletableFuture<Void> reload(SharedState sharedState, Executor backgroundExecutor, PreparationBarrier barrier, Executor gameExecutor) {
            return barrier.wait(null).thenRunAsync(ClientIndexManager::reload, gameExecutor);
        }
    }

    @Nullable
    public GunDisplay getGunDisplay(ResourceLocation id) {
        return gunDisplay.getData(id);
    }

    public Set<Map.Entry<ResourceLocation, GunDisplay>> getGunDisplays() {
        return gunDisplay.getAllData().entrySet();
    }

    public Set<ResourceLocation> getGunDisplayIds() {
        return gunDisplay.getAllData().keySet();
    }

    @Nullable
    public AttachmentDisplay getAttachmentDisplay(ResourceLocation id) {
        return attachmentDisplay.getData(id);
    }

    @Nullable
    public AmmoDisplay getAmmoDisplay(ResourceLocation id) {
        return ammoDisplay.getData(id);
    }

    @Nullable
    public BlockDisplay getBlockDisplay(ResourceLocation id) {
        return blockDisplay.getData(id);
    }

    @Nullable
    public BedrockModelPOJO getBedrockModelPOJO(ResourceLocation id) {
        return bedrockModel.getData(id);
    }

    @Nullable
    public BedrockAnimationFile getBedrockAnimations(ResourceLocation id) {
        return bedrockAnimation.getData(id);
    }

    @Nullable
    public LuaTable getScript(ResourceLocation id) {
        return scriptManager.getScript(id);
    }

    @Nullable
    public AnimationStructure getGltfAnimation(ResourceLocation id) {
        return gltfAnimation.getGltfAnimation(id);
    }

    @Nullable
    public PackInfo getPackInfo(String namespace) {
        return packInfo.getData(namespace);
    }

    @Nullable
    public PackInfo getPackInfo(@Nullable ResourceLocation namespace) {
        if (namespace == null) {
            return null;
        }
        return packInfo.getData(namespace.getNamespace());
    }

    public static void reloadAllPack() {
        // Reload applies resources on the client executor; waiting here would deadlock a
        // reload initiated by the client command handler.
        Minecraft.getInstance().reloadResourcePacks().thenRun(() -> {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.execute(CommonAssetsManager::reloadAllPack);
        }).exceptionally(error -> {
            GunMod.LOGGER.error("Failed to reload TACZ client resources", error);
            return null;
        });
    }
}
