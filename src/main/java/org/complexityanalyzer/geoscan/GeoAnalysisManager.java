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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.geoscan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import org.complexityanalyzer.geoscan.scan.ScanCoordinator;
import org.complexityanalyzer.geoscan.scan.ScanExecutor;
import org.complexityanalyzer.geoscan.scan.ScanSession;
import org.complexityanalyzer.geoscan.task.ChunkBatchProcessor;
import org.complexityanalyzer.geoscan.task.ScanNotifier;
import org.complexityanalyzer.geoscan.task.ScanTask;
import org.complexityanalyzer.geoscan.task.WorldScanner;

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
    private volatile ScanProfile scheduledProfile = ScanProfile.NORMAL;

    public GeoAnalysisManager(MinecraftServer server, GeoDatabase database, AnalysisEngine engine) {
        this.server = server;
        this.database = database;
        this.analysisEngine = engine;

        var worldScanner = new WorldScanner(server);
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

    public boolean isThrottled() {
        return scanExecutor.isThrottled();
    }

    public float getCurrentMspt() {
        return scanExecutor.getCurrentMspt();
    }

    public void startInitialScanIfNeeded() {
        if (isShutdown.get()) return;

        var executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot start initial scan, executor not ready!");
            return;
        }

        try {
            executor.execute(() -> {
                if (isShutdown.get()) return;

                var phase = database.getScanPhase();

                if (phase == ScanMetadata.ScanPhase.COMPLETE) {
                    notifier.logInfo(Component.translatable("complexityanalyzer.notifier.complete"));
                    database.loadAll();
                    return;
                }

                if (phase == ScanMetadata.ScanPhase.REFINING) {
                    notifier.logWarn(Component.translatable("complexityanalyzer.log.refiner.error"));
                    dataRefiner.refine(analysisEngine::onGeoScanFinished);
                    return;
                }

                if (phase == ScanMetadata.ScanPhase.RECONNAISSANCE) {
                    notifier.logWarn(Component.translatable("complexityanalyzer.log.refiner.no_data"));
                    database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
                }

                startScanImmediately(32, "Server", ScanProfile.NORMAL);
            });
        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.warn("Cannot start initial scan — executor already shut down.");
        }
    }

    public void scheduleScan(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        if (isScanning() || isCountdownActive() || scanStarting.get()) {
            notifier.sendFailure(null, Component.translatable("complexityanalyzer.geoscan.error.already_running"));
            return;
        }

        this.scheduledChunksPerBiome = chunksPerBiome;
        this.scheduledInitiator = initiatorName;
        this.scheduledProfile = profile;
        this.countdownTicks.set(ScanConfig.COUNTDOWN_SECONDS * 20);

        notifier.broadcastWarning(Component.translatable("complexityanalyzer.geoscan.notification.scheduled",
                Component.translatable("complexityanalyzer.geoscan.profile." + profile.commandName),
                ScanConfig.COUNTDOWN_SECONDS));
    }

    public void startScanImmediately(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        try {
            server.execute(() -> {
                if (isShutdown.get()) return;

                if (!scanStarting.compareAndSet(false, true)) {
                    notifier.sendFailure(null, Component.translatable("complexityanalyzer.geoscan.error.starting"));
                    return;
                }

                try {
                    prepareForNewScan();
                    broadcastForcedScanAlert(initiatorName, profile);
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

    private void prepareForNewScan() {
        if (isScanning()) {
            ComplexityAnalyzer.LOGGER.info("[GeoAnalysisManager] Stopping current scan to start new one");
            scanExecutor.stop();
            coordinator.stopScan();
        }
        if (isCountdownActive()) cancelScheduledScan();
    }

    private void broadcastForcedScanAlert(String initiator, ScanProfile profile) {
        if ("Server".equals(initiator)) return;

        Component message = (profile == ScanProfile.MAXIMUM || profile == ScanProfile.ULTRA_FAST)
                ? Component.translatable("complexityanalyzer.geoscan.notification.forced_warning",
                Component.translatable("complexityanalyzer.geoscan.profile." + profile.commandName))
                : Component.translatable("complexityanalyzer.geoscan.notification.forced_lag");

        notifier.broadcastSevere(message);
    }

    public void stopScan(CommandSourceStack source) {
        if (isScanning()) {
            scanExecutor.stop();
            coordinator.stopScan();
            notifier.sendSuccess(source, Component.translatable("complexityanalyzer.geoscan.notification.stopped"));
            notifier.notifyReconnaissanceFinished(true);
            return;
        }

        if (isCountdownActive()) {
            cancelScheduledScan();
            notifier.sendSuccess(source, Component.translatable("complexityanalyzer.geoscan.notification.cancelled"));
            return;
        }

        notifier.sendFailure(source, Component.translatable("complexityanalyzer.geoscan.error.no_scan"));
    }

    public void cancelScheduledScan() {
        if (isCountdownActive()) {
            countdownTicks.set(-1);
            scheduledChunksPerBiome = 0;
            scheduledInitiator = "";
            scheduledProfile = ScanProfile.NORMAL;
            notifier.broadcastInfo(Component.translatable("complexityanalyzer.geoscan.notification.scheduled_cancelled"));
        }
    }

    public Component getStatus() {
        if (scanStarting.get()) return Component.translatable("complexityanalyzer.geoscan.status.starting");

        if (isCountdownActive()) return Component.translatable("complexityanalyzer.geoscan.status.scheduled",
                Component.translatable("complexityanalyzer.geoscan.profile." + scheduledProfile.commandName),
                countdownTicks.get() / 20);

        var phase = database.getScanPhase();

        return switch (phase) {
            case IDLE -> Component.translatable("complexityanalyzer.geoscan.status.idle");
            case RECONNAISSANCE -> {
                var session = coordinator.getCurrentSession();
                if (session != null && session.isValid()) {
                    var status = Component.translatable("complexityanalyzer.geoscan.status.phase1", session.getStatusString());
                    if (scanExecutor.isThrottled()) {
                        float mspt = scanExecutor.getCurrentMspt();
                        yield status.append(Component.translatable("complexityanalyzer.geoscan.status.paused_mspt", mspt));
                    }
                    yield status;
                } else {
                    yield Component.translatable("complexityanalyzer.geoscan.status.recon_no_session");
                }
            }
            case REFINING -> Component.translatable("complexityanalyzer.geoscan.status.phase2");
            case COMPLETE -> Component.translatable("complexityanalyzer.geoscan.status.complete");
        };
    }

    public boolean isScanning() {
        var phase = database.getScanPhase();
        if (phase == ScanMetadata.ScanPhase.REFINING) return true;
        var session = coordinator.getCurrentSession();
        return phase == ScanMetadata.ScanPhase.RECONNAISSANCE && session != null && session.isValid();
    }

    public boolean isCountdownActive() {
        return countdownTicks.get() > 0;
    }

    private void startScanInternal(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        countdownTicks.set(-1);
        var session = coordinator.createSession(chunksPerBiome, profile);
        notifyScanStarting(chunksPerBiome, initiatorName, profile);

        var executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot start scan, background executor not available!");
            return;
        }

        try {
            executor.execute(() -> runBackgroundScanSetup(session));
        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.warn("Background executor rejected scan task.");
        }
    }

    private void notifyScanStarting(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        var info = Component.translatable("complexityanalyzer.geoscan.initiator_format", initiatorName,
                Component.translatable("complexityanalyzer.geoscan.profile." + profile.commandName),
                Component.translatable("complexityanalyzer.unit.general.mode"));
        notifier.notifyScanStarting(chunksPerBiome, info);
    }

    private void runBackgroundScanSetup(ScanSession session) {
        if (!session.isValid() || isShutdown.get()) return;

        var tasks = coordinator.prepareTasks(session);
        if (!session.isValid() || isShutdown.get()) return;

        if (tasks.isEmpty()) {
            finalizeUpToDateSession();
            return;
        }

        var existingCoordinates = database.loadAllReconChunkCoordinates();
        initializeAndExecuteSession(session, tasks, existingCoordinates);
    }

    private void finalizeUpToDateSession() {
        try {
            server.execute(() -> {
                notifier.notifyDatabaseIsUpToDate();
                coordinator.invalidateCurrentSession();
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void initializeAndExecuteSession(ScanSession session, ObjectArrayList<ScanTask> tasks, Object2ObjectMap<ResourceLocation, LongOpenHashSet> existingCoordinates) {
        try {
            server.execute(() -> {
                if (!session.isValid() || isShutdown.get()) return;

                if (!coordinator.initializeSession(session, tasks, existingCoordinates)) {
                    coordinator.invalidateCurrentSession();
                    return;
                }

                scanExecutor.execute(session, () -> onReconnaissanceComplete(session));
            });
        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.warn("Server executor rejected scan initialization.");
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