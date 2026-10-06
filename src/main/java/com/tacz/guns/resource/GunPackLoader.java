package com.tacz.guns.resource;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.resource.ResourceManager;
import com.tacz.guns.config.PreLoadConfig;
import com.tacz.guns.util.GetJarResources;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.InclusiveRange;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 26.1.2 pack APIs from Fabric semantics; loader paths/version from NeoForge 26.1.
 * Evidence: PathPackResources.PathResourcesSupplier(Path)#openPrimary(PackLocationInfo) ①
 * FilePackResources.FileResourcesSupplier ①
 * PackMetadataSection(Component, InclusiveRange&lt;PackFormat&gt;) ①
 * WorldVersion#packVersion(PackType) ①
 * Pack.readMetaAndCreate(PackLocationInfo, ResourcesSupplier, PackType, PackSelectionConfig) ①
 * FMLPaths.GAMEDIR ② loader-11.0.15
 */
public enum GunPackLoader implements RepositorySource {
    INSTANCE;
    private static final Marker MARKER = MarkerFactory.getMarker("GunPackFinder");
    private boolean firstLoad = true;

    @Override
    public void loadPacks(Consumer<Pack> pOnLoad) {
        // This path is taken when a repository calls loadPacks on us as a
        // RepositorySource.  We cannot know the PackType here, so we default
        // to SERVER_DATA (what the RecipeManager needs).  The per-type entry
        // point loadPacksForType is preferred and is used by the
        // AddPackFindersEvent handler in CommonRegistry.
        loadPacksForType(pOnLoad, PackType.SERVER_DATA);
    }

    /**
     * Entry point that knows which PackType the caller needs.
     * Used by the AddPackFindersEvent handler which captures
     * event.getPackType() in a closure instead of relying on a
     * mutable singleton field that can be overwritten by a later
     * event firing for the other PackType.
     */
    public void loadPacksForType(Consumer<Pack> pOnLoad, PackType packType) {
        Pack extensionsPack = discoverExtensions(packType);
        if (extensionsPack != null) {
            pOnLoad.accept(extensionsPack);
        }
    }

    private Pack discoverExtensions(PackType packType) {
        GunMod.LOGGER.info(MARKER, "discoverExtensions called with packType={}, firstLoad={}", packType, firstLoad);
        Path resourcePacksPath = FMLPaths.GAMEDIR.get().resolve("tacz");
        File folder = resourcePacksPath.toFile();
        if (!folder.isDirectory()) {
            try {
                Files.createDirectories(folder.toPath());
            } catch (Exception e) {
                GunMod.LOGGER.warn(MARKER, "Failed to init tacz resource directory...", e);
                return null;
            }
        }

        if (firstLoad) {
            if (PreLoadConfig.shouldOverwriteDefaultPack()) {
                for (ResourceManager.ExtraEntry entry : ResourceManager.EXTRA_ENTRIES) {
                    GetJarResources.copyModDirectory(entry.modMainClass(), entry.srcPath(), resourcePacksPath, entry.extraDirName());
                }
            }
            firstLoad = false;
        }

        GunMod.LOGGER.info(MARKER, "Start scanning for gun packs in {}", resourcePacksPath);
        List<GunPack> gunPacks = scanExtensions(resourcePacksPath);
        GunMod.LOGGER.info(MARKER, "Found {} possible gunpack(s) and added them to resource set.", gunPacks.size());
        List<PackResources> extensionPacks = new ArrayList<>();

        for (GunPack gunPack : gunPacks) {
            PackResources packResources;
            if (Files.isDirectory(gunPack.path)) {
                packResources = new PathPackResources.PathResourcesSupplier(gunPack.path)
                        .openPrimary(new PackLocationInfo(gunPack.name, Component.literal(gunPack.name), PackSource.BUILT_IN, Optional.empty()));
            } else {
                packResources = new FilePackResources.FileResourcesSupplier(gunPack.path)
                        .openPrimary(new PackLocationInfo(gunPack.name, Component.literal(gunPack.name), PackSource.BUILT_IN, Optional.empty()));
            }
            extensionPacks.add(packResources);
        }

        PackFormat format = SharedConstants.getCurrentVersion().packVersion(packType);
        PackLocationInfo location = new PackLocationInfo("tacz_resources", Component.literal("TACZ Resources"), PackSource.BUILT_IN, Optional.empty());
        PackMetadataSection meta = new PackMetadataSection(
                Component.translatable("tacz.resources.modresources"),
                new InclusiveRange<>(format));
        DelegatingPackResources pack = new DelegatingPackResources("tacz_resources", false, meta, extensionPacks) {
            public IoSupplier<InputStream> getRootResource(String... paths) {
                if (paths.length == 1 && paths[0].equals("pack.png")) {
                    Path logoPath = getModIcon("tacz");
                    if (logoPath != null) {
                        return IoSupplier.create(logoPath);
                    }
                }
                return null;
            }
        };
        Pack.ResourcesSupplier resourcesSupplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo locationInfo) {
                return pack;
            }

            @Override
            public PackResources openFull(PackLocationInfo locationInfo, Pack.Metadata metadata) {
                return openPrimary(locationInfo);
            }
        };
        return Pack.readMetaAndCreate(location, resourcesSupplier, packType, new PackSelectionConfig(true, Pack.Position.BOTTOM, false));
    }

    public static @Nullable Path getModIcon(String modId) {
        Optional<? extends ModContainer> m = ModList.get().getModContainerById(modId);
        if (m.isPresent()) {
            IModInfo mod = m.get().getModInfo();
            IModFile file = mod.getOwningFile().getFile();
            if (file != null) {
                Path logoPath = file.getFilePath().resolve("icon.png");
                if (Files.exists(logoPath)) {
                    return logoPath;
                }
            }
        }
        return null;
    }

    private static GunPack fromDirPath(Path path) throws IOException {
        Path packInfoFilePath = path.resolve("gunpack.meta.json");
        try (InputStream stream = Files.newInputStream(packInfoFilePath)) {
            PackMeta info = CommonAssetsManager.GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), PackMeta.class);
            if (info == null) {
                GunMod.LOGGER.warn(MARKER, "Failed to read info json: {}", packInfoFilePath.getFileName());
                return null;
            }
            if (info.getDependencies() != null && !modVersionAllMatch(info)) {
                GunMod.LOGGER.warn(MARKER, "Mod version mismatch: {}", packInfoFilePath.getFileName());
                return null;
            }
            return new GunPack(path, info.getName());
        } catch (IOException | JsonSyntaxException | JsonIOException | InvalidVersionSpecificationException exception) {
            GunMod.LOGGER.warn(MARKER, "Failed to read info json: {}", packInfoFilePath.getFileName());
            GunMod.LOGGER.warn(exception.getMessage());
        }
        return null;
    }

    private static GunPack fromZipPath(Path path) {
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            ZipEntry extDescriptorEntry = zipFile.getEntry("gunpack.meta.json");
            if (extDescriptorEntry == null) {
                GunMod.LOGGER.error(MARKER, "Failed to load extension from ZIP {}. Error: {}", path.getFileName(), "No gunpack.meta.json found");
                return null;
            }
            try (InputStream stream = zipFile.getInputStream(extDescriptorEntry)) {
                PackMeta info = CommonAssetsManager.GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), PackMeta.class);
                if (info == null) {
                    GunMod.LOGGER.warn(MARKER, "Failed to read info json: {}", path.getFileName());
                    return null;
                }
                if (info.getDependencies() != null && !modVersionAllMatch(info)) {
                    GunMod.LOGGER.warn(MARKER, "Mod version mismatch: {}", path.getFileName());
                    return null;
                }
                return new GunPack(path, info.getName());
            } catch (IOException | JsonSyntaxException | JsonIOException | InvalidVersionSpecificationException e) {
                GunMod.LOGGER.error(MARKER, "Failed to load extension from ZIP {}. Error: {}", path.getFileName(), e);
                return null;
            }
        } catch (IOException e) {
            GunMod.LOGGER.error(MARKER, "Failed to load extension from ZIP {}. Error: {}", path.getFileName(), e);
            return null;
        }
    }

    private static List<GunPack> scanExtensions(Path extensionsPath) {
        List<GunPack> gunPacks = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(extensionsPath)) {
            for (Path entry : stream) {
                GunPack gunPack = null;
                if (Files.isDirectory(entry)) {
                    gunPack = fromDirPath(entry);
                } else if (entry.toString().endsWith(".zip")) {
                    gunPack = fromZipPath(entry);
                }
                if (gunPack != null) {
                    GunMod.LOGGER.info(MARKER, "- {}, Main namespace: {}", gunPack.path.getFileName(), gunPack.name);
                    gunPacks.add(gunPack);
                }
            }
        } catch (IOException e) {
            GunMod.LOGGER.error(MARKER, "Failed to scan extensions from {}. Error: {}", extensionsPath, e);
        }
        return gunPacks;
    }

    private static boolean modVersionAllMatch(PackMeta info) throws InvalidVersionSpecificationException {
        HashMap<String, String> dependencies = info.getDependencies();
        for (String modId : dependencies.keySet()) {
            if (!modVersionMatch(modId, dependencies.get(modId))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Charter 7.4: gun packs check {@code >=1.1.8}. Mod version uses {@code +} build metadata.
     * Strip {@code +...} before Maven compare so {@code 1.1.8+neoforge...} still satisfies {@code >=1.1.8}.
     */
    static boolean modVersionMatch(String modId, String version) throws InvalidVersionSpecificationException {
        VersionRange versionRange = parseGunPackRange(version);
        return ModList.get().getModContainerById(modId).map(mod -> {
            ArtifactVersion modVersion = stripBuildMetadata(mod.getModInfo().getVersion());
            return versionRange.containsVersion(modVersion);
        }).orElse(false);
    }

    static VersionRange parseGunPackRange(String spec) throws InvalidVersionSpecificationException {
        String trimmed = spec.trim();
        if (trimmed.startsWith(">=")) {
            trimmed = "[" + trimmed.substring(2).trim() + ",)";
        } else if (trimmed.startsWith(">")) {
            trimmed = "(" + trimmed.substring(1).trim() + ",)";
        }
        return VersionRange.createFromVersionSpec(trimmed);
    }

    static ArtifactVersion stripBuildMetadata(ArtifactVersion version) {
        String raw = version.toString();
        int plus = raw.indexOf('+');
        if (plus >= 0) {
            return new DefaultArtifactVersion(raw.substring(0, plus));
        }
        return version;
    }

    public record GunPack(Path path, String name) {
    }
}
