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
import org.complexityanalyzer.export.cabin.api.XxHash64;
import org.complexityanalyzer.export.cabin.io.CabinReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class ClientCabinStorage {
    private static final String DIR = "complexityanalyzer/cabin";
    private static final String FILE = "latest.cabin";
    private static final String HASH = "latest.cabin.hash";

    private ClientCabinStorage() {
    }

    public static Path getDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(DIR).resolve(id());
    }

    private static String id() {
        var mc = Minecraft.getInstance();
        ServerData s = mc.getCurrentServer();
        if (s != null && !s.ip.isEmpty()) return "mp-" +
                Long.toHexString(XxHash64.hashString(s.ip.toLowerCase(Locale.ROOT), 0xCAB17C7E50F47A1L));
        var ss = mc.getSingleplayerServer();
        if (ss != null) return "sp-" +
                Long.toHexString(XxHash64.hashString(ss.getWorldData().getLevelName().toLowerCase(Locale.ROOT), 0x5550CABE5EEDC0DEL));
        return "unknown";
    }

    public static long readKnownHash() {
        Path p = getDir().resolve(HASH);
        if (Files.isRegularFile(p)) try {
            String s = Files.readString(p, StandardCharsets.US_ASCII).trim();
            return Long.parseUnsignedLong(s.startsWith("0x") ? s.substring(2) : s, 16);
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static byte[] readCabin() {
        Path p = getDir().resolve(FILE);
        if (Files.isRegularFile(p)) try {
            return Files.readAllBytes(p);
        } catch (IOException ignored) {
        }
        return null;
    }

    public static boolean writeCabin(byte[] data, long hash) {
        try {
            Path d = getDir();
            Files.createDirectories(d);
            Path tmp = d.resolve(FILE + ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, d.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, d.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(d.resolve(HASH), Long.toHexString(hash), StandardCharsets.US_ASCII);
            return true;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Cabin] Failed to save", e);
            return false;
        }
    }

    public static boolean validate(byte[] data) {
        try {
            new CabinReader(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}