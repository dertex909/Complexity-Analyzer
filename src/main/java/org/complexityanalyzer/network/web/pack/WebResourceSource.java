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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

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

        private final Path zipPath;
        private final Object lock = new Object();
        private final Cache<String, byte[]> memoryCache = CacheBuilder.newBuilder()
                .maximumWeight(MAX_TOTAL_CACHE_WEIGHT)
                .weigher((String k, byte[] v) -> v.length)
                .build();
        private ZipFile zipFile;
        private volatile boolean closed = false;

        public ZipFileSource(Path zipPath) {
            this.zipPath = zipPath.toAbsolutePath().normalize();
        }

        private ZipFile getOrCreateZip() throws IOException {
            if (closed) return null;
            var zf = this.zipFile;
            if (zf != null) return zf;
            synchronized (lock) {
                if (closed) return null;
                if (this.zipFile == null) {
                    if (!FileManager.isRegularFile(this.zipPath)) return null;
                    this.zipFile = new ZipFile(this.zipPath.toFile(), StandardCharsets.UTF_8);
                }
                return this.zipFile;
            }
        }

        @Override
        public byte[] read(String subpath) {
            var clean = WebPackPaths.cleanPath(subpath);
            if (clean.isEmpty() || closed) return null;
            var cached = this.memoryCache.getIfPresent(clean);
            if (cached != null) return cached;

            try {
                var zip = getOrCreateZip();
                if (zip == null) return null;
                var entry = zip.getEntry(clean);
                if (entry == null || entry.isDirectory()) return null;
                try (var in = zip.getInputStream(entry)) {
                    var bytes = in.readAllBytes();
                    if (bytes.length <= MAX_CACHEABLE_FILE_SIZE) this.memoryCache.put(clean, bytes);
                    return bytes;
                }
            } catch (Exception ignored) {
                return null;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                closed = true;
                if (zipFile != null) {
                    try {
                        zipFile.close();
                    } catch (IOException ignored) {
                    }
                    zipFile = null;
                }
                memoryCache.invalidateAll();
            }
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