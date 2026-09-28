package org.complexityanalyzer.network.web.pack;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.util.FileManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

public final class WebPackScanner {
    private static final String ZIP_SUFFIX = ".zip";

    private WebPackScanner() {
    }

    public static void scanDirectory(Path dir, ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, ObjectOpenHashSet<String> seenTabIds, String token) {
        if (!FileManager.isDirectory(dir)) return;
        try {
            for (var packPath : FileManager.list(dir)) {
                var fileName = packPath.getFileName().toString();
                if (fileName.startsWith(".")) continue;

                if (fileName.endsWith(ZIP_SUFFIX) && FileManager.isRegularFile(packPath)) {
                    scanZipPack(packPath, tabs, sources, seenTabIds, token);
                } else if (FileManager.isDirectory(packPath)) {
                    scanDirectoryPack(packPath, tabs, sources, seenTabIds, token);
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[WebPacks] Error scanning packs directory: {}", dir, e);
        }
    }

    public static void scanLoadedMods(ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, ObjectOpenHashSet<String> seenTabIds, String token) {
        try {
            var modList = ModList.get();
            if (modList == null) return;

            for (var mod : modList.getMods()) {
                if (mod.getModId().equals(ComplexityAnalyzer.MODID)) continue;

                try {
                    var modFile = mod.getOwningFile().getFile();
                    var packsRoot = modFile.findResource(WebPackPaths.PACKS_DIR_NAME);
                    if (!FileManager.isDirectory(packsRoot)) continue;

                    for (var packDir : FileManager.list(packsRoot)) {
                        if (FileManager.isDirectory(packDir)) {
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

    private static void scanDirectoryPack(Path packDir, ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, ObjectOpenHashSet<String> seenTabIds, String token) {
        try {
            var defaultNs = WebTabManifest.normalizeNamespace(packDir.getFileName().toString());
            var source = new WebResourceSource.PathSource(packDir);

            for (var file : FileManager.list(packDir)) {
                if (FileManager.isRegularFile(file) && file.getFileName().toString().endsWith(WebTabManifest.TAB_FILE_SUFFIX)) {
                    try (var in = Files.newInputStream(file)) {
                        tryRegisterTab(in, defaultNs, token, source, tabs, sources, seenTabIds);
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebPacks] Error scanning directory pack: {}", packDir, e);
        }
    }

    private static void scanZipPack(Path zipPath, ObjectArrayList<JsonObject> tabs, ConcurrentHashMap<String, WebResourceSource> sources, ObjectOpenHashSet<String> seenTabIds, String token) {
        try (var zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            var defaultNs = WebTabManifest.normalizeNamespace(zipPath.getFileName().toString().replace(ZIP_SUFFIX, ""));
            var source = new WebResourceSource.ZipFileSource(zipPath);

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(WebTabManifest.TAB_FILE_SUFFIX) && !name.contains("/")) {
                    try (var in = zip.getInputStream(entry)) {
                        tryRegisterTab(in, defaultNs, token, source, tabs, sources, seenTabIds);
                    }
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
            ObjectOpenHashSet<String> seenTabIds
    ) {
        var tab = WebTabManifest.parse(in, defaultNs, token);
        if (tab == null) return;

        var id = tab.get(WebTabManifest.PROP_ID).getAsString();
        var ns = tab.get(WebTabManifest.PROP_NAMESPACE).getAsString();

        if (seenTabIds.add(id)) {
            sources.putIfAbsent(ns, source);
            tabs.add(tab);
        }
    }
}