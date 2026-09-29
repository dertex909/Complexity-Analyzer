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

package org.complexityanalyzer.network.web.pack;

import org.complexityanalyzer.util.FileManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

public sealed interface WebResourceSource permits WebResourceSource.PathSource, WebResourceSource.ZipFileSource {

    byte[] read(String subpath);

    final class PathSource implements WebResourceSource {
        private final Path root;

        public PathSource(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public byte[] read(String subpath) {
            try {
                var clean = WebPackPaths.cleanPath(subpath);
                if (clean.isEmpty()) return null;
                var target = this.root.resolve(clean).normalize();
                if (!target.startsWith(this.root) || !FileManager.isRegularFile(target)) return null;
                return Files.readAllBytes(target);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    final class ZipFileSource implements WebResourceSource {
        private final Path zipPath;
        private final ConcurrentHashMap<String, byte[]> memoryCache = new ConcurrentHashMap<>();

        public ZipFileSource(Path zipPath) {
            this.zipPath = zipPath.toAbsolutePath().normalize();
        }

        @Override
        public byte[] read(String subpath) {
            var clean = WebPackPaths.cleanPath(subpath);
            if (clean.isEmpty()) return null;
            var cached = this.memoryCache.get(clean);
            if (cached != null) return cached;
            if (!FileManager.isRegularFile(this.zipPath)) return null;

            try (var zip = new ZipFile(this.zipPath.toFile(), StandardCharsets.UTF_8)) {
                var entry = zip.getEntry(clean);
                if (entry == null || entry.isDirectory()) return null;
                try (var in = zip.getInputStream(entry)) {
                    var bytes = in.readAllBytes();
                    this.memoryCache.put(clean, bytes);
                    return bytes;
                }
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}