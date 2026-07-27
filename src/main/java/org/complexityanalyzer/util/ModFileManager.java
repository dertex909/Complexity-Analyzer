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
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

public final class ModFileManager {

    private static final FileVisitor<Path> DELETE_VISITOR = new SimpleFileVisitor<>() {
        @Override
        public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
            if (exc != null) throw exc;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
        }
    };

    private static volatile boolean supportsAtomicMove = true;

    private ModFileManager() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static @NotNull Path resolve(@NotNull MinecraftServer server, @Nullable String... relativePath) {
        Objects.requireNonNull(server, "MinecraftServer cannot be null for resolving path");
        return resolve(server.getWorldPath(LevelResource.ROOT), relativePath);
    }

    public static @NotNull Path resolve(@NotNull Path worldRoot, @Nullable String... relativePath) {
        Objects.requireNonNull(worldRoot, "worldRoot cannot be null");

        var baseDir = worldRoot.resolve("data").resolve(ComplexityAnalyzer.MODID).toAbsolutePath().normalize();
        var path = baseDir;

        if (relativePath != null) for (var element : relativePath) {
            if (element != null && !element.isEmpty()) path = path.resolve(element);
        }

        path = path.toAbsolutePath().normalize();

        if (!path.startsWith(baseDir)) throw new IllegalArgumentException("Path traversal attempt detected: " + path);
        return path;
    }

    public static void writeBytesAtomic(@NotNull Path target, byte @NotNull [] bytes) throws IOException {
        Objects.requireNonNull(target, "Target path cannot be null");
        Objects.requireNonNull(bytes, "Bytes cannot be null");

        ensureParentExists(target);
        var tmp = createTempFileInSameDir(target);

        try {
            Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            moveAtomic(tmp, target);
        } catch (Throwable t) {
            cleanupQuietly(tmp);
            throw t;
        }
    }

    public static void writeStringAtomic(@NotNull Path target, @NotNull String content) throws IOException {
        Objects.requireNonNull(content, "Content cannot be null");
        writeBytesAtomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeCompressedAtomic(@NotNull Path target, byte @NotNull [] uncompressedData, int zstdLevel) throws IOException {
        Objects.requireNonNull(target, "Target path cannot be null");
        Objects.requireNonNull(uncompressedData, "Uncompressed data cannot be null");

        ensureParentExists(target);
        var tmp = createTempFileInSameDir(target);

        try {
            try (var os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 var zstdOs = new ZstdOutputStream(os, zstdLevel)) {
                zstdOs.write(uncompressedData);
            }
            moveAtomic(tmp, target);
        } catch (Throwable t) {
            cleanupQuietly(tmp);
            throw t;
        }
    }

    public static byte[] readCompressedBytes(@NotNull Path source) throws IOException {
        Objects.requireNonNull(source, "Source path cannot be null");
        try (var is = Files.newInputStream(source); var zstdIs = new ZstdInputStream(is)) {
            return zstdIs.readAllBytes();
        }
    }

    public static @NotNull String readString(@NotNull Path source) throws IOException {
        Objects.requireNonNull(source, "Source path cannot be null");
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    // Тот самый метод readLines, возвращающий ObjectArrayList<String> без использования Stream API
    public static @NotNull ObjectArrayList<String> readLines(@Nullable Path source) throws IOException {
        var lines = new ObjectArrayList<String>();
        if (source == null || !Files.isRegularFile(source)) return lines;

        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    public static void appendLines(@NotNull Path target, @NotNull Iterable<String> lines) throws IOException {
        Objects.requireNonNull(target, "Target path cannot be null");
        Objects.requireNonNull(lines, "Lines cannot be null");
        ensureParentExists(target);
        Files.write(target, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static boolean exists(@Nullable Path path) {
        return path != null && Files.exists(path);
    }

    public static boolean isRegularFile(@Nullable Path path) {
        return path != null && Files.isRegularFile(path);
    }

    public static long getSize(@NotNull Path path) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        return Files.size(path);
    }

    public static @NotNull FileTime getLastModifiedTime(@NotNull Path path) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        return Files.getLastModifiedTime(path);
    }

    public static @NotNull ObjectArrayList<Path> list(@Nullable Path dir) throws IOException {
        var paths = new ObjectArrayList<Path>();
        if (dir == null || !Files.isDirectory(dir)) return paths;

        try (var ds = Files.newDirectoryStream(dir)) {
            for (var p : ds) paths.add(p);
        }
        return paths;
    }

    public static boolean delete(@Nullable Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(path, DELETE_VISITOR);
                return true;
            } else {
                return Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to delete path: {}", path, e);
            return false;
        }
    }

    private static Path createTempFileInSameDir(Path target) throws IOException {
        var parent = target.getParent();
        if (parent == null) parent = Path.of("");
        var fileName = target.getFileName();
        String prefix = (fileName != null ? fileName.toString() : "file") + ".tmp.";
        return Files.createTempFile(parent, prefix, null);
    }

    private static void moveAtomic(Path tmp, Path target) throws IOException {
        if (supportsAtomicMove) {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                supportsAtomicMove = false;
                ComplexityAnalyzer.LOGGER.warn("Atomic move not supported on this filesystem. Falling back to non-atomic replace.");
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void ensureParentExists(Path target) throws IOException {
        var parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private static void cleanupQuietly(@Nullable Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }
}