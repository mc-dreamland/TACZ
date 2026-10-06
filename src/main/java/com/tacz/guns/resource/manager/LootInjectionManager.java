package com.tacz.guns.resource.manager;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.tacz.guns.GunMod;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.pojo.data.loot.LootTableInjection;
import com.tacz.guns.util.ResourceScanner;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LootInjectionManager extends SimplePreparableReloadListener<Map<ResourceLocation, List<JsonElement>>>  {
    private final HolderLookup.Provider registries;
    private final Map<ResourceLocation, List<LootTableInjection>> injections = Maps.newHashMap();
    private final Gson gson = CommonAssetsManager.GSON;
    private final Marker marker = MarkerFactory.getMarker("LootInjection");
    private final FileToIdConverter fileToIdConverter = FileToIdConverter.json("tacz_loot_injectors");

    public LootInjectionManager(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    @Override
    protected @NotNull Map<ResourceLocation, List<JsonElement>> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return ResourceScanner.scanDirectoryAll(resourceManager, fileToIdConverter, gson);
    }

    @Override
    protected void apply(Map<ResourceLocation, List<JsonElement>> object, ResourceManager resourceManager, ProfilerFiller profiler) {
        injections.clear();
        for (Map.Entry<ResourceLocation, List<JsonElement>> entry : object.entrySet()) {
            ResourceLocation id = entry.getKey();
            for (JsonElement element : entry.getValue()) {
                try {
                    LootTableInjection injection = LootTableInjection.fromJson(id, element, registries);
                    for (ResourceLocation lootTable : injection.lootTables()) {
                        injections.computeIfAbsent(lootTable, key -> new java.util.ArrayList<>()).add(injection);
                    }
                } catch (JsonParseException | IllegalArgumentException e) {
                    GunMod.LOGGER.error(marker, "Failed to load loot injection {}", id, e);
                }
            }
        }
    }

    public List<LootTableInjection> getInjections(ResourceLocation lootTable) {
        return injections.getOrDefault(lootTable, Collections.emptyList());
    }

    /**
     * 所有被声明为注入目标的战利品表 ID。
     *
     * <p>{@code injections} 本就以目标表 ID 为键，这里直接暴露键集即可，
     * 无需额外维护状态。返回不可变视图，防止调用方误改。</p>
     */
    public Set<ResourceLocation> getInjectionTargets() {
        return Collections.unmodifiableSet(injections.keySet());
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "loot_injection_loader");
}