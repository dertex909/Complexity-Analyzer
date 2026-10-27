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
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.network.web.CabinNettyHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class WebTabManifest {
    public static final int DEFAULT_TAB_ORDER = 100;
    public static final String TAB_FILE_SUFFIX = ".tab.json";

    public static final String PROP_ID = "id";
    public static final String PROP_NAMESPACE = "namespace";
    public static final String PROP_TITLE = "title";
    public static final String PROP_ENTRYPOINT = "entrypoint";
    public static final String PROP_SCRIPT_URL = "scriptUrl";
    public static final String PROP_STYLES = "styles";
    public static final String PROP_ORDER = "order";
    public static final String PROP_MIME_TYPES = "mimeTypes";

    private WebTabManifest() {
    }

    private static void applySchema(JsonObject obj, String defaultNamespace) {
        ensure(obj, PROP_NAMESPACE, defaultNamespace);
        ensure(obj, PROP_ID, obj.get(PROP_NAMESPACE).getAsString());
        ensure(obj, PROP_TITLE, obj.get(PROP_ID).getAsString());
        ensure(obj, PROP_ORDER, DEFAULT_TAB_ORDER);
    }

    private static void resolveWebRoutes(JsonObject obj, String token) {
        var ns = normalizeNamespace(obj.get(PROP_NAMESPACE).getAsString());
        var base = "/%s/%s/%s/".formatted(token, CabinNettyHandler.PREFIX, ns);
        prefixUrl(obj, PROP_SCRIPT_URL, PROP_ENTRYPOINT, base);
        prefixUrls(obj, PROP_STYLES, base);
    }

    public static ParsedTab parse(InputStream in, String defaultNamespace, String token) {
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            var elem = JsonParser.parseReader(reader);
            if (elem == null || !elem.isJsonObject()) return null;
            var obj = elem.getAsJsonObject();
            applySchema(obj, defaultNamespace);
            var mimeTypes = parseMimeTypes(obj);
            obj.remove(PROP_MIME_TYPES);
            resolveWebRoutes(obj, token);

            ComplexityAnalyzer.LOGGER.info("[WebPacks] Discovered tab: {} [{}] under namespace: {}",
                    obj.get(PROP_ID).getAsString(), obj.get(PROP_TITLE).getAsString(), obj.get(PROP_NAMESPACE).getAsString());

            return new ParsedTab(obj, mimeTypes);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Malformed {} descriptor encountered", TAB_FILE_SUFFIX, e);
            return null;
        }
    }

    private static Object2ObjectOpenHashMap<String, String> parseMimeTypes(JsonObject obj) {
        var mimeTypes = new Object2ObjectOpenHashMap<String, String>();
        var element = obj.get(PROP_MIME_TYPES);
        if (element == null) return mimeTypes;
        if (!element.isJsonObject()) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] '{}' in {} must be a JSON object", PROP_MIME_TYPES, TAB_FILE_SUFFIX);
            return mimeTypes;
        }

        for (var entry : element.getAsJsonObject().entrySet()) {
            var extension = normalizeExtension(entry.getKey());
            var value = entry.getValue();
            if (extension == null || value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                ComplexityAnalyzer.LOGGER.warn("[WebPacks] Ignoring invalid MIME type entry '{}' in {}", entry.getKey(), TAB_FILE_SUFFIX);
                continue;
            }

            var mimeType = value.getAsString().trim();
            int slash = mimeType.indexOf('/');
            if (slash <= 0 || slash == mimeType.length() - 1 || mimeType.indexOf('\r') >= 0 || mimeType.indexOf('\n') >= 0) {
                ComplexityAnalyzer.LOGGER.warn("[WebPacks] Ignoring invalid MIME type '{}' for extension '{}'", mimeType, extension);
                continue;
            }

            var previous = mimeTypes.putIfAbsent(extension, mimeType);
            if (previous != null && !previous.equals(mimeType)) {
                ComplexityAnalyzer.LOGGER.warn("[WebPacks] Conflicting MIME types for extension '{}'; keeping '{}'", extension, previous);
            }
        }

        return mimeTypes;
    }

    private static String normalizeExtension(String extension) {
        if (extension == null) return null;
        var normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(".")) normalized = "." + normalized;
        if (normalized.length() < 2) return null;

        for (int i = 1; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_' && c != '+') return null;
        }
        return normalized;
    }

    public static String normalizeNamespace(String raw) {
        return raw.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    private static void ensure(JsonObject obj, String key, String defaultValue) {
        if (!obj.has(key) || obj.get(key).getAsString().isBlank()) obj.addProperty(key, defaultValue);
    }

    private static void ensure(JsonObject obj, String key, Number defaultValue) {
        if (!obj.has(key)) obj.addProperty(key, defaultValue);
    }

    private static void prefixUrl(JsonObject obj, String targetKey, String sourceKey, String base) {
        if (obj.has(sourceKey)) {
            var val = obj.get(sourceKey).getAsString().trim();
            if (!val.isBlank()) obj.addProperty(targetKey, base + WebPackPaths.cleanPath(val));
        }
    }

    private static void prefixUrls(JsonObject obj, String key, String base) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            var arr = new JsonArray();
            for (var elem : obj.getAsJsonArray(key)) arr.add(base + WebPackPaths.cleanPath(elem.getAsString()));
            obj.add(key, arr);
        }
    }

    public record ParsedTab(JsonObject tab, Object2ObjectOpenHashMap<String, String> mimeTypes) {
    }
}