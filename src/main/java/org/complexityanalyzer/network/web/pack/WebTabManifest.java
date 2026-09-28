package org.complexityanalyzer.network.web.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.network.web.CabinNettyHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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

    private WebTabManifest() {
    }

    public static String normalizeNamespace(String raw) {
        return raw.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    public static JsonObject parse(InputStream in, String defaultNamespace, String token) {
        try {
            var elem = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (elem == null || !elem.isJsonObject()) return null;
            var obj = elem.getAsJsonObject();
            var namespace = normalizeNamespace(getString(obj, PROP_NAMESPACE, defaultNamespace));
            var id = getString(obj, PROP_ID, namespace);
            var title = getString(obj, PROP_TITLE, id);
            var order = getInt(obj, PROP_ORDER, DEFAULT_TAB_ORDER);

            obj.addProperty(PROP_NAMESPACE, namespace);
            obj.addProperty(PROP_ID, id);
            obj.addProperty(PROP_TITLE, title);
            obj.addProperty(PROP_ORDER, order);

            var basePath = "/%s/%s/%s/".formatted(token, CabinNettyHandler.PREFIX, namespace);
            resolveAssetUrls(obj, basePath);

            ComplexityAnalyzer.LOGGER.info("[WebPacks] Discovered tab: {} [{}] under namespace: {}", id, title, namespace);
            return obj;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Malformed {} descriptor encountered", TAB_FILE_SUFFIX, e);
            return null;
        }
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key)) return fallback;
        var val = obj.get(key).getAsString();
        return val.isBlank() ? fallback : val.trim();
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        return obj.has(key) ? obj.get(key).getAsInt() : fallback;
    }

    private static void resolveAssetUrls(JsonObject obj, String basePath) {
        if (obj.has(PROP_ENTRYPOINT)) {
            var clean = WebPackPaths.cleanPath(obj.get(PROP_ENTRYPOINT).getAsString());
            obj.addProperty(PROP_SCRIPT_URL, basePath + clean);
        }

        if (obj.has(PROP_STYLES) && obj.get(PROP_STYLES).isJsonArray()) {
            var resolved = new JsonArray();
            for (var elem : obj.getAsJsonArray(PROP_STYLES)) {
                var clean = WebPackPaths.cleanPath(elem.getAsString());
                resolved.add(basePath + clean);
            }
            obj.add(PROP_STYLES, resolved);
        }
    }
}