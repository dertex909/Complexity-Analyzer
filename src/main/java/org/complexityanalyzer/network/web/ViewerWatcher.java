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

import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.util.ModFileManager;

import java.io.IOException;
import java.nio.file.*;

public final class ViewerWatcher {

    private static Thread watcherThread;
    private static volatile boolean running = false;

    private ViewerWatcher() {
    }

    public static synchronized void start() {
        var watchDirs = CabinResourceResolver.getWatchDirectories();
        if (watchDirs.isEmpty() || running) return;
        running = true;

        watcherThread = Thread.ofVirtual().name("Complexity-Viewer-Watcher").start(() -> {
            try (var watchService = FileSystems.getDefault().newWatchService()) {

                for (var dir : watchDirs) {
                    registerTree(dir, watchService);
                    ComplexityAnalyzer.LOGGER.info("[WebDev] Watching directory: {}", dir);
                }

                long lastReload = 0;

                while (running && !Thread.currentThread().isInterrupted()) {
                    var key = watchService.take();
                    var dir = (Path) key.watchable();

                    var shouldReload = false;
                    for (var event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        var context = (Path) event.context();
                        if (context == null) continue;
                        var fullPath = dir.resolve(context);

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && ModFileManager.isDirectory(fullPath)) {
                            registerTree(fullPath, watchService);
                            shouldReload = true;
                        }

                        var name = context.toString();
                        if (!name.startsWith(".") && !name.endsWith("~") && !name.endsWith(".tmp")) shouldReload = true;
                    }

                    var now = System.currentTimeMillis();
                    if (shouldReload && (now - lastReload > 250)) {
                        lastReload = now;
                        ComplexityAnalyzer.LOGGER.info("[WebDev] Change detected, invalidating cache and reloading browser...");
                        CabinResourceResolver.invalidateCache();
                        CabinWsHub.broadcast("reload");
                    }

                    key.reset();
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("[WebDev] Watcher error", e);
            }
        });
    }

    private static void registerTree(Path root, WatchService watchService) {
        try (var stream = Files.walk(root)) {
            stream.filter(ModFileManager::isDirectory).forEach(dir -> {
                try {
                    dir.register(watchService,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    public static synchronized void stop() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }
}