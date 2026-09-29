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

package org.complexityanalyzer.network.web.dev;

import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.network.web.pack.CabinResourceResolver;
import org.complexityanalyzer.network.web.pack.WebPackPaths;
import org.complexityanalyzer.network.web.ws.CabinWsHub;
import org.complexityanalyzer.util.FileManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

public final class ViewerWatcher {

    private static final AtomicReference<Thread> watcherRef = new AtomicReference<>();

    private ViewerWatcher() {
    }

    public static void start() {
        var watchDirs = WebPackPaths.getWatchDirectories();
        if (watchDirs.isEmpty() || watcherRef.get() != null) return;

        var thread = Thread.ofVirtual().name("Complexity-Viewer-Watcher").unstarted(() -> {
            try (var watchService = FileSystems.getDefault().newWatchService()) {

                for (var dir : watchDirs) {
                    registerTree(dir, watchService);
                    ComplexityAnalyzer.LOGGER.info("[WebDev] Watching directory: {}", dir);
                }

                long lastReload = 0;

                while (!Thread.currentThread().isInterrupted()) {
                    var key = watchService.take();
                    var dir = (Path) key.watchable();

                    var shouldReload = false;
                    for (var event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        var context = (Path) event.context();
                        if (context == null) continue;
                        var fullPath = dir.resolve(context);

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && FileManager.isDirectory(fullPath)) {
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
                        CabinWsHub.broadcastReload();
                    }

                    key.reset();
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("[WebDev] Watcher error", e);
            }
        });

        if (watcherRef.compareAndSet(null, thread)) thread.start();
    }

    private static void registerTree(Path root, WatchService watchService) {
        try (var stream = Files.walk(root)) {
            stream.filter(FileManager::isDirectory).forEach(dir -> {
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

    public static void stop() {
        var thread = watcherRef.getAndSet(null);
        if (thread != null) thread.interrupt();
    }
}