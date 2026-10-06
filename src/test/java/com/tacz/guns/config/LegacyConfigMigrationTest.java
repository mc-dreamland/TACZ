package com.tacz.guns.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyConfigMigrationTest {
    @TempDir
    Path directory;

    @Test
    void copiesExistingDebugSettingWithoutChangingLegacyFile() throws IOException {
        Path legacy = directory.resolve("tacz-pre.toml");
        Path target = directory.resolve("config/tacz-pre.toml");
        String config = "[gunpack]\nDefaultPackDebug = true\n";
        Files.writeString(legacy, config);

        assertTrue(LegacyConfigMigration.copyIfMissing(legacy, target));
        assertEquals(config, Files.readString(target));
        assertEquals(config, Files.readString(legacy));
    }

    @Test
    void preservesAnExistingNeoForgeConfiguration() throws IOException {
        Path legacy = directory.resolve("legacy.toml");
        Path target = directory.resolve("current.toml");
        Files.writeString(legacy, "legacy");
        Files.writeString(target, "current");

        assertFalse(LegacyConfigMigration.copyIfMissing(legacy, target));
        assertEquals("current", Files.readString(target));
    }

    @Test
    void doesNotCreateAConfigurationWhenNoLegacyFileExists() throws IOException {
        Path target = directory.resolve("config/current.toml");
        assertFalse(LegacyConfigMigration.copyIfMissing(directory.resolve("missing.toml"), target));
        assertFalse(Files.exists(target));
    }

    @Test
    void reportsCopyFailureSoCallerCanPreventDefaultPackReplacement() throws IOException {
        Path legacy = directory.resolve("legacy.toml");
        Path blockedDirectory = directory.resolve("config");
        Files.writeString(legacy, "DefaultPackDebug = true");
        Files.writeString(blockedDirectory, "a file blocks the target directory");

        assertThrows(IOException.class, () -> LegacyConfigMigration.copyIfMissing(
                legacy, blockedDirectory.resolve("current.toml")));
        assertEquals("DefaultPackDebug = true", Files.readString(legacy));
    }
}
