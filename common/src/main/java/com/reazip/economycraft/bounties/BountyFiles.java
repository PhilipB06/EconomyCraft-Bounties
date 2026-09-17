package com.reazip.economycraft.bounties;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class BountyFiles {
    private static final String ADDON_DIRECTORY = "economycraft_bounties";

    private BountyFiles() {
    }

    static Path configFile(MinecraftServer server) {
        return root(server).resolve("config.json");
    }

    static Path dataFile(MinecraftServer server) {
        Path data = root(server).resolve("data");
        createDirectories(data);
        return data.resolve("bounties.json");
    }

    static Path economyCraftConfigFile(MinecraftServer server) {
        return server.isDedicatedServer()
                ? server.getFile("config/economycraft/config.json")
                : server.getWorldPath(LevelResource.ROOT).resolve("economycraft/config.json");
    }

    static void writeAtomically(Path file, String json) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(json);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch (IOException e) {
            deleteQuietly(temporary, e);
            throw e;
        }
        try {
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temporary, e);
            throw e;
        }
    }

    private static Path root(MinecraftServer server) {
        Path root = server.isDedicatedServer()
                ? server.getFile("config/" + ADDON_DIRECTORY)
                : server.getWorldPath(LevelResource.ROOT).resolve(ADDON_DIRECTORY);
        createDirectories(root);
        return root;
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create directory " + path, e);
        }
    }

    private static void deleteQuietly(Path file, IOException failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
