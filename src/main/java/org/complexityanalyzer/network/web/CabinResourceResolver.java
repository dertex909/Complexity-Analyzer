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

package org.complexityanalyzer.network.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.util.ModFileManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

public final class CabinResourceResolver {

    private static final int DEFAULT_TAB_ORDER = 100;
    private static final String PACKS_DIR_NAME = "complexity-web-packs";
    private static final String TAB_FILE_SUFFIX = ".tab.json";
    private static final String ZIP_SUFFIX = ".zip";

    private static final String PROP_ID = "id";
    private static final String PROP_NAMESPACE = "namespace";
    private static final String PROP_TITLE = "title";
    private static final String PROP_ENTRYPOINT = "entrypoint";
    private static final String PROP_SCRIPT_URL = "scriptUrl";
    private static final String PROP_STYLES = "styles";
    private static final String PROP_ORDER = "order";

    private static final String VIEWER_BASE = "/assets/complexityanalyzer/viewer";
    private static final String DEV_RESOURCES = "src/main/resources";

    private static final String SCRIPT_TAG_MARKER = "<script src=\"js/app.js\"";
    private static final String HEAD_CLOSE_TAG = "</head>";
    private static final String INJECTION_TEMPLATE = """
            <script>
              window.__COMPLEXITY_TOKEN__ = "%s";
              window.__COMPLEXITY_TABS__ = %s;
            </script>
            """;

    private static volatile TabRegistry cachedRegistry = null;

    private CabinResourceResolver() {
    }

    public static synchronized void invalidateCache() {
        cachedRegistry = null;
    }

    private static Path getDevRoot() {
        if (ModFileManager.isDirectory(Path.of(DEV_RESOURCES))) return Path.of("");
        if (ModFileManager.isDirectory(Path.of("..", DEV_RESOURCES))) return Path.of("..");
        return null;
    }

    private static Path getDevDir(String subPath) {
        var root = getDevRoot();
        if (root == null) return null;
        var path = root.resolve(DEV_RESOURCES).resolve(cleanPath(subPath)).toAbsolutePath().normalize();
        return ModFileManager.isDirectory(path) ? path : null;
    }

    public static Path getActivePacksDir() {
        var runDir = Path.of("run", PACKS_DIR_NAME);
        if (ModFileManager.isDirectory(runDir)) return runDir.toAbsolutePath().normalize();

        var rootDir = Path.of(PACKS_DIR_NAME);
        if (ModFileManager.isDirectory(rootDir)) return rootDir.toAbsolutePath().normalize();

        var target = ModFileManager.isDirectory(Path.of("run")) ? runDir : rootDir;
        try {
            return Files.createDirectories(target).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return target.toAbsolutePath().normalize();
        }
    }

    public static ObjectArrayList<Path> getWatchDirectories() {
        var list = new ObjectArrayList<Path>();

        var uiDir = getDevDir(VIEWER_BASE);
        if (uiDir != null) list.add(uiDir);

        var devPacks = getDevDir(PACKS_DIR_NAME);
        if (devPacks != null) list.add(devPacks);

        var diskPacks = getActivePacksDir();
        if (ModFileManager.isDirectory(diskPacks) && !list.contains(diskPacks)) list.add(diskPacks);

        return list;
    }

    public static byte[] resolveInternal(String path) {
        var devDir = getDevDir(VIEWER_BASE);
        if (devDir != null) {
            var clean = cleanPath(path);
            var diskPath = devDir.resolve(clean).normalize();
            if (ModFileManager.isRegularFile(diskPath)) try {
                return Files.readAllBytes(diskPath);
            } catch (Exception ignored) {
            }
        }

        try (var in = CabinResourceResolver.class.getResourceAsStream(VIEWER_BASE + path)) {
            return in != null ? in.readAllBytes() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static byte[] resolveExternal(String namespace, String subpath) {
        if (namespace == null || namespace.isEmpty() || subpath == null || subpath.isEmpty()) return null;
        var registry = getOrBuildRegistry(CabinNettyHandler.getToken());
        var source = registry.sources().get(namespace);
        if (source == null) return null;
        return source.read(subpath);
    }

    public static String buildTabsJson(String token) {
        var registry = getOrBuildRegistry(token);
        var arr = new JsonArray();
        for (var tab : registry.tabs()) arr.add(tab);
        return arr.toString();
    }

    public static byte[] injectIndexHtml(byte[] rawHtmlBytes, String token) {
        var html = new String(rawHtmlBytes, StandardCharsets.UTF_8);
        var tabsJson = buildTabsJson(token);
        var injection = INJECTION_TEMPLATE.formatted(token, tabsJson);

        var modifiedHtml = html.contains(SCRIPT_TAG_MARKER)
                ? html.replace(SCRIPT_TAG_MARKER, injection + SCRIPT_TAG_MARKER)
                : html.contains(HEAD_CLOSE_TAG)
                ? html.replace(HEAD_CLOSE_TAG, injection + HEAD_CLOSE_TAG)
                : injection + html;

        return modifiedHtml.getBytes(StandardCharsets.UTF_8);
    }

    private static TabRegistry getOrBuildRegistry(String token) {
        var reg = cachedRegistry;
        if (reg != null) return reg;

        synchronized (CabinResourceResolver.class) {
            if (cachedRegistry != null) return cachedRegistry;

            var tabs = new ObjectArrayList<JsonObject>();
            var sources = new ConcurrentHashMap<String, WebResourceSource>();
            var seenTabIds = new HashSet<String>();

            scanDirectory(getActivePacksDir(), tabs, sources, seenTabIds, token);
            var devPacks = getDevDir(PACKS_DIR_NAME);
            if (devPacks != null) scanDirectory(devPacks, tabs, sources, seenTabIds, token);
            scanLoadedMods(tabs, sources, seenTabIds, token);
            tabs.sort(Comparator.comparingInt(tab -> tab.has(PROP_ORDER) ? tab.get(PROP_ORDER).getAsInt() : DEFAULT_TAB_ORDER));

            cachedRegistry = new TabRegistry(tabs, sources);
            return cachedRegistry;
        }
    }

    private static void scanDirectory(Path dir, ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, HashSet<String> seenTabIds, String token) {
        if (!ModFileManager.isDirectory(dir)) return;
        try {
            for (var packPath : ModFileManager.list(dir)) {
                var fileName = packPath.getFileName().toString();
                if (fileName.startsWith(".")) continue;

                if (fileName.endsWith(ZIP_SUFFIX) && ModFileManager.isRegularFile(packPath)) {
                    scanZipPack(packPath, tabs, sources, seenTabIds, token);
                } else if (ModFileManager.isDirectory(packPath)) {
                    scanDirectoryPack(packPath, tabs, sources, seenTabIds, token);
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[WebPacks] Error scanning packs directory: {}", dir, e);
        }
    }

    private static void scanLoadedMods(ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, HashSet<String> seenTabIds, String token) {
        try {
            var modList = ModList.get();
            if (modList == null) return;

            for (var mod : modList.getMods()) {
                if (mod.getModId().equals(ComplexityAnalyzer.MODID)) continue;

                try {
                    var modFile = mod.getOwningFile().getFile();
                    var packsRoot = modFile.findResource(PACKS_DIR_NAME);
                    if (!ModFileManager.isDirectory(packsRoot)) continue;

                    for (var packDir : ModFileManager.list(packsRoot)) {
                        if (ModFileManager.isDirectory(packDir)) {
                            scanDirectoryPack(packDir, tabs, sources, seenTabIds, token);
                        }
                    }
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.warn("[WebPacks] Failed to scan mod resources for mod: {}", mod.getModId(), e);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scanDirectoryPack(Path packDir, ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, HashSet<String> seenTabIds, String token) {
        try {
            var defaultNs = packDir.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
            for (var file : ModFileManager.list(packDir)) {
                if (ModFileManager.isRegularFile(file) && file.getFileName().toString().endsWith(TAB_FILE_SUFFIX)) {
                    try (var in = Files.newInputStream(file)) {
                        var tab = parseTabStream(in, defaultNs, token);
                        if (tab != null) {
                            var id = tab.get(PROP_ID).getAsString();
                            var ns = tab.get(PROP_NAMESPACE).getAsString();
                            if (seenTabIds.add(id)) {
                                sources.putIfAbsent(ns, new PathSource(packDir));
                                tabs.add(tab);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Error scanning directory pack: {}", packDir, e);
        }
    }

    private static void scanZipPack(Path zipPath, ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, HashSet<String> seenTabIds, String token) {
        try (var zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            var defaultNs = zipPath.getFileName().toString().replace(ZIP_SUFFIX, "").toLowerCase().replaceAll("[^a-z0-9_-]", "_");

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(TAB_FILE_SUFFIX) && !name.contains("/")) {
                    try (var in = zip.getInputStream(entry)) {
                        var tab = parseTabStream(in, defaultNs, token);
                        if (tab != null) {
                            var id = tab.get(PROP_ID).getAsString();
                            var ns = tab.get(PROP_NAMESPACE).getAsString();
                            if (seenTabIds.add(id)) {
                                sources.putIfAbsent(ns, new ZipFileSource(zipPath));
                                tabs.add(tab);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Failed to read pack zip: {}", zipPath, e);
        }
    }

    private static JsonObject parseTabStream(InputStream in, String defaultNamespace, String token) {
        try {
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var obj = JsonParser.parseString(content).getAsJsonObject();

            var namespace = obj.has(PROP_NAMESPACE) && !obj.get(PROP_NAMESPACE).getAsString().isBlank()
                    ? obj.get(PROP_NAMESPACE).getAsString().trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_")
                    : defaultNamespace;
            obj.addProperty(PROP_NAMESPACE, namespace);

            if (!obj.has(PROP_ID) || obj.get(PROP_ID).getAsString().isBlank()) obj.addProperty(PROP_ID, namespace);
            if (!obj.has(PROP_TITLE) || obj.get(PROP_TITLE).getAsString().isBlank())
                obj.addProperty(PROP_TITLE, obj.get(PROP_ID).getAsString());
            if (!obj.has(PROP_ORDER)) obj.addProperty(PROP_ORDER, DEFAULT_TAB_ORDER);

            var basePath = "/%s/%s/%s/".formatted(token, CabinNettyHandler.PREFIX, namespace);

            if (obj.has(PROP_ENTRYPOINT)) {
                var cleanEntry = cleanPath(obj.get(PROP_ENTRYPOINT).getAsString());
                obj.addProperty(PROP_SCRIPT_URL, basePath + cleanEntry);
            }

            if (obj.has(PROP_STYLES) && obj.get(PROP_STYLES).isJsonArray()) {
                var resolvedStyles = new JsonArray();
                for (var styleElem : obj.getAsJsonArray(PROP_STYLES)) {
                    var cleanStyle = cleanPath(styleElem.getAsString());
                    resolvedStyles.add(basePath + cleanStyle);
                }
                obj.add(PROP_STYLES, resolvedStyles);
            }

            ComplexityAnalyzer.LOGGER.info("[WebPacks] Discovered tab: {} [{}] under namespace: {}",
                    obj.get(PROP_ID).getAsString(), obj.get(PROP_TITLE).getAsString(), namespace);

            return obj;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Malformed .tab.json descriptor encountered", e);
            return null;
        }
    }

    private static String cleanPath(String path) {
        if (path == null || path.isEmpty()) return "";
        var normalized = path.replace('\\', '/');
        var i = 0;
        while (i < normalized.length() && normalized.charAt(i) == '/') i++;
        return normalized.substring(i);
    }

    public sealed interface WebResourceSource permits PathSource, ZipFileSource {
        byte[] read(String subpath);
    }

    public static final class PathSource implements WebResourceSource {
        private final Path root;

        public PathSource(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public byte[] read(String subpath) {
            try {
                var clean = cleanPath(subpath);
                if (clean.isEmpty()) return null;
                var target = this.root.resolve(clean).normalize();
                if (!target.startsWith(this.root) || !ModFileManager.isRegularFile(target)) return null;
                return Files.readAllBytes(target);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static final class ZipFileSource implements WebResourceSource {
        private final Path zipPath;

        public ZipFileSource(Path zipPath) {
            this.zipPath = zipPath.toAbsolutePath().normalize();
        }

        @Override
        public byte[] read(String subpath) {
            var clean = cleanPath(subpath);
            if (clean.isEmpty() || !ModFileManager.isRegularFile(this.zipPath)) return null;
            try (var zip = new ZipFile(this.zipPath.toFile())) {
                var entry = zip.getEntry(clean);
                if (entry == null || entry.isDirectory()) return null;
                try (var in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private record TabRegistry(
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources
    ) {
    }
}