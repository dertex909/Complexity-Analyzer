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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.complexityanalyzer.network.web.CabinNettyHandler;
import org.complexityanalyzer.util.FileManager;

import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class CabinResourceResolver {

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

    public static void invalidateCache() {
        var old = cachedRegistry;
        cachedRegistry = null;
        if (old == null) return;
        for (var source : old.sources().values()) {
            try {
                source.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static byte[] resolveInternal(String path) {
        var devDir = WebPackPaths.getDevDir(WebPackPaths.VIEWER_BASE);
        if (devDir != null) {
            var clean = WebPackPaths.cleanPath(path);
            var diskPath = devDir.resolve(clean).normalize();
            if (FileManager.isRegularFile(diskPath)) try {
                return Files.readAllBytes(diskPath);
            } catch (Exception ignored) {
            }
        }

        try (var in = CabinResourceResolver.class.getResourceAsStream(WebPackPaths.VIEWER_BASE + path)) {
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

    public static String getExternalMimeType(String namespace, String subpath) {
        var registry = getOrBuildRegistry(CabinNettyHandler.getToken());
        var mimeTypes = registry.mimeTypesByNamespace().get(namespace);
        if (mimeTypes != null) {
            var normalizedPath = subpath.toLowerCase(Locale.ROOT);
            String matchedExtension = null;
            String matchedMimeType = null;

            for (var entry : mimeTypes.object2ObjectEntrySet()) {
                var extension = entry.getKey();
                if (normalizedPath.endsWith(extension) && (matchedExtension == null || extension.length() > matchedExtension.length())) {
                    matchedExtension = extension;
                    matchedMimeType = entry.getValue();
                }
            }

            if (matchedMimeType != null) return matchedMimeType;
        }

        return CabinNettyHandler.getMimeType(subpath);
    }

    public static String buildTabsJson(String token) {
        var registry = getOrBuildRegistry(token);
        var arr = new JsonArray();
        for (var tab : registry.tabs()) arr.add(tab);
        return arr.toString();
    }

    public static byte[] injectIndexHtml(byte[] rawHtmlBytes, String token) {
        var html = new String(rawHtmlBytes, UTF_8);
        var tabsJson = buildTabsJson(token);
        var injection = INJECTION_TEMPLATE.formatted(token, tabsJson);

        var modifiedHtml = html.contains(SCRIPT_TAG_MARKER)
                ? html.replace(SCRIPT_TAG_MARKER, injection + SCRIPT_TAG_MARKER)
                : html.contains(HEAD_CLOSE_TAG)
                ? html.replace(HEAD_CLOSE_TAG, injection + HEAD_CLOSE_TAG)
                : injection + html;

        return modifiedHtml.getBytes(UTF_8);
    }

    private static TabRegistry getOrBuildRegistry(String token) {
        var reg = cachedRegistry;
        if (reg != null) return reg;

        synchronized (CabinResourceResolver.class) {
            if (cachedRegistry != null) return cachedRegistry;
            var ctx = new WebPackScanContext(token);
            WebPackScanner.scanDirectory(WebPackPaths.getActivePacksDir(), ctx);
            var devPacks = WebPackPaths.getDevDir(WebPackPaths.PACKS_DIR_NAME);
            if (devPacks != null) WebPackScanner.scanDirectory(devPacks, ctx);
            WebPackScanner.scanLoadedMods(ctx);
            cachedRegistry = ctx.buildRegistry();
            return cachedRegistry;
        }
    }

    record TabRegistry(
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources,
            Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace
    ) {
    }
}