/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.geoscan;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.geoscan.config.ScanConfig;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.refinement.DataRefiner;
import org.complexityanalyzer.geoscan.scan.*;
import org.complexityanalyzer.geoscan.task.*;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GeoAnalysisManager {

    private final MinecraftServer server;
    private final GeoDatabase database;
    private final AnalysisEngine analysisEngine;

    private final ChunkBatchProcessor batchProcessor;
    private final ScanNotifier notifier;
    private final ScanCoordinator coordinator;
    private final ScanExecutor scanExecutor;
    private final DataRefiner dataRefiner;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean scanStarting = new AtomicBoolean(false);
    private final AtomicInteger countdownTicks = new AtomicInteger(-1);

    private volatile int scheduledChunksPerBiome = 0;
    private volatile String scheduledInitiator = "";
    private volatile ScanProfile scheduledProfile = ScanProfile.QUARTER;

    public GeoAnalysisManager(MinecraftServer server, GeoDatabase database, AnalysisEngine engine) {
        this.server = server;
        this.database = database;
        this.analysisEngine = engine;

        WorldScanner worldScanner = new WorldScanner(server);
        this.batchProcessor = new ChunkBatchProcessor(server);
        this.notifier = new ScanNotifier(server);
        this.coordinator = new ScanCoordinator(server, database, worldScanner, notifier);
        this.scanExecutor = new ScanExecutor(server, database, worldScanner, batchProcessor, notifier);
        this.dataRefiner = new DataRefiner(database, notifier);

        NeoForge.EVENT_BUS.register(this);

        ComplexityAnalyzer.LOGGER.info("GeoAnalysisManager initialized");
    }

    public ScanSession getCurrentSession() {
        return coordinator.getCurrentSession();
    }

    public int getActiveWorkerCount() {
        return scanExecutor.getActiveWorkerCount();
    }

    public int getTargetWorkerCount() {
        return scanExecutor.getTargetWorkerCount();
    }

    public boolean isThrottled() {
        return scanExecutor.isThrottled();
    }

    public float getCurrentMspt() {
        return scanExecutor.getCurrentMspt();
    }

    public void startInitialScanIfNeeded() {
        if (isShutdown.get()) return;

        Executor executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot start initial scan, executor not ready!");
            return;
        }

        try {
            executor.execute(() -> {
                if (isShutdown.get()) return;

                ScanMetadata.ScanPhase phase = database.getScanPhase();

                if (phase == ScanMetadata.ScanPhase.COMPLETE) {
                    notifier.logInfo("GeoDatabase is complete. Skipping initial scan.");
                    database.loadAll();
                    return;
                }

                if (phase == ScanMetadata.ScanPhase.REFINING) {
                    notifier.logWarn("Server stopped during refinement. Restarting refinement phase...");
                    dataRefiner.refine(analysisEngine::onGeoScanFinished);
                    return;
                }

                if (phase == ScanMetadata.ScanPhase.RECONNAISSANCE) {
                    notifier.logWarn("Found incomplete reconnaissance. Resetting to IDLE...");
                    database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
                }

                startScanImmediately(32, "Server", ScanProfile.QUARTER);
            });
        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.warn("Cannot start initial scan — executor already shut down.");
        }
    }

    public void scheduleScan(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        if (isScanning() || isCountdownActive() || scanStarting.get()) {
            notifier.sendFailure(null, "A scan is already running or scheduled.");
            return;
        }

        this.scheduledChunksPerBiome = chunksPerBiome;
        this.scheduledInitiator = initiatorName;
        this.scheduledProfile = profile;
        this.countdownTicks.set(ScanConfig.COUNTDOWN_SECONDS * 20);

        notifier.broadcastWarning(String.format(
                "World scan (%s mode) will start in %d seconds.",
                profile.name().toLowerCase(), ScanConfig.COUNTDOWN_SECONDS));
    }

    public void startScanImmediately(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        try {
            server.execute(() -> {
                if (isShutdown.get()) return;

                if (isScanning()) {
                    ComplexityAnalyzer.LOGGER.info("[GeoAnalysisManager] Stopping current scan to start new one");
                    scanExecutor.stop();
                    coordinator.stopScan();
                }

                if (isCountdownActive()) cancelScheduledScan();

                if (!scanStarting.compareAndSet(false, true)) {
                    notifier.sendFailure(null, "A scan is already starting.");
                    return;
                }

                try {
                    if (!initiatorName.equals("Server")) {
                        if (profile == ScanProfile.FULL || profile == ScanProfile.MOST) {
                            notifier.broadcastSevere("!!! FORCED WORLD SCAN IN " + profile.name() +
                                    " MODE STARTED! SERVER MAY LAG SEVERELY! !!!");
                        } else {
                            notifier.broadcastSevere("Forced world scan started! Some lag may occur.");
                        }
                    }

                    startScanInternal(chunksPerBiome, initiatorName, profile);
                } finally {
                    scanStarting.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.warn("Cannot start scan — server executor already shut down.");
            scanStarting.set(false);
        }
    }

    public void stopScan(CommandSourceStack source) {
        if (isScanning()) {
            scanExecutor.stop();
            coordinator.stopScan();
            notifier.sendSuccess(source, "Scan stopped.");
            notifier.notifyReconnaissanceFinished(true);
            return;
        }

        if (isCountdownActive()) {
            cancelScheduledScan();
            notifier.sendSuccess(source, "Scheduled scan has been cancelled.");
            return;
        }

        notifier.sendFailure(source, "No scan is currently running or scheduled.");
    }

    public void cancelScheduledScan() {
        if (isCountdownActive()) {
            countdownTicks.set(-1);
            scheduledChunksPerBiome = 0;
            scheduledInitiator = "";
            scheduledProfile = ScanProfile.QUARTER;
            notifier.broadcastInfo("Scheduled world scan has been cancelled.");
        }
    }

    public String getStatus() {
        if (scanStarting.get()) return "Starting scan...";

        if (isCountdownActive()) return String.format("Scan scheduled in %s mode, starting in %d seconds...",
                scheduledProfile.name().toLowerCase(), countdownTicks.get() / 20);

        ScanMetadata.ScanPhase phase = database.getScanPhase();

        return switch (phase) {
            case IDLE -> "Idle";
            case RECONNAISSANCE -> {
                ScanSession session = coordinator.getCurrentSession();
                if (session != null && session.isValid()) {
                    String status = "Phase 1: " + session.getStatusString();
                    if (scanExecutor.isThrottled()) {
                        float mspt = scanExecutor.getCurrentMspt();
                        status += String.format(" [PAUSED - MSPT: %.1f]", mspt);
                    }
                    yield status;
                } else {
                    yield "Reconnaissance (no active session)";
                }
            }
            case REFINING -> "Phase 2: Refining all collected data...";
            case COMPLETE -> "Complete";
        };
    }

    public boolean isScanning() {
        ScanMetadata.ScanPhase phase = database.getScanPhase();
        if (phase == ScanMetadata.ScanPhase.REFINING) return true;
        ScanSession session = coordinator.getCurrentSession();
        return phase == ScanMetadata.ScanPhase.RECONNAISSANCE && session != null && session.isValid();
    }

    public boolean isCountdownActive() {
        return countdownTicks.get() > 0;
    }

    private void startScanInternal(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;
        countdownTicks.set(-1);
        ScanSession session = coordinator.createSession(chunksPerBiome, profile);
        notifier.notifyScanStarting(chunksPerBiome, initiatorName + " (" + profile.name().toLowerCase() + " mode)");
        Executor executor = analysisEngine.getBackgroundExecutor();

        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot start scan, background executor not available!");
            return;
        }

        try {
            executor.execute(() -> {
                if (!session.isValid() || isShutdown.get()) return;
                List<ScanTask> tasks = coordinator.prepareTasks(session);
                if (!session.isValid() || isShutdown.get()) return;
                if (tasks.isEmpty()) {
                    try {
                        server.execute(() -> {
                            notifier.notifyDatabaseIsUpToDate();
                            coordinator.invalidateCurrentSession();
                        });
                    } catch (RejectedExecutionException ignored) {
                    }
                    return;
                }

                try {
                    server.execute(() -> {
                        if (!session.isValid() || isShutdown.get()) return;
                        if (!coordinator.initializeSession(session, tasks)) {
                            coordinator.invalidateCurrentSession();
                            return;
                        }

                        scanExecutor.execute(session, () -> onReconnaissanceComplete(session));
                    });
                } catch (RejectedExecutionException e) {
                    ComplexityAnalyzer.LOGGER.warn("Server executor rejected scan initialization.");
                }
            });
        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.warn("Background executor rejected scan task.");
        }
    }

    private void onReconnaissanceComplete(ScanSession session) {
        if (isShutdown.get()) return;
        ComplexityAnalyzer.LOGGER.info("[GeoAnalysisManager] Reconnaissance complete! Starting refinement...");
        coordinator.finishReconnaissance(session);
        dataRefiner.refine(() -> {
            if (!isShutdown.get()) {
                ComplexityAnalyzer.LOGGER.info("[GeoAnalysisManager] Refinement complete! Invalidating session.");
                coordinator.invalidateCurrentSession();
                analysisEngine.onGeoScanFinished();
            }
        });
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (isShutdown.get()) return;
        if (isScanning()) scanExecutor.updateMsptMonitor();
        int ticks = countdownTicks.get();
        if (ticks <= 0) return;
        ticks = countdownTicks.decrementAndGet();

        if (ticks % 20 == 0) {
            int secondsLeft = ticks / 20;
            notifier.notifyScanCountdown(secondsLeft);
            if (secondsLeft == 0) startScanInternal(scheduledChunksPerBiome, scheduledInitiator, scheduledProfile);
        }
    }

    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return;

        long startTime = System.currentTimeMillis();
        ComplexityAnalyzer.LOGGER.info("[Shutdown] GeoAnalysisManager shutting down...");

        countdownTicks.set(-1);
        scanStarting.set(false);
        coordinator.shutdown();
        scanExecutor.shutdown();
        dataRefiner.shutdown();
        batchProcessor.shutdown();

        try {
            NeoForge.EVENT_BUS.unregister(this);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Error unregistering from event bus: {}", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        ComplexityAnalyzer.LOGGER.info("[Shutdown] GeoAnalysisManager shut down in {}ms.", elapsed);
    }
}