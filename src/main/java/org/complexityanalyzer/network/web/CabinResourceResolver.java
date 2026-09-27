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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.util.ModFileManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipFile;

public final class CabinResourceResolver {

    private static final String VIEWER_BASE = "/assets/complexityanalyzer/viewer";
    private static final String TABS = "tabs";
    private static final String VIEWER_EXTERNAL_PREFIX = "viewer/";
    private static final String TABS_DIR = VIEWER_EXTERNAL_PREFIX + TABS;
    private static final String TABS_ZIP_PREFIX = TABS + "/";
    private static final String TAB_FILE_SUFFIX = ".tab.json";
    private static final String ZIP_SUFFIX = ".zip";

    private static final String PROP_ENTRYPOINT = "entrypoint";
    private static final String PROP_SCRIPT_URL = "scriptUrl";
    private static final String PROP_STYLES = "styles";
    private static final String PROP_ORDER = "order";
    private static final int DEFAULT_TAB_ORDER = 100;

    private static final String SCRIPT_TAG_MARKER = "<script src=\"js/app.js\"";
    private static final String HEAD_CLOSE_TAG = "</head>";

    private static final String PACKS_DIR_NAME = "complexity_packs";
    private static final Path PACKS_DIR = Path.of(PACKS_DIR_NAME);
    private static final Path RUN_PACKS_DIR = Path.of("run", PACKS_DIR_NAME);
    private static final Path DEV_PACKS_DIR = Path.of("..", PACKS_DIR_NAME);

    private static final String SRC_DIR = "src/main/resources" + VIEWER_BASE;

    private static final Path[] DEV_SEARCH_PATHS = {
            Path.of(SRC_DIR),
            Path.of("..", SRC_DIR)
    };

    private static final String INJECTION_TEMPLATE = """
            <script>
              window.__COMPLEXITY_TOKEN__ = "%s";
              window.__COMPLEXITY_TABS__ = %s;
            </script>
            """;

    private static Path cachedDevDir = null;
    private static boolean devDirChecked = false;

    private CabinResourceResolver() {
    }

    public static synchronized Path findDevDir() {
        if (devDirChecked) return cachedDevDir;
        devDirChecked = true;

        for (var path : DEV_SEARCH_PATHS) {
            if (Files.isDirectory(path)) {
                cachedDevDir = path.toAbsolutePath().normalize();
                ComplexityAnalyzer.LOGGER.info("[WebDev] Found dev directory at: {}", cachedDevDir);
                return cachedDevDir;
            }
        }

        return null;
    }

    public static byte[] resolveInternal(String path) {
        var devDir = findDevDir();
        if (devDir != null) {
            var rel = path.startsWith("/") ? path.substring(1) : path;
            var diskPath = devDir.resolve(rel).normalize();
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
        var packData = resolveFromCustomPacks(namespace, subpath);
        if (packData != null) return packData;

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;

        var loc = ResourceLocation.fromNamespaceAndPath(namespace, VIEWER_EXTERNAL_PREFIX + subpath);
        var resource = server.getResourceManager().getResource(loc).orElse(null);
        return readResourceBytes(resource);
    }

    public static String buildTabsJson(String token) {
        var tabs = new ObjectArrayList<JsonObject>();
        scanCustomPacks(tabs, token);

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            var resources = server.getResourceManager().listResources(TABS_DIR, id -> id.getPath().endsWith(TAB_FILE_SUFFIX));
            for (var entry : resources.entrySet()) {
                var tab = parseTabDescriptor(entry.getKey().getNamespace(), entry.getValue(), token);
                if (tab != null) tabs.add(tab);
            }
        }

        tabs.sort(Comparator.comparingInt(tab -> tab.has(PROP_ORDER) ? tab.get(PROP_ORDER).getAsInt() : DEFAULT_TAB_ORDER));

        var arr = new JsonArray();
        for (var tab : tabs) arr.add(tab);
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

    public static Path getActivePacksDir() {
        if (Files.isDirectory(PACKS_DIR)) return PACKS_DIR;
        if (Files.isDirectory(RUN_PACKS_DIR)) return RUN_PACKS_DIR;
        if (Files.isDirectory(DEV_PACKS_DIR)) return DEV_PACKS_DIR;

        try {
            return Files.createDirectories(PACKS_DIR);
        } catch (Exception ignored) {
            return PACKS_DIR;
        }
    }

    private static void scanCustomPacks(ObjectArrayList<JsonObject> tabs, String token) {
        var dir = getActivePacksDir();

        try {
            var files = ModFileManager.list(dir);
            for (var file : files) {
                String fileName = file.getFileName().toString();
                String namespace = fileName.replace(ZIP_SUFFIX, "").toLowerCase().replaceAll("[^a-z0-9_-]", "_");

                if (fileName.endsWith(ZIP_SUFFIX) && ModFileManager.isRegularFile(file)) {
                    try (var zip = new ZipFile(file.toFile())) {
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            if (entry.getName().endsWith(TAB_FILE_SUFFIX)) {
                                try (var in = zip.getInputStream(entry)) {
                                    var tab = parseTabStream(namespace, in, token);
                                    if (tab != null) tabs.add(tab);
                                }
                            }
                        }
                    } catch (Exception e) {
                        ComplexityAnalyzer.LOGGER.warn("Failed to read pack zip: {}", file, e);
                    }
                } else if (Files.isDirectory(file)) {
                    scanFolderForTabs(file, namespace, tabs, token);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void scanFolderForTabs(Path folder, String namespace, ObjectArrayList<JsonObject> tabs, String token) {
        try {
            for (var p : ModFileManager.list(folder)) {
                if (ModFileManager.isRegularFile(p) && p.toString().endsWith(TAB_FILE_SUFFIX)) {
                    try (var in = Files.newInputStream(p)) {
                        var tab = parseTabStream(namespace, in, token);
                        if (tab != null) tabs.add(tab);
                    }
                } else if (Files.isDirectory(p)) {
                    scanFolderForTabs(p, namespace, tabs, token);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] resolveFromCustomPacks(String namespace, String subpath) {
        var dir = getActivePacksDir();
        if (!Files.isDirectory(dir)) return null;

        var zipPath = dir.resolve(namespace + ZIP_SUFFIX);
        if (ModFileManager.isRegularFile(zipPath)) {
            try (var zip = new ZipFile(zipPath.toFile())) {
                var entry = zip.getEntry(subpath);
                if (entry == null) entry = zip.getEntry(TABS_ZIP_PREFIX + subpath);
                if (entry != null) try (var in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (Exception ignored) {
            }
        }

        var folderPath = dir.resolve(namespace);
        if (Files.isDirectory(folderPath)) {
            var target = folderPath.resolve(subpath);
            if (!ModFileManager.isRegularFile(target)) target = folderPath.resolve(TABS).resolve(subpath);
            if (ModFileManager.isRegularFile(target)) try {
                return Files.readAllBytes(target);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static byte[] readResourceBytes(Resource resource) {
        if (resource == null) return null;
        try (var in = resource.open()) {
            return in.readAllBytes();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject parseTabDescriptor(String namespace, Resource resource, String token) {
        try (var in = resource.open()) {
            return parseTabStream(namespace, in, token);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject parseTabStream(String namespace, InputStream in, String token) {
        try {
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var obj = JsonParser.parseString(content).getAsJsonObject();
            var basePath = "/%s/%s/%s/%s/".formatted(token, CabinNettyHandler.PREFIX, namespace, TABS);

            if (obj.has(PROP_ENTRYPOINT)) {
                var entry = cleanLeadingSlash(obj.get(PROP_ENTRYPOINT).getAsString());
                obj.addProperty(PROP_SCRIPT_URL, basePath + entry);
            }

            if (obj.has(PROP_STYLES) && obj.get(PROP_STYLES).isJsonArray()) {
                var resolvedStyles = new JsonArray();
                for (var style : obj.getAsJsonArray(PROP_STYLES)) {
                    var cleanStyle = cleanLeadingSlash(style.getAsString());
                    resolvedStyles.add(basePath + cleanStyle);
                }
                obj.add(PROP_STYLES, resolvedStyles);
            }

            if (!obj.has(PROP_ORDER)) obj.addProperty(PROP_ORDER, DEFAULT_TAB_ORDER);

            ComplexityAnalyzer.LOGGER.info("[WebTabs] Discovered tab: {} [{}] from namespace: {}",
                    obj.get("id"), obj.get("title"), namespace);

            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static String cleanLeadingSlash(String path) {
        if (path == null) return "";
        int i = 0;
        while (i < path.length() && path.charAt(i) == '/') i++;
        return path.substring(i);
    }
}