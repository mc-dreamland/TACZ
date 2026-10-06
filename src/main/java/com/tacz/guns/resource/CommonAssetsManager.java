package com.tacz.guns.resource;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.vmlib.LuaGunLogicConstant;
import com.tacz.guns.api.vmlib.LuaLibrary;
import com.tacz.guns.crafting.GunSmithTableIngredient;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import com.tacz.guns.init.ModRecipe;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.ServerMessageSyncGunPack;
import com.tacz.guns.resource.filter.RecipeFilter;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import com.tacz.guns.resource.index.CommonBlockIndex;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.manager.AttachmentDataManager;
import com.tacz.guns.resource.manager.AttachmentsTagManager;
import com.tacz.guns.resource.manager.CommonDataManager;
import com.tacz.guns.resource.manager.INetworkCacheReloadListener;
import com.tacz.guns.resource.manager.LootInjectionManager;
import com.tacz.guns.resource.manager.RecipeFilterManager;
import com.tacz.guns.resource.manager.ScriptManager;
import com.tacz.guns.resource.manager.TableRecipeManager;
import com.tacz.guns.resource.network.CommonNetworkCache;
import com.tacz.guns.resource.network.DataType;
import com.tacz.guns.resource.pojo.data.attachment.AttachmentData;
import com.tacz.guns.resource.pojo.data.block.BlockData;
import com.tacz.guns.resource.pojo.data.block.TabConfig;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.gun.Ignite;
import com.tacz.guns.resource.pojo.data.loot.LootTableInjection;
import com.tacz.guns.resource.pojo.data.recipe.TableRecipe;
import com.tacz.guns.resource.serialize.CommonAmmoIndexSerializer;
import com.tacz.guns.resource.serialize.CommonAttachmentIndexSerializer;
import com.tacz.guns.resource.serialize.CommonBlockIndexSerializer;
import com.tacz.guns.resource.serialize.CommonGunIndexSerializer;
import com.tacz.guns.resource.serialize.DistanceDamagePairSerializer;
import com.tacz.guns.resource.serialize.GunSmithTableIngredientSerializer;
import com.tacz.guns.resource.serialize.GunSmithTableResultSerializer;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import com.tacz.guns.resource.serialize.IgniteSerializer;
import com.tacz.guns.resource.serialize.PairSerializer;
import com.tacz.guns.resource.serialize.Vec3Serializer;
import com.tacz.guns.util.AllowAttachmentTagMatcher;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evidence: AddServerReloadListenersEvent#addListener(ResourceLocation, PreparableReloadListener) ②
 * PreparableReloadListener.reload(SharedState, Executor, PreparationBarrier, Executor) ①
 */
@EventBusSubscriber(modid = GunMod.MOD_ID)
public class CommonAssetsManager implements ICommonResourceProvider {
    private static CommonAssetsManager INSTANCE;
    public static final Gson GSON = new GsonBuilder()
            .setStrictness(Strictness.LENIENT)
            .registerTypeAdapter(ResourceLocation.class, new IdentifierSerializer())
            .registerTypeAdapter(Pair.class, new PairSerializer())
            .registerTypeAdapter(GunSmithTableIngredient.class, new GunSmithTableIngredientSerializer())
            .registerTypeAdapter(GunSmithTableResult.class, new GunSmithTableResultSerializer())
            .registerTypeAdapter(ExtraDamage.DistanceDamagePair.class, new DistanceDamagePairSerializer())
            .registerTypeAdapter(Vec3.class, new Vec3Serializer())
            .registerTypeAdapter(Ignite.class, new IgniteSerializer())
            .registerTypeAdapter(RecipeFilter.class, new RecipeFilter.Deserializer())
            .registerTypeAdapter(CommonGunIndex.class, new CommonGunIndexSerializer())
            .registerTypeAdapter(CommonAmmoIndex.class, new CommonAmmoIndexSerializer())
            .registerTypeAdapter(CommonAttachmentIndex.class, new CommonAttachmentIndexSerializer())
            .registerTypeAdapter(CommonBlockIndex.class, new CommonBlockIndexSerializer())
            .registerTypeAdapter(TabConfig.class, new TabConfig.Deserializer())
            .create();

    private final List<INetworkCacheReloadListener> listeners = new ArrayList<>();
    private CommonDataManager<GunData> gunData;
    private CommonDataManager<AttachmentData> attachmentData;
    private CommonDataManager<BlockData> blockData;
    private CommonDataManager<CommonAmmoIndex> ammoIndex;
    private CommonDataManager<CommonGunIndex> gunIndex;
    private CommonDataManager<CommonAttachmentIndex> attachmentIndex;
    private CommonDataManager<CommonBlockIndex> blockIndex;
    private CommonDataManager<TableRecipe> tableRecipe;
    private RecipeFilterManager recipeFilterManager;
    private LootInjectionManager lootInjectionManager;
    private AttachmentsTagManager attachmentsTagManager;
    List<LuaLibrary> libList = List.of(new LuaGunLogicConstant());
    private final ScriptManager scriptManager = new ScriptManager(new FileToIdConverter("scripts", ".lua"), libList);
    public RecipeManager recipeManager;

    public void reloadAndRegister(AddServerReloadListenersEvent event) {
        AtomicInteger anon = new AtomicInteger();
        java.util.function.Consumer<PreparableReloadListener> register = listener -> {
            ResourceLocation key;
            if (listener instanceof com.tacz.guns.resource.manager.JsonDataManager<?> json) {
                key = json.ID;
            } else {
                String name = listener.getClass().getSimpleName();
                if (name.isEmpty() || name.contains("$")) {
                    name = "listener_" + anon.incrementAndGet();
                }
                key = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, name.toLowerCase(Locale.ROOT));
            }
            event.addListener(key, listener);
        };

        gunData = registerNetwork(new CommonDataManager<>(DataType.GUN_DATA, GunData.class, GSON, "data/guns", "GunDataLoader"), register);
        attachmentData = registerNetwork(new AttachmentDataManager(), register);
        // 2026-08-21 LAN 实测回归修复：这两个管理器实现了 INetworkCacheReloadListener，
        // 但此前只 register.accept、未入 listeners —— ATTACHMENT_TAGS 与 RECIPE_FILTER
        // 从不进 getNetworkCache() 同步包。客户端缺 RecipeFilter 时，BLOCK_INDEX 的
        // CommonBlockIndex.checkData 第二个 Precondition 必炸（"there is no corresponding
        // data file"，默认包 tacz:gun_smith_table 也不能幸免）；缺 ATTACHMENT_TAGS 则
        // 客户端配件允装判断静默失效。
        // 证据：③ refab 26.1.2 CommonAssetsManager#reloadAndRegister —— 其 register(...)
        // 助手把 attachmentsTagManager 与 recipeFilterManager 一并加入 listeners（全同步），
        // 仅 lootInjectionManager 与 scriptManager 不同步。本处对齐该接线。
        attachmentsTagManager = registerNetwork(new AttachmentsTagManager(), register);
        recipeFilterManager = registerNetwork(new RecipeFilterManager(), register);
        lootInjectionManager = new LootInjectionManager(event.getRegistryAccess());
        register.accept(lootInjectionManager);
        blockData = registerNetwork(new CommonDataManager<>(DataType.BLOCK_DATA, BlockData.class, GSON, "data/blocks", "BlockDataLoader"), register);
        register.accept(scriptManager);

        ammoIndex = registerNetwork(new CommonDataManager<>(DataType.AMMO_INDEX, CommonAmmoIndex.class, GSON, "index/ammo", "AmmoIndexLoader"), register);
        gunIndex = registerNetwork(new CommonDataManager<>(DataType.GUN_INDEX, CommonGunIndex.class, GSON, "index/guns", "GunIndexLoader"), register);
        attachmentIndex = registerNetwork(new CommonDataManager<>(DataType.ATTACHMENT_INDEX, CommonAttachmentIndex.class, GSON, "index/attachments", "AttachmentIndexLoader"), register);
        blockIndex = registerNetwork(new CommonDataManager<>(DataType.BLOCK_INDEX, CommonBlockIndex.class, GSON, "index/blocks", "BlockIndexLoader"), register);
        tableRecipe = registerNetwork(new TableRecipeManager(), register);

        register.accept((sharedState, backgroundExecutor, barrier, gameExecutor) ->
                barrier.wait(null).thenRunAsync(AllowAttachmentTagMatcher::resetCache, gameExecutor));
    }

    private <T extends INetworkCacheReloadListener> T registerNetwork(T listener, java.util.function.Consumer<PreparableReloadListener> register) {
        listeners.add(listener);
        register.accept(listener);
        return listener;
    }

    public Map<DataType, Map<ResourceLocation, String>> getNetworkCache() {
        ImmutableMap.Builder<DataType, Map<ResourceLocation, String>> builder = ImmutableMap.builder();
        for (INetworkCacheReloadListener listener : listeners) {
            Map<ResourceLocation, String> cache = listener.getNetworkCache();
            if (cache != null) {
                builder.put(listener.getType(), cache);
            }
        }
        return builder.build();
    }

    @Nullable
    @Override
    public GunData getGunData(ResourceLocation id) {
        return gunData == null ? null : gunData.getData(id);
    }

    @Nullable
    @Override
    public AttachmentData getAttachmentData(ResourceLocation id) {
        return attachmentData == null ? null : attachmentData.getData(id);
    }

    @Nullable
    @Override
    public BlockData getBlockData(ResourceLocation id) {
        return blockData == null ? null : blockData.getData(id);
    }

    @Override
    @Nullable
    public RecipeFilter getRecipeFilter(ResourceLocation id) {
        return recipeFilterManager == null ? null : recipeFilterManager.getFilter(id);
    }

    public Set<ResourceLocation> getLootInjectionTargets() {
        if (lootInjectionManager == null) {
            return Set.of();
        }
        return lootInjectionManager.getInjectionTargets();
    }

    public List<LootTableInjection> getLootTableInjections(ResourceLocation lootTable) {
        if (lootInjectionManager == null) {
            return List.of();
        }
        return lootInjectionManager.getInjections(lootTable);
    }

    @Nullable
    @Override
    public CommonGunIndex getGunIndex(ResourceLocation gunId) {
        return gunIndex == null ? null : gunIndex.getData(gunId);
    }

    @Override
    public Set<Map.Entry<ResourceLocation, CommonGunIndex>> getAllGuns() {
        return gunIndex == null ? Set.of() : gunIndex.getAllData().entrySet();
    }

    @Nullable
    @Override
    public CommonAmmoIndex getAmmoIndex(ResourceLocation ammoId) {
        return ammoIndex == null ? null : ammoIndex.getData(ammoId);
    }

    @Override
    public Set<Map.Entry<ResourceLocation, CommonAmmoIndex>> getAllAmmos() {
        return ammoIndex == null ? Set.of() : ammoIndex.getAllData().entrySet();
    }

    @Nullable
    @Override
    public CommonAttachmentIndex getAttachmentIndex(ResourceLocation attachmentId) {
        return attachmentIndex == null ? null : attachmentIndex.getData(attachmentId);
    }

    @Override
    public Set<Map.Entry<ResourceLocation, CommonAttachmentIndex>> getAllAttachments() {
        return attachmentIndex == null ? Set.of() : attachmentIndex.getAllData().entrySet();
    }

    @Override
    public LuaTable getScript(ResourceLocation scriptId) {
        return scriptManager.getScript(scriptId);
    }

    @Nullable
    @Override
    public CommonBlockIndex getBlockIndex(ResourceLocation blockId) {
        return blockIndex == null ? null : blockIndex.getData(blockId);
    }

    @Override
    public TableRecipe getTableRecipe(ResourceLocation recipeId) {
        return tableRecipe == null ? null : tableRecipe.getData(recipeId);
    }

    @Override
    public Set<Map.Entry<ResourceLocation, TableRecipe>> getAllTableRecipes() {
        return tableRecipe == null ? java.util.Collections.emptySet() : tableRecipe.getAllData().entrySet();
    }

    @Override
    public Set<Map.Entry<ResourceLocation, CommonBlockIndex>> getAllBlocks() {
        return blockIndex == null ? Set.of() : blockIndex.getAllData().entrySet();
    }

    @Override
    public Set<String> getAttachmentTags(ResourceLocation registryName) {
        return attachmentsTagManager == null ? Set.of() : attachmentsTagManager.getAttachmentTags(registryName);
    }

    @Override
    public Set<String> getAllowAttachmentTags(ResourceLocation registryName) {
        return attachmentsTagManager == null ? Set.of() : attachmentsTagManager.getAllowAttachmentTags(registryName);
    }

    @Nullable
    public static CommonAssetsManager getInstance() {
        return INSTANCE;
    }

    public static void clearInstance() {
        INSTANCE = null;
    }

    public static ICommonResourceProvider get() {
        return INSTANCE == null ? CommonNetworkCache.INSTANCE : INSTANCE;
    }

    public RecipeManager getRecipeManager() {
        return recipeManager;
    }

    @SubscribeEvent
    public static void onReload(AddServerReloadListenersEvent event) {
        var commonAssetsManager = new CommonAssetsManager();
        commonAssetsManager.reloadAndRegister(event);
        INSTANCE = commonAssetsManager;
        INSTANCE.recipeManager = event.getServerResources().getRecipeManager();
        int guns = INSTANCE.gunIndex == null ? 0 : INSTANCE.gunIndex.getAllData().size();
        GunMod.LOGGER.info("CommonAssetsManager listeners registered (gun index fills after reload). placeholderGuns={}", guns);
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        com.tacz.guns.util.ItemNbtUtils.setLookupProvider(event.getLookupProvider());
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD && getInstance() != null && getInstance().recipeManager != null) {
            List<GunSmithTableRecipe> recipes = getInstance().recipeManager.getRecipes().stream()
                    .filter(holder -> holder.value().getType() == ModRecipe.GUN_SMITH_TABLE_CRAFTING.get())
                    .map(holder -> {
                        GunSmithTableRecipe recipe = (GunSmithTableRecipe) holder.value();
                        recipe.bindId(holder.id().location());
                        return recipe;
                    })
                    .toList();
            for (GunSmithTableRecipe recipe : recipes) {
                recipe.init();
            }
            if (getInstance().gunIndex != null) {
                GunMod.LOGGER.info("Gun pack loaded: guns={} ammo={} attachments={} blocks={} recipes={}",
                        getInstance().gunIndex.getAllData().size(),
                        getInstance().ammoIndex == null ? 0 : getInstance().ammoIndex.getAllData().size(),
                        getInstance().attachmentIndex == null ? 0 : getInstance().attachmentIndex.getAllData().size(),
                        getInstance().blockIndex == null ? 0 : getInstance().blockIndex.getAllData().size(),
                        getInstance().tableRecipe == null ? 0 : getInstance().tableRecipe.getAllData().size());
            }
            // Legacy vanilla crafting recipes from gun-pack `recipes/` are remapped to
            // `recipe/` in DelegatingPackResources (with RecipeCompat format conversion)
            // so the minecraft:recipe datapack registry loads them. RecipeManager on
            // 26.1.2 only copies that registry into an immutable RecipeMap — there is
            // no fromJson(Map) / mutable recipes field to inject into after the fact.
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearInstance();
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (getInstance() == null) {
            return;
        }
        ServerMessageSyncGunPack message = new ServerMessageSyncGunPack(getInstance().getNetworkCache());
        event.getRelevantPlayers().forEach(player -> NetworkHandler.sendToClientPlayer(message, player));
    }

    public static void reloadAllPack() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        PackRepository packrepository = server.getPackRepository();
        packrepository.reload();
        Collection<String> collection = packrepository.getSelectedIds();
        server.reloadResources(collection);
    }
}
