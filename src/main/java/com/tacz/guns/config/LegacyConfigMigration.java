package com.tacz.guns.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies pre-NeoForge configuration without replacing either existing file. */
final class LegacyConfigMigration {
    private LegacyConfigMigration() {
    }

    static boolean copyIfMissing(Path legacy, Path target) throws IOException {
        if (Files.exists(target) || Files.notExists(legacy)) {
            return false;
        }
        Files.createDirectories(target.getParent());
        try {
            Files.copy(legacy, target);
            return true;
        } catch (FileAlreadyExistsException ignored) {
            // Another instance may have created the target after the initial check.
            return false;
        }
    }
}
