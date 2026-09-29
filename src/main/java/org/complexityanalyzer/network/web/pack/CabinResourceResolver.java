package org.complexityanalyzer.network.web.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.complexityanalyzer.network.web.CabinNettyHandler;
import org.complexityanalyzer.util.FileManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

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
        cachedRegistry = null;
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
            var seenTabIds = new ObjectOpenHashSet<String>();

            WebPackScanner.scanDirectory(WebPackPaths.getActivePacksDir(), tabs, sources, seenTabIds, token);
            var devPacks = WebPackPaths.getDevDir(WebPackPaths.PACKS_DIR_NAME);
            if (devPacks != null) WebPackScanner.scanDirectory(devPacks, tabs, sources, seenTabIds, token);
            WebPackScanner.scanLoadedMods(tabs, sources, seenTabIds, token);

            tabs.sort(Comparator.comparingInt(tab -> tab.has(WebTabManifest.PROP_ORDER)
                    ? tab.get(WebTabManifest.PROP_ORDER).getAsInt() : WebTabManifest.DEFAULT_TAB_ORDER));

            cachedRegistry = new TabRegistry(tabs, sources);
            return cachedRegistry;
        }
    }

    private record TabRegistry(
            ObjectArrayList<JsonObject> tabs,
            ConcurrentHashMap<String, WebResourceSource> sources
    ) {
    }
}