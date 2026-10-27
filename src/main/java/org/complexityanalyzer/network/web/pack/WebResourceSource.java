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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.util.FileManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

public sealed interface WebResourceSource {

    byte[] read(String subpath);

    default void close() {
    }

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
        private static final int MAX_CACHEABLE_FILE_SIZE = 512 * 1024;
        private static final long MAX_TOTAL_CACHE_WEIGHT = 8L * 1024L * 1024L;

        private final String archiveFileName;
        private final Cache<String, byte[]> memoryCache = CacheBuilder.newBuilder()
                .maximumWeight(MAX_TOTAL_CACHE_WEIGHT)
                .weigher((String k, byte[] v) -> v.length)
                .build();
        private volatile byte[] archiveBytes;

        public ZipFileSource(Path zipPath, byte[] archiveBytes) {
            var normalizedPath = zipPath.toAbsolutePath().normalize();
            this.archiveFileName = normalizedPath.getFileName().toString();
            this.archiveBytes = archiveBytes;
        }

        @Override
        public byte[] read(String subpath) {
            var clean = WebPackPaths.cleanPath(subpath);
            var archive = this.archiveBytes;
            if (clean.isEmpty() || archive == null) return null;
            if (clean.equals(this.archiveFileName)) return archive.clone();

            var cached = this.memoryCache.getIfPresent(clean);
            if (cached != null) return cached;

            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
                var entry = zip.getNextEntry();
                while (entry != null) {
                    if (!entry.isDirectory() && clean.equals(entry.getName())) {
                        var bytes = zip.readAllBytes();
                        if (bytes.length <= MAX_CACHEABLE_FILE_SIZE) this.memoryCache.put(clean, bytes);
                        return bytes;
                    }
                    entry = zip.getNextEntry();
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        @Override
        public void close() {
            this.archiveBytes = null;
            memoryCache.invalidateAll();
        }
    }

    final class CompositeSource implements WebResourceSource {
        private final ObjectList<WebResourceSource> delegates = new ObjectArrayList<>(2);

        public CompositeSource(WebResourceSource first, WebResourceSource second) {
            add(first);
            add(second);
        }

        public CompositeSource with(WebResourceSource additional) {
            add(additional);
            return this;
        }

        private void add(WebResourceSource s) {
            if (s instanceof CompositeSource cs) for (var d : cs.delegates) add(d);
            else if (s != null && !delegates.contains(s)) delegates.add(s);
        }

        @Override
        public byte[] read(String subpath) {
            for (var delegate : delegates) {
                var bytes = delegate.read(subpath);
                if (bytes != null) return bytes;
            }
            return null;
        }

        @Override
        public void close() {
            for (var delegate : delegates) {
                try {
                    delegate.close();
                } catch (Throwable ignored) {
                }
            }
            delegates.clear();
        }
    }
}