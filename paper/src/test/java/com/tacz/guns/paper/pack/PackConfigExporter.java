package com.tacz.guns.paper.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Build-time entry point: export the same validated defaults as a fresh plugin installation. */
public final class PackConfigExporter {
    private PackConfigExporter() { }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected an empty output directory");
        export(Path.of(arguments[0]));
    }

    static void export(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) throw new IOException("Refusing to export into a nonempty directory: " + directory);
            }
        }
        try (InputStream input = DefaultGunPack.class.getResourceAsStream("/default-pack.zip")) {
            if (input == null) throw new IOException("Bundled default-pack.zip is missing");
            new DefaultGunPack(null).load(input, directory);
        }
    }
}
