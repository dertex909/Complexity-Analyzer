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