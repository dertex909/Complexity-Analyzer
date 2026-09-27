package org.complexityanalyzer.network.web;

import org.complexityanalyzer.ComplexityAnalyzer;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

public final class ViewerWatcher {

    private static Thread watcherThread;
    private static volatile boolean running = false;

    private ViewerWatcher() {
    }

    public static synchronized void start() {
        var devDir = CabinResourceResolver.findDevDir();
        if (devDir == null || running) return;
        running = true;

        watcherThread = Thread.ofVirtual().name("Complexity-Viewer-Watcher").start(() -> {
            try (var watchService = FileSystems.getDefault().newWatchService()) {

                try (var stream = Files.walk(devDir)) {
                    stream.filter(Files::isDirectory).forEach(dir -> {
                        try {
                            dir.register(watchService,
                                    StandardWatchEventKinds.ENTRY_MODIFY,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_DELETE);
                        } catch (IOException ignored) {
                        }
                    });
                }

                long lastReload = 0;
                ComplexityAnalyzer.LOGGER.info("[WebDev] Live reload watcher active on: {} (Virtual Thread)", devDir);

                while (running && !Thread.currentThread().isInterrupted()) {
                    var key = watchService.take();
                    var dir = (Path) key.watchable();

                    boolean shouldReload = false;
                    for (var event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        var context = (Path) event.context();
                        if (context == null) continue;
                        var fullPath = dir.resolve(context);

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                            try {
                                fullPath.register(watchService,
                                        StandardWatchEventKinds.ENTRY_MODIFY,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_DELETE);
                            } catch (IOException ignored) {
                            }
                        }

                        if (CabinNettyHandler.isSupportedExtension(context.toString())) shouldReload = true;
                    }

                    long now = System.currentTimeMillis();
                    if (shouldReload && (now - lastReload > 250)) {
                        lastReload = now;
                        ComplexityAnalyzer.LOGGER.info("[WebDev] Change detected, reloading browser...");
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

    public static synchronized void stop() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }
}