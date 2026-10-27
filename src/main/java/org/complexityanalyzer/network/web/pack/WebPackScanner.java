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

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.util.FileManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

public final class WebPackScanner {
    private static final String ZIP_SUFFIX = ".zip";

    private WebPackScanner() {
    }

    public static void scanDirectory(
            Path dir,
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources,
            Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace,
            ObjectOpenHashSet<String> seenTabIds,
            String token
    ) {
        if (!FileManager.isDirectory(dir)) return;
        try {
            for (var packPath : FileManager.list(dir)) {
                var fileName = packPath.getFileName().toString();
                if (fileName.startsWith(".")) continue;

                if (fileName.endsWith(ZIP_SUFFIX) && FileManager.isRegularFile(packPath)) {
                    scanZipPack(packPath, tabs, sources, mimeTypesByNamespace, seenTabIds, token);
                } else if (FileManager.isDirectory(packPath)) {
                    scanDirectoryPack(packPath, tabs, sources, mimeTypesByNamespace, seenTabIds, token);
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[WebPacks] Error scanning packs directory: {}", dir, e);
        }
    }

    public static void scanLoadedMods(
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources,
            Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace,
            ObjectOpenHashSet<String> seenTabIds,
            String token
    ) {
        try {
            var modList = ModList.get();
            if (modList == null) return;

            for (var mod : modList.getMods()) {
                if (mod.getModId().equals(ComplexityAnalyzer.MODID)) continue;

                try {
                    var modFile = mod.getOwningFile().getFile();
                    var packsRoot = modFile.findResource(WebPackPaths.PACKS_DIR_NAME);
                    if (!FileManager.isDirectory(packsRoot)) continue;

                    ComplexityAnalyzer.LOGGER.debug("[WebPacks] Found mod pack resources for '{}': {}", mod.getModId(), packsRoot);
                    for (var packDir : FileManager.list(packsRoot)) {
                        if (FileManager.isDirectory(packDir)) {
                            scanDirectoryPack(packDir, tabs, sources, mimeTypesByNamespace, seenTabIds, token);
                        }
                    }
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.warn("[WebPacks] Failed to scan mod resources for mod: {}", mod.getModId(), e);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scanDirectoryPack(
            Path packDir,
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources,
            Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace,
            ObjectOpenHashSet<String> seenTabIds,
            String token
    ) {
        try {
            var defaultNs = WebTabManifest.normalizeNamespace(packDir.getFileName().toString());
            var source = new WebResourceSource.PathSource(packDir);

            for (var file : FileManager.list(packDir)) {
                if (FileManager.isRegularFile(file) && file.getFileName().toString().endsWith(WebTabManifest.TAB_FILE_SUFFIX)) {
                    try (var in = Files.newInputStream(file)) {
                        tryRegisterTab(in, defaultNs, token, source, tabs, sources, mimeTypesByNamespace, seenTabIds);
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Error scanning directory pack: {}", packDir, e);
        }
    }

    private static void scanZipPack(
            Path zipPath,
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources,
            Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace,
            ObjectOpenHashSet<String> seenTabIds,
            String token
    ) {
        try {
            var archiveBytes = Files.readAllBytes(zipPath);
            var fileName = zipPath.getFileName().toString();
            var baseName = fileName.endsWith(ZIP_SUFFIX) ? fileName.substring(0, fileName.length() - ZIP_SUFFIX.length()) : fileName;

            var defaultNs = WebTabManifest.normalizeNamespace(baseName);
            var source = new WebResourceSource.ZipFileSource(zipPath, archiveBytes);

            try (var zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes), StandardCharsets.UTF_8)) {
                var entry = zip.getNextEntry();
                while (entry != null) {
                    var name = entry.getName();
                    if (!entry.isDirectory() && name.endsWith(WebTabManifest.TAB_FILE_SUFFIX) && !name.contains("/")) {
                        var manifestBytes = zip.readAllBytes();
                        tryRegisterTab(new ByteArrayInputStream(manifestBytes), defaultNs, token, source,
                                tabs, sources, mimeTypesByNamespace, seenTabIds);
                    }
                    entry = zip.getNextEntry();
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Failed to read pack zip: {}", zipPath, e);
        }
    }

    private static void tryRegisterTab(
            InputStream in,
            String defaultNs,
            String token,
            WebResourceSource source,
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources,
            Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace,
            ObjectOpenHashSet<String> seenTabIds
    ) {
        var parsed = WebTabManifest.parse(in, defaultNs, token);
        if (parsed == null) return;
        var tab = parsed.tab();

        var id = tab.get(WebTabManifest.PROP_ID).getAsString();
        var ns = tab.get(WebTabManifest.PROP_NAMESPACE).getAsString();

        if (seenTabIds.add(id)) {
            sources.compute(ns, (key, existing) -> {
                if (existing == null) return source;
                if (existing.equals(source)) return existing;
                ComplexityAnalyzer.LOGGER.warn("[WebPacks] Namespace collision for '{}'! Merging sources to prevent 404s.", ns);
                if (existing instanceof WebResourceSource.CompositeSource composite) return composite.with(source);
                return new WebResourceSource.CompositeSource(existing, source);
            });
            registerMimeTypes(ns, parsed.mimeTypes(), mimeTypesByNamespace);
            tabs.add(tab);
        }
    }

    private static void registerMimeTypes(String namespace, Object2ObjectOpenHashMap<String, String> declaredMimeTypes,
                                          Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace) {
        if (declaredMimeTypes.isEmpty()) return;
        var namespaceMimeTypes = mimeTypesByNamespace.computeIfAbsent(namespace, ignored -> new Object2ObjectOpenHashMap<>());

        for (var entry : declaredMimeTypes.object2ObjectEntrySet()) {
            var previous = namespaceMimeTypes.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                ComplexityAnalyzer.LOGGER.warn("[WebPacks] Conflicting MIME types for namespace '{}' and extension '{}'; keeping '{}'",
                        namespace, entry.getKey(), previous);
            }
        }
    }
}