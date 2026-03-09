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
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.task.ScanNotifier;
import org.complexityanalyzer.geoscan.task.ScanTask;
import org.complexityanalyzer.geoscan.task.SpiralChunkSearcher;
import org.complexityanalyzer.geoscan.task.WorldScanner;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class GeoAnalysisManager {
    public enum ScanProfile {
        LITE(40.0f, 20, 250),
        FAST(50.0f, 5, 100),
        EXTREME(Float.MAX_VALUE, 1, 50),
        ATOMIC(0f, 0, 0);

        public final float maxTickTimeMs;
        public final int ticksBetweenScans;
        public final int relocateFailureThreshold;

        ScanProfile(float maxTickTimeMs, int ticksBetweenScans, int relocateFailureThreshold) {
            this.maxTickTimeMs = maxTickTimeMs;
            this.ticksBetweenScans = ticksBetweenScans;
            this.relocateFailureThreshold = relocateFailureThreshold;
        }
    }

    private static final int COUNTDOWN_SECONDS = 60;

    private final MinecraftServer server;
    private final GeoDatabase database;
    private final AnalysisEngine analysisEngine;
    private final WorldScanner worldScanner;
    private final ScanNotifier notifier;

    private volatile ScanMetadata.ScanPhase scanPhase = ScanMetadata.ScanPhase.IDLE;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean isProcessingChunk = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean tickInProgress = new AtomicBoolean(false);
    private final AtomicInteger tickCounter = new AtomicInteger(0);

    private volatile int countdownTicks = -1;
    private volatile int scheduledChunksPerBiome = 0;
    private volatile String scheduledInitiator = "";

    private volatile ScanProfile scheduledProfile = ScanProfile.LITE;
    private volatile ScanProfile currentProfile = ScanProfile.LITE;

    private final Queue<ScanTask> taskQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> attemptedChunks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger totalTasks = new AtomicInteger(0);
    private final AtomicInteger tasksCompleted = new AtomicInteger(0);

    private volatile long lastPauseLogTime = 0;
    private static final long LOG_THROTTLE_MS = 5000;
    private volatile boolean wasPaused = false;

    private volatile @Nullable ScanTask currentTask;
    private volatile SpiralChunkSearcher currentSearcher;
    private final List<ChunkSnapshot> pristineSnapshotsForCurrentTask = new CopyOnWriteArrayList<>();
    private final AtomicInteger consecutiveScanFailures = new AtomicInteger(0);

    public GeoAnalysisManager(MinecraftServer server, GeoDatabase database, AnalysisEngine engine) {
        this.server = server;
        this.database = database;
        this.analysisEngine = engine;
        this.worldScanner = new WorldScanner(server);
        this.notifier = new ScanNotifier(server);
        NeoForge.EVENT_BUS.register(this);
    }

    public void startInitialScanIfNeeded() {
        if (isShutdown.get()) return;

        Executor executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot start initial scan, executor is not ready!");
            return;
        }

        executor.execute(() -> {
            if (isShutdown.get()) return;

            ScanMetadata.ScanPhase phase = database.getScanPhase();
            if (phase == ScanMetadata.ScanPhase.COMPLETE) {
                notifier.logInfo("GeoDatabase is complete. Skipping initial scan.");
                database.loadAll();
                return;
            }
            if (phase == ScanMetadata.ScanPhase.REFINING) {
                notifier.logWarn("Server was stopped during refinement. Restarting refinement phase.");
                beginGlobalRefinement(null);
                return;
            }

            startScanImmediately(32, "Server", ScanProfile.LITE);
        });
    }

    public void scheduleScan(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        if (isScanning() || isCountdownActive()) {
            notifier.sendFailure(null, "A scan is already running or scheduled.");
            return;
        }
        this.scheduledChunksPerBiome = chunksPerBiome;
        this.scheduledInitiator = initiatorName;
        this.scheduledProfile = profile;
        this.countdownTicks = COUNTDOWN_SECONDS * 20;
        notifier.broadcastWarning(String.format("World scan (%s mode) will start in %d seconds.", profile.name().toLowerCase(), COUNTDOWN_SECONDS));
    }

    private void runAtomicScan(int chunksPerBiome) {
        if (isShutdown.get()) return;

        Executor executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot run ATOMIC scan, executor is not available!");
            return;
        }

        executor.execute(() -> {
            if (isShutdown.get()) return;

            long startTime = System.currentTimeMillis();
            notifier.logInfo("[ATOMIC] Starting blocking scan...");

            List<ScanTask> preparedTasks = prepareScanTasks(chunksPerBiome);
            if (preparedTasks.isEmpty()) {
                server.execute(() -> {
                    scanPhase = ScanMetadata.ScanPhase.IDLE;
                    stopRequested.set(false);
                    notifier.notifyDatabaseIsUpToDate();
                });
                return;
            }

            scanPhase = ScanMetadata.ScanPhase.RECONNAISSANCE;
            database.setScanPhase(ScanMetadata.ScanPhase.RECONNAISSANCE);
            taskQueue.addAll(preparedTasks);
            totalTasks.set(taskQueue.size());
            tasksCompleted.set(0);
            stopRequested.set(false);
            attemptedChunks.clear();
            attemptedChunks.addAll(database.loadAllReconChunkCoordinates());
            notifier.logInfo(String.format("[ATOMIC] Loaded %d already scanned chunk coordinates.", attemptedChunks.size()));
            currentTask = null;

            ScanTask task;
            while ((task = taskQueue.poll()) != null) {
                if (isShutdown.get() || stopRequested.get() || Thread.currentThread().isInterrupted()) {
                    notifier.logInfo("[ATOMIC] Stop requested, aborting remaining tasks.");
                    break;
                }

                this.currentTask = task;
                tasksCompleted.incrementAndGet();

                pristineSnapshotsForCurrentTask.clear();
                consecutiveScanFailures.set(0);

                Optional<ChunkPos> startPosOpt = worldScanner.findBiomeLocation(
                        task.dimension(), task.biome(), false
                );
                if (startPosOpt.isEmpty()) {
                    notifier.logWarn(String.format("[ATOMIC] Could not find location for %s, skipping.", task.biome().location()));
                    continue;
                }

                SpiralChunkSearcher searcher = new SpiralChunkSearcher();
                searcher.startAt(startPosOpt.get().x, startPosOpt.get().z);
                int attempts = 0;
                int relocateTries = 0;

                while (pristineSnapshotsForCurrentTask.size() < task.chunksToFind()
                        && attempts < 10000
                        && relocateTries < 5
                        && !isShutdown.get()
                        && !stopRequested.get()
                        && !Thread.currentThread().isInterrupted()) {

                    if (attempts > 0 && attempts % 250 == 0) {
                        Optional<ChunkPos> newStart = worldScanner.findBiomeLocation(
                                task.dimension(), task.biome(), true
                        );
                        if (newStart.isPresent()) {
                            searcher.startAt(newStart.get().x, newStart.get().z);
                            relocateTries++;
                        } else {
                            break;
                        }
                    }

                    ChunkPos currentPos = searcher.next();
                    if (!attemptedChunks.add(currentPos.toLong())) {
                        attempts++;
                        continue;
                    }

                    if (isShutdown.get() || Thread.currentThread().isInterrupted()) {
                        notifier.logInfo(String.format("[ATOMIC] Interrupted during scan of %s", task.biome().location()));
                        break;
                    }

                    Optional<ChunkSnapshot> snapshotOpt = worldScanner.processChunkBlocking(
                            task.dimension(),
                            task.biome(),
                            currentPos,
                            Math.min(ComplexityConfig.getGeoscanChunkTimeoutMs(), 1000)
                    );

                    if (snapshotOpt.isPresent()) {
                        if (database.analyzeSnapshotForRecon(snapshotOpt.get())) {
                            pristineSnapshotsForCurrentTask.add(snapshotOpt.get());
                        }
                    }

                    attempts++;
                }

                if (!pristineSnapshotsForCurrentTask.isEmpty()) {
                    database.appendReconData(
                            task.dimension().location(),
                            task.biome().location(),
                            new ArrayList<>(pristineSnapshotsForCurrentTask)
                    );
                    notifier.logInfo(String.format(
                            "[ATOMIC] Task %d/%d: %s... found %d candidates.",
                            tasksCompleted.get(), totalTasks.get(),
                            task.biome().location().getPath(),
                            pristineSnapshotsForCurrentTask.size()
                    ));
                }
            }

            this.currentTask = null;

            if (isShutdown.get() || stopRequested.get()) {
                server.execute(() -> {
                    scanPhase = ScanMetadata.ScanPhase.IDLE;
                    taskQueue.clear();
                    stopRequested.set(false);
                    notifier.notifyReconnaissanceFinished(true);
                });
            } else {
                long duration = System.currentTimeMillis() - startTime;
                notifier.logInfo(String.format(
                        "[ATOMIC] Reconnaissance phase complete in %.2f seconds.",
                        duration / 1000.0
                ));
                server.execute(() -> finishReconnaissance(false));
            }
        });
    }

    public void startScanImmediately(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        server.execute(() -> {
            if (isShutdown.get()) return;

            if (isScanning() || isCountdownActive()) {
                if (initiatorName.equals("Server")) {
                    notifier.logWarn("Scan was requested by the server, but another scan/countdown is already active. Skipping.");
                } else {
                    notifier.sendFailure(null, "A scan is already running or scheduled.");
                }
                return;
            }

            if (!initiatorName.equals("Server")) {
                if (profile == ScanProfile.EXTREME) {
                    notifier.broadcastSevere("!!! FORCED WORLD SCAN IN EXTREME MODE STARTED! SERVER MAY LAG SEVERELY! !!!");
                } else {
                    notifier.broadcastSevere("Forced world scan started! Severe lag may occur!");
                }
            }

            startScanInternal(chunksPerBiome, initiatorName, profile);
        });
    }

    public void stopScan(CommandSourceStack source) {
        if (isScanning()) {
            if (stopRequested.getAndSet(true)) {
                notifier.sendFailure(source, "A stop has already been requested.");
            } else {
                notifier.sendSuccess(source, "Scan stop requested. Finishing and saving current progress...");
            }
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
            countdownTicks = -1;
            scheduledChunksPerBiome = 0;
            scheduledInitiator = "";
            scheduledProfile = ScanProfile.LITE;
            notifier.broadcastInfo("Scheduled world scan has been cancelled.");
        }
    }

    public String getStatus() {
        if (isCountdownActive()) {
            return String.format("Scan scheduled in %s mode, starting in %d seconds...", scheduledProfile.name().toLowerCase(), countdownTicks / 20);
        }
        ScanTask task = currentTask;
        int completed = tasksCompleted.get();
        int total = totalTasks.get();

        return switch (scanPhase) {
            case IDLE -> "Idle";
            case RECONNAISSANCE -> {
                if (task == null) yield "Phase 1: Reconnaissance (Initializing next task...)";
                yield String.format("Phase 1: Reconnaissance. Task %d/%d: %s (%d/%d)",
                        completed, total, task.biome().location().getPath(),
                        pristineSnapshotsForCurrentTask.size(), task.chunksToFind());
            }
            case REFINING -> "Phase 2: Refining all collected data...";
            case COMPLETE -> "Complete";
        };
    }

    public boolean isScanning() {
        ScanMetadata.ScanPhase phase = scanPhase;
        return phase == ScanMetadata.ScanPhase.RECONNAISSANCE || phase == ScanMetadata.ScanPhase.REFINING;
    }

    public boolean isCountdownActive() {
        return this.countdownTicks > 0;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (isShutdown.get()) return;

        if (!tickInProgress.compareAndSet(false, true)) return;

        try {
            if (isShutdown.get()) return;
            if (handleCountdown()) return;
            if (currentProfile == ScanProfile.ATOMIC) return;
            if (handleStopRequest()) return;

            ScanMetadata.ScanPhase phase = scanPhase;
            if (phase != ScanMetadata.ScanPhase.RECONNAISSANCE || isProcessingChunk.get()) return;

            if (isServerUnderLoad()) {
                long now = System.currentTimeMillis();
                if (!wasPaused) {
                    notifier.logInfo("Server is under heavy load (tick time > " + currentProfile.maxTickTimeMs + "ms). Geo-scan is paused.");
                    wasPaused = true;
                    lastPauseLogTime = now;
                } else if (now - lastPauseLogTime > LOG_THROTTLE_MS) {
                    notifier.logInfo("Geo-scan still paused due to server load.");
                    lastPauseLogTime = now;
                }
                return;
            } else {
                if (wasPaused) {
                    notifier.logInfo("Server load normalized. Geo-scan resumed.");
                    wasPaused = false;
                }
            }

            if (isTickScheduled()) return;

            ScanTask task = currentTask;
            if (task == null) {
                if (!startNextTask()) {
                    finishReconnaissance(false);
                }
                return;
            }

            if (pristineSnapshotsForCurrentTask.size() >= task.chunksToFind()) {
                finishCurrentTask();
                return;
            }

            if (consecutiveScanFailures.get() >= currentProfile.relocateFailureThreshold) {
                handleRelocation();
                return;
            }

            processNextChunk();
        } finally {
            tickInProgress.set(false);
        }
    }

    private void startScanInternal(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        if (isShutdown.get()) return;

        this.currentProfile = profile;
        this.countdownTicks = -1;
        this.scanPhase = ScanMetadata.ScanPhase.RECONNAISSANCE;
        this.stopRequested.set(false);
        this.currentTask = null;

        notifier.notifyScanStarting(chunksPerBiome, initiatorName + " (" + profile.name().toLowerCase() + " mode)");

        if (profile == ScanProfile.ATOMIC) {
            runAtomicScan(chunksPerBiome);
        } else {
            Executor executor = analysisEngine.getBackgroundExecutor();
            if (executor == null) {
                ComplexityAnalyzer.LOGGER.error("Cannot start scan, background executor is not available!");
                this.scanPhase = ScanMetadata.ScanPhase.IDLE;
                return;
            }

            executor.execute(() -> {
                if (isShutdown.get()) return;

                List<ScanTask> tasks = prepareScanTasks(chunksPerBiome);

                server.execute(() -> {
                    if (isShutdown.get()) return;

                    if (tasks.isEmpty()) {
                        notifier.notifyDatabaseIsUpToDate();
                        this.scanPhase = ScanMetadata.ScanPhase.IDLE;
                        return;
                    }

                    database.setScanPhase(ScanMetadata.ScanPhase.RECONNAISSANCE);
                    taskQueue.addAll(tasks);
                    totalTasks.set(tasks.size());
                    tasksCompleted.set(0);
                    attemptedChunks.clear();
                    attemptedChunks.addAll(database.loadAllReconChunkCoordinates());
                    notifier.logInfo(String.format("Loaded %d already scanned chunk coordinates.", attemptedChunks.size()));
                    notifier.notifyScanPreparationComplete(totalTasks.get());
                });
            });
        }
    }

    private List<ScanTask> prepareScanTasks(int chunksPerBiome) {
        database.loadAll();
        List<ScanTask> tasksToQueue = new ArrayList<>();
        ComplexityAnalyzer.LOGGER.debug("[Prepare] Starting to build scan tasks. Chunks per biome: {}", chunksPerBiome);

        for (ServerLevel level : server.getAllLevels()) {
            if (isShutdown.get() || stopRequested.get()) break;
            ResourceKey<Level> dimension = level.dimension();

            ComplexityAnalyzer.LOGGER.info("Scanning dimension: {}", dimension.location());

            Set<ResourceKey<Biome>> biomesToScan = getBiomesForDimension(level);

            ComplexityAnalyzer.LOGGER.debug("Found {} biomes in dimension {}", biomesToScan.size(), dimension.location());

            for (ResourceKey<Biome> biomeKey : biomesToScan) {
                if (isShutdown.get() || stopRequested.get()) break;

                int finalChunks = database.getBiomeData(dimension.location(), biomeKey.location())
                        .map(BiomeScanData::getChunksScanned).orElse(0);
                int reconChunks = database.countReconChunks(dimension.location(), biomeKey.location());
                int chunksNeeded = chunksPerBiome - Math.max(finalChunks, reconChunks);

                if (chunksNeeded > 0) {
                    tasksToQueue.add(new ScanTask(dimension, biomeKey, chunksNeeded));
                }
            }
        }

        ComplexityAnalyzer.LOGGER.debug("[Prepare] Found {} total scan tasks.", tasksToQueue.size());
        tasksToQueue.sort(Comparator.naturalOrder());
        return tasksToQueue;
    }

    private Set<ResourceKey<Biome>> getBiomesForDimension(ServerLevel level) {
        Set<ResourceKey<Biome>> biomes = new HashSet<>();

        var biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        biomeSource.possibleBiomes().forEach(holder -> holder.unwrapKey().ifPresent(biomes::add));

        return biomes;
    }

    private boolean startNextTask() {
        if (isShutdown.get()) return false;

        ScanTask task = taskQueue.poll();
        currentTask = task;

        if (task == null) return false;

        isProcessingChunk.set(true);

        CompletableFuture.supplyAsync(
                () -> worldScanner.findBiomeLocation(task.dimension(), task.biome(), false),
                analysisEngine.getBackgroundExecutor()
        ).thenAcceptAsync(startPos -> {
            if (isShutdown.get()) {
                isProcessingChunk.set(false);
                return;
            }

            if (startPos.isPresent()) {
                tasksCompleted.incrementAndGet();
                pristineSnapshotsForCurrentTask.clear();
                consecutiveScanFailures.set(0);
                currentSearcher = new SpiralChunkSearcher();
                currentSearcher.startAt(startPos.get().x, startPos.get().z);
                notifier.logInfo(String.format(
                        "Starting reconnaissance for %s (%d/%d). Starting at %s",
                        task.biome().location().getPath(),
                        tasksCompleted.get(), totalTasks.get(), startPos.get()
                ));
            } else {
                notifier.logWarn("Could not find biome "
                        + task.biome().location() + ". Skipping.");
                currentTask = null;
            }
            isProcessingChunk.set(false);
        }, server);

        return true;
    }

    private void processNextChunk() {
        if (isShutdown.get()) return;
        if (!isProcessingChunk.compareAndSet(false, true)) return;

        SpiralChunkSearcher searcher = currentSearcher;
        ScanTask task = currentTask;

        if (searcher == null || task == null || isShutdown.get()) {
            isProcessingChunk.set(false);
            return;
        }

        final long timeBudgetNanos = 1_000_000L;
        long searchStartTime = System.nanoTime();

        ChunkPos nextPos = null;
        while (System.nanoTime() - searchStartTime < timeBudgetNanos) {
            ChunkPos candidatePos = searcher.next();
            if (attemptedChunks.add(candidatePos.toLong())) {
                nextPos = candidatePos;
                break;
            }
        }

        if (nextPos == null) {
            isProcessingChunk.set(false);
            return;
        }

        worldScanner.processChunk(task.dimension(), task.biome(), nextPos, (snapshotOpt, success) -> {
            if (isShutdown.get()) {
                isProcessingChunk.set(false);
                return;
            }

            if (success) {
                snapshotOpt.ifPresent(snapshot -> {
                    if (database.analyzeSnapshotForRecon(snapshot)) {
                        pristineSnapshotsForCurrentTask.add(snapshot);
                        consecutiveScanFailures.set(0);
                    } else {
                        consecutiveScanFailures.incrementAndGet();
                    }
                });
            } else {
                consecutiveScanFailures.incrementAndGet();
            }
            isProcessingChunk.set(false);
        });

        notifier.logProgress(true, getStatus());
    }

    private void finishCurrentTask() {
        ScanTask task = currentTask;
        if (task == null) {
            return;
        }

        if (!pristineSnapshotsForCurrentTask.isEmpty()) {
            notifier.logInfo(String.format("Finished reconnaissance for biome %s, found %d new candidates. Saving...",
                    task.biome().location(), pristineSnapshotsForCurrentTask.size()));
            database.appendReconData(task.dimension().location(), task.biome().location(), new ArrayList<>(pristineSnapshotsForCurrentTask));
        }
        currentTask = null;
        currentSearcher = null;
        pristineSnapshotsForCurrentTask.clear();
    }

    private void finishReconnaissance(boolean wasStopped) {
        finishCurrentTask();
        scanPhase = ScanMetadata.ScanPhase.IDLE;
        taskQueue.clear();
        stopRequested.set(false);
        notifier.notifyReconnaissanceFinished(wasStopped);

        if (!wasStopped && !isShutdown.get()) {
            beginGlobalRefinement(null);
        }
    }

    public void beginGlobalRefinement(@Nullable CommandSourceStack source) {
        if (isShutdown.get()) return;

        scanPhase = ScanMetadata.ScanPhase.REFINING;
        database.setScanPhase(ScanMetadata.ScanPhase.REFINING);
        notifier.sendSuccess(source, "Reconnaissance complete! Starting final data refinement in background...");

        Executor executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot begin global refinement, executor is not available!");
            scanPhase = ScanMetadata.ScanPhase.IDLE;
            database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
            return;
        }

        executor.execute(() -> {
            if (isShutdown.get()) return;

            try {
                Map<ResourceLocation, Map<ResourceLocation, Path>> allReconPaths = database.getAllReconFilePaths();
                if (allReconPaths.isEmpty()) {
                    notifier.logWarn("Refinement started, but no reconnaissance data was found.");
                    finishRefinement();
                    return;
                }

                notifier.logInfo("Building heuristics from reconnaissance data...");
                database.buildHeuristicFromFiles(allReconPaths);

                if (isShutdown.get()) return;

                notifier.logInfo("Clearing old final data...");
                database.clearFinalData();

                if (isShutdown.get()) return;

                notifier.logInfo("Refining data for each biome...");
                allReconPaths.forEach((dim, biomeMap) -> biomeMap.forEach((biome, path) -> {
                    if (isShutdown.get()) return;

                    try (Stream<ChunkSnapshot> snapshotStream = database.streamReconFile(path)) {
                        BiomeScanData finalData = database.refineRawDataFromStream(snapshotStream, dim);
                        if (finalData.getChunksScanned() > 0) {
                            database.saveBiomeData(dim, biome, finalData);
                        }
                    }
                }));

                if (!isShutdown.get()) {
                    finishRefinement();
                }
            } catch (IOException e) {
                notifier.logError("A critical error occurred during the refinement phase!", e);
                scanPhase = ScanMetadata.ScanPhase.IDLE;
                database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
            }
        });
    }

    private void finishRefinement() {
        if (isShutdown.get()) return;

        notifier.logInfo("Finalizing refinement process...");
        scanPhase = ScanMetadata.ScanPhase.COMPLETE;
        database.setScanPhase(ScanMetadata.ScanPhase.COMPLETE);
        database.loadAll();

        if (!isShutdown.get()) {
            analysisEngine.onGeoScanFinished();
        }
        notifier.notifyRefinementFinished();
    }

    private boolean handleCountdown() {
        int ticks = countdownTicks;
        if (ticks > 0) {
            countdownTicks = --ticks;
            if (ticks % 20 == 0) {
                int secondsLeft = ticks / 20;
                notifier.notifyScanCountdown(secondsLeft);
                if (secondsLeft == 0) {
                    startScanInternal(scheduledChunksPerBiome, scheduledInitiator, scheduledProfile);
                }
            }
            return true;
        }
        return false;
    }

    private boolean handleStopRequest() {
        if (stopRequested.get() && scanPhase == ScanMetadata.ScanPhase.RECONNAISSANCE && !isProcessingChunk.get()) {
            finishReconnaissance(true);
            return true;
        }
        return stopRequested.get();
    }

    private void handleRelocation() {
        ScanTask task = currentTask;
        SpiralChunkSearcher searcher = currentSearcher;

        if (task == null || searcher == null || isShutdown.get()) return;

        notifier.logWarn("Too many failures for "
                + task.biome().location() + ". Relocating...");

        isProcessingChunk.set(true);

        CompletableFuture.supplyAsync(
                () -> worldScanner.findBiomeLocation(task.dimension(), task.biome(), true),
                analysisEngine.getBackgroundExecutor()
        ).thenAcceptAsync(newStartPos -> {
            if (isShutdown.get()) {
                isProcessingChunk.set(false);
                return;
            }

            if (newStartPos.isPresent()) {
                SpiralChunkSearcher currentSearcherRef = currentSearcher;
                if (currentSearcherRef != null) {
                    currentSearcherRef.startAt(newStartPos.get().x, newStartPos.get().z);
                }
                consecutiveScanFailures.set(0);
            } else {
                notifier.logError("Could not relocate for "
                        + task.biome().location() + ". Skipping.");
                finishCurrentTask();
            }
            isProcessingChunk.set(false);
        }, server);
    }

    private boolean isServerUnderLoad() {
        return server.getAverageTickTimeNanos() / 1_000_000.0F > currentProfile.maxTickTimeMs;
    }

    private boolean isTickScheduled() {
        int counter = tickCounter.incrementAndGet();
        if (counter >= currentProfile.ticksBetweenScans) {
            tickCounter.set(0);
            return false;
        }
        return true;
    }

    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return;

        ComplexityAnalyzer.LOGGER.info("Shutting down GeoAnalysisManager...");

        this.stopRequested.set(true);
        this.scanPhase = ScanMetadata.ScanPhase.IDLE;

        this.worldScanner.shutdown();

        this.taskQueue.clear();
        this.countdownTicks = -1;
        this.currentTask = null;
        this.currentSearcher = null;
        this.pristineSnapshotsForCurrentTask.clear();
        this.attemptedChunks.clear();

        try {
            NeoForge.EVENT_BUS.unregister(this);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Error unregistering from event bus: {}", e.getMessage());
        }

        ComplexityAnalyzer.LOGGER.info("GeoAnalysisManager has been shut down.");
    }
}