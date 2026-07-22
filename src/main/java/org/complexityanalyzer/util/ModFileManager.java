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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.util;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

public final class ModFileManager {

    private static final FileVisitor<Path> DELETE_VISITOR = new SimpleFileVisitor<>() {
        @Override
        public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
        }
    };
    private static volatile boolean supportsAtomicMove = true;

    private ModFileManager() {
    }

    public static Path resolve(MinecraftServer server, String... relativePath) {
        if (server == null) throw new IllegalArgumentException("MinecraftServer cannot be null for resolving path");
        return resolve(server.getWorldPath(LevelResource.ROOT), relativePath);
    }

    public static Path resolve(Path worldRoot, String... relativePath) {
        var path = worldRoot.resolve("data").resolve(ComplexityAnalyzer.MODID);
        for (var element : relativePath) if (element != null && !element.isEmpty()) path = path.resolve(element);
        return path.toAbsolutePath().normalize();
    }

    public static void writeBytesAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        moveAtomic(tmp, target);
    }

    public static void writeStringAtomic(Path target, String content) throws IOException {
        writeBytesAtomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeCompressedAtomic(Path target, byte[] uncompressedData, int zstdLevel) throws IOException {
        Files.createDirectories(target.getParent());
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");

        try (var os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE); var zstdOs = new ZstdOutputStream(os, zstdLevel)) {
            zstdOs.write(uncompressedData);
        }

        moveAtomic(tmp, target);
    }

    public static byte[] readCompressedBytes(Path source) throws IOException {
        try (var is = Files.newInputStream(source); var zstdIs = new ZstdInputStream(is)) {
            return zstdIs.readAllBytes();
        }
    }

    public static String readString(Path source) throws IOException {
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    public static Stream<String> streamLines(Path source) throws IOException {
        if (!Files.exists(source)) return Stream.empty();
        return Files.lines(source, StandardCharsets.UTF_8);
    }

    public static void appendLines(Path target, Iterable<String> lines) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void moveAtomic(Path tmp, Path target) throws IOException {
        if (supportsAtomicMove) try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            supportsAtomicMove = false;
            ComplexityAnalyzer.LOGGER.warn("Atomic move not supported on this filesystem. Falling back to non-atomic replace.");
        } catch (Throwable t) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean exists(Path path) {
        return path != null && Files.exists(path);
    }

    public static boolean isRegularFile(Path path) {
        return path != null && Files.isRegularFile(path);
    }

    public static long getSize(Path path) throws IOException {
        return Files.size(path);
    }

    public static FileTime getLastModifiedTime(Path path) throws IOException {
        return Files.getLastModifiedTime(path);
    }

    public static Stream<Path> list(Path dir) throws IOException {
        if (!Files.exists(dir)) return Stream.empty();
        return Files.list(dir);
    }

    public static boolean delete(Path path) {
        if (path == null || !Files.exists(path)) return false;
        try {
            if (Files.isDirectory(path)) {
                Files.walkFileTree(path, DELETE_VISITOR);
                return true;
            } else {
                return Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            return false;
        }
    }
}