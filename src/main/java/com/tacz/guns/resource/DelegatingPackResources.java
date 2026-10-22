package com.tacz.guns.resource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.tacz.guns.GunMod;
import com.tacz.guns.crafting.RecipeCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DelegatingPackResources extends AbstractPackResources {
    private final PackMetadataSection packMeta;
    private final List<PackResources> delegates;
    private final Map<String, List<PackResources>> namespacesAssets;
    private final Map<String, List<PackResources>> namespacesData;

    public DelegatingPackResources(String packId, boolean isBuiltin, PackMetadataSection packMeta, List<? extends PackResources> packs) {
        super(new PackLocationInfo(packId, Component.literal(packId), PackSource.DEFAULT, Optional.empty()));
        this.packMeta = packMeta;
        this.delegates = ImmutableList.copyOf(packs);
        this.namespacesAssets = this.buildNamespaceMap(PackType.CLIENT_RESOURCES, delegates);
        this.namespacesData = this.buildNamespaceMap(PackType.SERVER_DATA, delegates);
    }

    private Map<String, List<PackResources>> buildNamespaceMap(PackType type, List<PackResources> packList) {
        Map<String, List<PackResources>> map = new HashMap<>();
        for (PackResources pack : packList) {
            for (String namespace : pack.getNamespaces(type)) {
                map.computeIfAbsent(namespace, k -> new ArrayList<>()).add(pack);
            }
        }
        map.replaceAll((k, list) -> ImmutableList.copyOf(list));
        return ImmutableMap.copyOf(map);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> deserializer) throws IOException {
        return deserializer.name().equals("pack") ? (T) this.packMeta : null;
    }

    @Override
    public void listResources(PackType type, String resourceNamespace, String paths, ResourceOutput resourceOutput) {
        for (PackResources delegate : this.delegates) {
            delegate.listResources(type, resourceNamespace, paths, resourceOutput);
        }
        // 26.1.2 RecipeManager no longer scans JSON itself: it copies the minecraft:recipe
        // datapack registry, whose FileToIdConverter only lists data/<ns>/recipe/.
        // Old gun packs still ship vanilla crafting JSONs under recipes/ (plural).
        // Expose those files at the singular path with codec-era JSON so survival
        // crafting and JEI/REI see them. Gun-smith-table recipes are left on recipes/
        // for TableRecipeManager.
        if (type == PackType.SERVER_DATA) {
            listLegacyVanillaRecipes(resourceNamespace, paths, resourceOutput);
        }
    }

    private void listLegacyVanillaRecipes(String resourceNamespace, String paths, ResourceOutput resourceOutput) {
        String legacyPath = RecipeCompat.toLegacyRecipesDirectory(paths);
        if (legacyPath == null) {
            return;
        }
        int[] remapped = {0};
        for (PackResources delegate : this.delegates) {
            delegate.listResources(PackType.SERVER_DATA, resourceNamespace, legacyPath, (id, supplier) -> {
                Identifier remappedId = RecipeCompat.remapRecipesToRecipe(id);
                if (remappedId == null) {
                    return;
                }
                if (getDelegateResource(PackType.SERVER_DATA, remappedId) != null) {
                    return;
                }
                IoSupplier<InputStream> converted = convertLegacyVanillaRecipe(supplier);
                if (converted == null) {
                    return;
                }
                resourceOutput.accept(remappedId, converted);
                remapped[0]++;
            });
        }
        if (remapped[0] > 0) {
            GunMod.LOGGER.info("[TACZ] Remapped {} legacy recipes/ file(s) to recipe/ for namespace '{}'",
                    remapped[0], resourceNamespace);
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? namespacesAssets.keySet() : namespacesData.keySet();
    }

    @Override
    public void close() {
        for (PackResources pack : delegates) {
            pack.close();
        }
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        // Root resources do not make sense here
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        IoSupplier<InputStream> existing = getDelegateResource(type, location);
        if (existing != null) {
            return existing;
        }
        if (type != PackType.SERVER_DATA) {
            return null;
        }
        Identifier legacy = RecipeCompat.toLegacyRecipesLocation(location);
        if (legacy == null) {
            return null;
        }
        IoSupplier<InputStream> legacySupplier = getDelegateResource(type, legacy);
        if (legacySupplier == null) {
            return null;
        }
        return convertLegacyVanillaRecipe(legacySupplier);
    }

    @Nullable
    public Collection<PackResources> getChildren() {
        return delegates;
    }

    @Nullable
    private IoSupplier<InputStream> getDelegateResource(PackType type, Identifier location) {
        for (PackResources pack : getCandidatePacks(type, location)) {
            IoSupplier<InputStream> ioSupplier = pack.getResource(type, location);
            if (ioSupplier != null) {
                return ioSupplier;
            }
        }
        return null;
    }

    private List<PackResources> getCandidatePacks(PackType type, Identifier location) {
        Map<String, List<PackResources>> map = type == PackType.CLIENT_RESOURCES ? namespacesAssets : namespacesData;
        List<PackResources> packsWithNamespace = map.get(location.getNamespace());
        return packsWithNamespace == null ? Collections.emptyList() : packsWithNamespace;
    }

    /**
     * Parse a legacy {@code recipes/} JSON, keep only vanilla shaped/shapeless crafting,
     * and rewrite it to the 26.x codec form. Returns {@code null} for gun-smith-table
     * recipes (those stay on the plural path).
     */
    @Nullable
    private static IoSupplier<InputStream> convertLegacyVanillaRecipe(IoSupplier<InputStream> original) {
        JsonElement converted;
        try (InputStream in = original.get();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            converted = RecipeCompat.convertLegacyVanillaRecipe(RecipeCompat.parseLenient(reader));
        } catch (Exception e) {
            return null;
        }
        if (converted == null) {
            return null;
        }
        byte[] bytes = converted.toString().getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }
}
