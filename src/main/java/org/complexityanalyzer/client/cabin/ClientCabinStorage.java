/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.client.cabin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.export.cabin.CabinReader;
import org.complexityanalyzer.export.cabin.XxHash64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Resolves on-disk paths for cached .cabin snapshots and exposes the latest known
 * file hash so the client can short-circuit redundant downloads.
 * <p>
 * Layout:
 * <pre>
 *   &lt;minecraft&gt;/complexityanalyzer/cabin/&lt;server-id&gt;/latest.cabin
 *   &lt;minecraft&gt;/complexityanalyzer/cabin/&lt;server-id&gt;/latest.cabin.hash
 * </pre>
 * {@code server-id} is a stable hex hash of the server address (or "singleplayer-NAME"
 * when not connected), so different servers do not overwrite each other's caches.
 */
public final class ClientCabinStorage {

    private static final String ROOT_DIR = "complexityanalyzer";
    private static final String CABIN_SUBDIR = "cabin";
    private static final String CABIN_FILE = "latest.cabin";
    private static final String HASH_FILE = "latest.cabin.hash";

    private ClientCabinStorage() {
    }

    public static Path getRootDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve(ROOT_DIR)
                .resolve(CABIN_SUBDIR)
                .toAbsolutePath()
                .normalize();
    }

    public static Path getServerDir() {
        return getRootDir().resolve(currentServerId());
    }

    public static Path getCabinPath() {
        return getServerDir().resolve(CABIN_FILE);
    }

    public static Path getHashPath() {
        return getServerDir().resolve(HASH_FILE);
    }

    private static final long SEED_MP = 0xCAB17C7_E50F47A1L;
    private static final long SEED_SP = 0x5550CABE_5EEDC0DEL;

    public static String currentServerId() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        if (server != null && !server.ip.isEmpty()) {
            long hash = XxHash64.hashString(server.ip.toLowerCase(Locale.ROOT), SEED_MP);
            return "mp-" + Long.toHexString(hash);
        }
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            String name = mc.getSingleplayerServer().getWorldData().getLevelName();
            long hash = XxHash64.hashString(name.toLowerCase(Locale.ROOT), SEED_SP);
            return "sp-" + Long.toHexString(hash);
        }
        return "unknown";
    }

    public static long readKnownHash() {
        Path hashPath = getHashPath();
        if (!Files.isRegularFile(hashPath)) return 0L;
        try {
            String s = Files.readString(hashPath, StandardCharsets.US_ASCII).trim();
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
            return Long.parseUnsignedLong(s, 16);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Cabin] Failed to read hash file {}: {}", hashPath, t.getMessage());
            return 0L;
        }
    }

    public static byte[] readCabin() {
        Path p = getCabinPath();
        if (!Files.isRegularFile(p)) return null;
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.warn("[Cabin] Failed to read {}: {}", p, e.getMessage());
            return null;
        }
    }

    public static boolean writeCabin(byte[] data, long fileHash) {
        try {
            Path dir = getServerDir();
            Files.createDirectories(dir);
            Path tmp = dir.resolve(CABIN_FILE + ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, dir.resolve(CABIN_FILE),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable atomic) {
                Files.move(tmp, dir.resolve(CABIN_FILE), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(dir.resolve(HASH_FILE),
                    Long.toHexString(fileHash) + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
            return true;
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error("[Cabin] Failed to persist cabin", t);
            return false;
        }
    }

    public static boolean validate(byte[] data) {
        try {
            new CabinReader(data);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
