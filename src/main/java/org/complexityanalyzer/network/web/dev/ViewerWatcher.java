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

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.network.web.pack.CabinResourceResolver;
import org.complexityanalyzer.network.web.pack.WebPackPaths;
import org.complexityanalyzer.network.web.ws.CabinWsHub;
import org.complexityanalyzer.util.FileManager;
import org.complexityanalyzer.util.Scope;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.file.StandardWatchEventKinds.*;

public final class ViewerWatcher {

    private static final long RELOAD_QUIET_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long WATCH_POLL_INTERVAL_MILLIS = 50;
    private static final WatchEvent.Kind<?>[] WATCH_EVENT_KINDS = {ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE};

    private static final AtomicReference<Thread> watcherRef = new AtomicReference<>();

    private ViewerWatcher() {
    }

    public static void start() {
        var roots = WebPackPaths.getWatchDirectories();
        if (roots.isEmpty()) {
            ComplexityAnalyzer.LOGGER.warn("[WebDev] No filesystem directories are available for hot reload.");
            return;
        }

        if (WebPackPaths.getDevDir(WebPackPaths.VIEWER_BASE) == null) {
            ComplexityAnalyzer.LOGGER.debug("[WebDev] No source viewer directory found; packaged viewer assets are served without a filesystem watcher.");
        }

        var thread = Thread.ofVirtual().name("Complexity-Viewer-Watcher").unstarted(() -> watch(roots));
        if (!watcherRef.compareAndSet(null, thread)) return;

        try {
            thread.start();
        } catch (RuntimeException e) {
            watcherRef.compareAndSet(thread, null);
            throw e;
        }
    }

    private static void watch(ObjectArrayList<Path> requestedRoots) {
        try (var ignored = Scope.of(() -> watcherRef.compareAndSet(Thread.currentThread(), null));
             var watchService = FileSystems.getDefault().newWatchService()) {
            var roots = normalizeRoots(requestedRoots);
            var rootPaths = new ObjectOpenHashSet<Path>();
            var rootParentPaths = new ObjectOpenHashSet<Path>();
            var directoryKeys = new Object2ObjectOpenHashMap<Path, WatchKey>();

            rootPaths.addAll(roots);
            registerRoots(roots, rootParentPaths, directoryKeys, watchService);

            long reloadDeadline = 0L;
            while (!Thread.currentThread().isInterrupted()) {
                var key = watchService.poll(WATCH_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (key != null) {
                    var result = processEvents(key, rootPaths, rootParentPaths, directoryKeys, watchService);
                    var watchedDirectory = normalize((Path) key.watchable());

                    if (!key.reset()) {
                        directoryKeys.remove(watchedDirectory);
                        ComplexityAnalyzer.LOGGER.debug("[WebDev] Directory watch expired: {}", watchedDirectory);
                    }

                    if (result.overflow()) rebuildRegistrations(roots, rootParentPaths, directoryKeys, watchService);
                    if (result.changed()) reloadDeadline = System.nanoTime() + RELOAD_QUIET_PERIOD_NANOS;
                }

                if (reloadDeadline != 0L && System.nanoTime() - reloadDeadline >= 0L) {
                    reloadDeadline = 0L;
                    reloadViewer();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[WebDev] Watcher stopped after an error.", e);
        }
    }

    private static ObjectArrayList<Path> normalizeRoots(ObjectArrayList<Path> requestedRoots) {
        var roots = new ObjectArrayList<Path>(requestedRoots.size());
        var seen = new ObjectOpenHashSet<Path>();

        for (var requestedRoot : requestedRoots) {
            var root = normalize(requestedRoot);
            if (seen.add(root)) roots.add(root);
        }

        return roots;
    }

    private static void registerRoots(ObjectArrayList<Path> roots, ObjectOpenHashSet<Path> rootParentPaths,
                                      Object2ObjectOpenHashMap<Path, WatchKey> directoryKeys, WatchService watchService) {
        for (var root : roots) {
            ComplexityAnalyzer.LOGGER.info("[WebDev] Watching filesystem root: {}", root);
            var parent = root.getParent();
            if (parent != null && rootParentPaths.add(parent)) registerDirectory(parent, directoryKeys, watchService);
            registerTree(root, directoryKeys, watchService);
        }
    }

    private static void registerTree(Path root, Object2ObjectOpenHashMap<Path, WatchKey> directoryKeys, WatchService watchService) {
        if (!FileManager.isDirectory(root)) {
            ComplexityAnalyzer.LOGGER.debug("[WebDev] Watch root is not present yet: {}", root);
            return;
        }

        FileManager.walkDirectories(root, directory -> registerDirectory(directory, directoryKeys, watchService));
    }

    private static void registerDirectory(Path directory, Object2ObjectOpenHashMap<Path, WatchKey> directoryKeys, WatchService watchService) {
        var normalizedDirectory = normalize(directory);
        var existingKey = directoryKeys.get(normalizedDirectory);
        if (existingKey != null && existingKey.isValid()) return;
        if (existingKey != null) directoryKeys.remove(normalizedDirectory);

        try {
            var key = normalizedDirectory.register(watchService, WATCH_EVENT_KINDS);
            directoryKeys.put(normalizedDirectory, key);
            ComplexityAnalyzer.LOGGER.debug("[WebDev] Registered directory: {}", normalizedDirectory);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[WebDev] Could not watch directory: {}", normalizedDirectory, e);
        }
    }

    private static WatchResult processEvents(WatchKey key, ObjectOpenHashSet<Path> rootPaths, ObjectOpenHashSet<Path> rootParentPaths,
                                             Object2ObjectOpenHashMap<Path, WatchKey> directoryKeys, WatchService watchService) {
        var watchedDirectory = normalize((Path) key.watchable());
        var isRootParent = rootParentPaths.contains(watchedDirectory);
        var changed = false;
        var overflow = false;

        for (var event : key.pollEvents()) {
            if (event.kind() == OVERFLOW) {
                ComplexityAnalyzer.LOGGER.warn("[WebDev] Filesystem event queue overflowed; rebuilding directory watches.");
                changed = true;
                overflow = true;
                continue;
            }

            if (!(event.context() instanceof Path relativePath)) continue;
            var changedPath = normalize(watchedDirectory.resolve(relativePath));
            if (isRootParent && !rootPaths.contains(changedPath)) continue;

            if (event.kind() == ENTRY_CREATE && FileManager.isDirectory(changedPath)) {
                registerTree(changedPath, directoryKeys, watchService);
            } else if (event.kind() == ENTRY_DELETE) {
                cancelTree(changedPath, directoryKeys);
            }

            ComplexityAnalyzer.LOGGER.debug("[WebDev] Filesystem event {}: {}", event.kind().name(), changedPath);
            changed = true;
        }

        return new WatchResult(changed, overflow);
    }

    private static void cancelTree(Path deletedPath, Object2ObjectOpenHashMap<Path, WatchKey> directoryKeys) {
        var deletedDirectories = new ObjectArrayList<Path>();
        for (var watchedDirectory : directoryKeys.keySet()) {
            if (watchedDirectory.equals(deletedPath) || watchedDirectory.startsWith(deletedPath)) {
                deletedDirectories.add(watchedDirectory);
            }
        }

        for (var deletedDirectory : deletedDirectories) {
            var key = directoryKeys.remove(deletedDirectory);
            if (key != null) key.cancel();
        }
    }

    private static void rebuildRegistrations(ObjectArrayList<Path> roots, ObjectOpenHashSet<Path> rootParentPaths,
                                             Object2ObjectOpenHashMap<Path, WatchKey> directoryKeys, WatchService watchService) {
        for (var key : directoryKeys.values()) key.cancel();
        directoryKeys.clear();
        rootParentPaths.clear();
        ComplexityAnalyzer.LOGGER.debug("[WebDev] Rebuilding recursive directory watches.");
        registerRoots(roots, rootParentPaths, directoryKeys, watchService);
    }

    private static void reloadViewer() {
        ComplexityAnalyzer.LOGGER.info("[WebDev] Resource changes settled; invalidating cache and reloading viewers.");
        CabinResourceResolver.invalidateCache();
        CabinWsHub.broadcastReload();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public static void stop() {
        var thread = watcherRef.getAndSet(null);
        if (thread != null) thread.interrupt();
    }

    private record WatchResult(boolean changed, boolean overflow) {
    }
}