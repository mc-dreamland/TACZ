package com.tacz.guns.config;

import com.tacz.guns.GunMod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.file.Path;

public class PreLoadConfig {
    public static ModConfigSpec spec;
    public static ModConfigSpec.BooleanValue override;
    private static boolean legacyMigrationFailed;

    /** Preserve the old gun-pack debug setting before NeoForge loads the startup config. */
    public static void migrateLegacyConfig() {
        Path legacy = FMLPaths.GAMEDIR.get().resolve("tacz").resolve("tacz-pre.toml");
        Path target = FMLPaths.CONFIGDIR.get().resolve("tacz-pre.toml");
        try {
            if (LegacyConfigMigration.copyIfMissing(legacy, target)) {
                GunMod.LOGGER.info("Migrated TACZ startup configuration from {} to {}", legacy, target);
            }
        } catch (IOException | SecurityException exception) {
            legacyMigrationFailed = true;
            GunMod.LOGGER.error("Could not migrate TACZ startup configuration from {} to {}. "
                    + "Default gun-pack replacement is disabled for this launch to preserve local changes.",
                    legacy, target, exception);
        }
    }

    public static boolean shouldOverwriteDefaultPack() {
        return !legacyMigrationFailed && !override.get();
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("gunpack");
        builder.comment("When enabled, the mod will not try to overwrite the default pack under .minecraft/tacz");
        override = builder.define("DefaultPackDebug", false);
        builder.pop();
        spec = builder.build();
    }
}
