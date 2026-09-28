package org.complexityanalyzer.network.web.pack;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.complexityanalyzer.util.FileManager;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WebPackPaths {
    public static final String PACKS_DIR_NAME = "complexity-web-packs";
    public static final String VIEWER_BASE = "/assets/complexityanalyzer/viewer";
    private static final String DEV_RESOURCES = "src/main/resources";
    private static final String RUN = "run";

    private WebPackPaths() {
    }

    public static Path getDevRoot() {
        if (FileManager.isDirectory(Path.of(DEV_RESOURCES))) return Path.of("");
        if (FileManager.isDirectory(Path.of("..", DEV_RESOURCES))) return Path.of("..");
        return null;
    }

    public static Path getDevDir(String subPath) {
        var root = getDevRoot();
        if (root == null) return null;
        var path = root.resolve(DEV_RESOURCES).resolve(cleanPath(subPath)).toAbsolutePath().normalize();
        return FileManager.isDirectory(path) ? path : null;
    }

    public static Path getActivePacksDir() {
        var runDir = Path.of(RUN, PACKS_DIR_NAME);
        if (FileManager.isDirectory(runDir)) return runDir.toAbsolutePath().normalize();

        var rootDir = Path.of(PACKS_DIR_NAME);
        if (FileManager.isDirectory(rootDir)) return rootDir.toAbsolutePath().normalize();

        var target = FileManager.isDirectory(Path.of(RUN)) ? runDir : rootDir;
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
        if (FileManager.isDirectory(diskPacks) && !list.contains(diskPacks)) list.add(diskPacks);

        return list;
    }

    public static String cleanPath(String path) {
        if (path == null || path.isEmpty()) return "";
        var normalized = path.replace('\\', '/');
        var i = 0;
        while (i < normalized.length() && normalized.charAt(i) == '/') i++;
        return normalized.substring(i);
    }
}