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
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;
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
    private static final int ATOMIC_MAX_PENDING_PER_WORKER = 32;
    private static final int ATOMIC_MAX_ATTEMPTS_PER_CHUNK = 100;
    private static final int ATOMIC_MIN_ATTEMPTS = 5000;
    private static final int ATOMIC_MAX_ATTEMPTS = 50000;
    private static final int ATOMIC_MAX_RELOCATIONS = 20;
    private static final long LOG_THROTTLE_MS = 5000;

    private final AtomicInteger totalChunksNeeded = new AtomicInteger(0);
    private final AtomicInteger totalChunksFound = new AtomicInteger(0);

    private final MinecraftServer server;
    private final GeoDatabase database;
    private final AnalysisEngine analysisEngine;
    private final WorldScanner worldScanner;
    private final ScanNotifier notifier;

    private volatile ScanMetadata.ScanPhase scanPhase = ScanMetadata.ScanPhase.IDLE;
    private volatile ScanProfile currentProfile = ScanProfile.LITE;
    private volatile ScanProfile scheduledProfile = ScanProfile.LITE;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean isProcessingChunk = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean tickInProgress = new AtomicBoolean(false);

    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final AtomicInteger totalTasks = new AtomicInteger(0);
    private final AtomicInteger tasksCompleted = new AtomicInteger(0);
    private final AtomicInteger consecutiveScanFailures = new AtomicInteger(0);

    private volatile int countdownTicks = -1;
    private volatile int scheduledChunksPerBiome = 0;
    private volatile String scheduledInitiator = "";

    private final Queue<ScanTask> taskQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> attemptedChunks = ConcurrentHashMap.newKeySet();

    private volatile @Nullable ScanTask currentTask;
    private volatile @Nullable SpiralChunkSearcher currentSearcher;
    private final List<ChunkSnapshot> pristineSnapshotsForCurrentTask = new CopyOnWriteArrayList<>();

    private volatile long lastPauseLogTime = 0;
    private volatile boolean wasPaused = false;

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
        notifier.broadcastWarning(String.format(
                "World scan (%s mode) will start in %d seconds.",
                profile.name().toLowerCase(), COUNTDOWN_SECONDS));
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
                if (profile == ScanProfile.EXTREME || profile == ScanProfile.ATOMIC) {
                    notifier.broadcastSevere("!!! FORCED WORLD SCAN IN " + profile.name() + " MODE STARTED! SERVER MAY LAG SEVERELY! !!!");
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
            return String.format("Scan scheduled in %s mode, starting in %d seconds...",
                    scheduledProfile.name().toLowerCase(), countdownTicks / 20);
        }

        int completed = tasksCompleted.get();
        int total = totalTasks.get();

        return switch (scanPhase) {
            case IDLE -> "Idle";
            case RECONNAISSANCE -> {
                if (currentProfile == ScanProfile.ATOMIC) {
                    int chunksFound = totalChunksFound.get();
                    int chunksNeeded = totalChunksNeeded.get();
                    yield String.format("Phase 1: ATOMIC scan - %d/%d biomes completed (%d/%d chunks)",
                            completed, total, chunksFound, chunksNeeded);
                } else {
                    ScanTask task = currentTask;
                    if (task == null) {
                        yield String.format("Phase 1: Reconnaissance - %d/%d biomes completed (Initializing...)",
                                completed, total);
                    } else {
                        yield String.format("Phase 1: Reconnaissance - %d/%d biomes completed. Current: %s (%d/%d chunks)",
                                completed, total,
                                task.biome().location().getPath(),
                                pristineSnapshotsForCurrentTask.size(),
                                task.chunksToFind());
                    }
                }
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
                handleServerOverload();
                return;
            } else if (wasPaused) {
                notifier.logInfo("Server load normalized. Geo-scan resumed.");
                wasPaused = false;
            }

            if (isTickScheduled()) return;

            ScanTask task = currentTask;
            if (task == null) {
                if (!startNextTask()) finishReconnaissance(false);
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

        worldScanner.configureForScan(chunksPerBiome);

        notifier.notifyScanStarting(chunksPerBiome, initiatorName + " (" + profile.name().toLowerCase() + " mode)");

        if (profile == ScanProfile.ATOMIC) {
            runAtomicScan(chunksPerBiome);
        } else {
            runThrottledScan(chunksPerBiome);
        }
    }

    private void runThrottledScan(int chunksPerBiome) {
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
            notifier.logInfo("[ATOMIC] Preparing parallel scan...");

            List<ScanTask> preparedTasks = prepareScanTasks(chunksPerBiome);
            if (preparedTasks.isEmpty()) {
                server.execute(() -> {
                    scanPhase = ScanMetadata.ScanPhase.IDLE;
                    stopRequested.set(false);
                    notifier.notifyDatabaseIsUpToDate();
                });
                return;
            }

            int totalNeeded = preparedTasks.stream().mapToInt(ScanTask::chunksToFind).sum();
            totalChunksNeeded.set(totalNeeded);
            totalChunksFound.set(0);

            scanPhase = ScanMetadata.ScanPhase.RECONNAISSANCE;
            database.setScanPhase(ScanMetadata.ScanPhase.RECONNAISSANCE);
            taskQueue.addAll(preparedTasks);
            totalTasks.set(taskQueue.size());
            tasksCompleted.set(0);
            stopRequested.set(false);
            attemptedChunks.clear();
            attemptedChunks.addAll(database.loadAllReconChunkCoordinates());

            notifier.logInfo(String.format("[ATOMIC] Loaded %d already scanned chunk coordinates.", attemptedChunks.size()));

            int numWorkers = ThreadPoolManager.getInstance().getParallelism();
            notifier.logInfo(String.format("[ATOMIC] Starting %d parallel workers...", numWorkers));

            ConcurrentHashMap<String, CopyOnWriteArrayList<ChunkSnapshot>> resultsByBiome = new ConcurrentHashMap<>();

            AtomicInteger activeWorkers = new AtomicInteger(numWorkers);
            CountDownLatch allWorkersDone = new CountDownLatch(numWorkers);

            ExecutorService computePool = ThreadPoolManager.getInstance().getComputePool();
            for (int i = 0; i < numWorkers; i++) {
                final int workerId = i;
                computePool.execute(() -> atomicWorker(workerId, resultsByBiome, allWorkersDone, activeWorkers));
            }

            computePool.execute(() -> atomicCompletionHandler(allWorkersDone, resultsByBiome, startTime));
        });
    }

    private void atomicWorker(
            int workerId,
            ConcurrentHashMap<String, CopyOnWriteArrayList<ChunkSnapshot>> resultsByBiome,
            CountDownLatch doneLatch,
            AtomicInteger activeWorkers
    ) {
        final String workerName = "Worker-" + workerId;
        ComplexityAnalyzer.LOGGER.info("[ATOMIC] {} started", workerName);

        final Semaphore pendingSlots = new Semaphore(ATOMIC_MAX_PENDING_PER_WORKER);
        final Phaser taskPhaser = new Phaser(1);

        try {
            while (!isShutdown.get() && !stopRequested.get() && !Thread.currentThread().isInterrupted()) {
                ScanTask task = taskQueue.poll();
                if (task == null) {
                    ComplexityAnalyzer.LOGGER.debug("[ATOMIC] {} - no more tasks, exiting", workerName);
                    break;
                }

                processAtomicTask(workerName, task, resultsByBiome, pendingSlots, taskPhaser);
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ATOMIC] {} crashed with exception", workerName, e);
        } finally {
            taskPhaser.forceTermination();
            int remaining = activeWorkers.decrementAndGet();
            ComplexityAnalyzer.LOGGER.info("[ATOMIC] {} finished. {} workers still active", workerName, remaining);
            doneLatch.countDown();
        }
    }

    private void processAtomicTask(
            String workerName,
            ScanTask task,
            ConcurrentHashMap<String, CopyOnWriteArrayList<ChunkSnapshot>> resultsByBiome,
            Semaphore pendingSlots,
            Phaser taskPhaser
    ) {
        final String biomeKey = task.dimension().location() + "|" + task.biome().location();
        final CopyOnWriteArrayList<ChunkSnapshot> biomeSnapshots =
                resultsByBiome.computeIfAbsent(biomeKey, k -> new CopyOnWriteArrayList<>());

        final int targetChunks = task.chunksToFind();
        final int taskNum = tasksCompleted.incrementAndGet();
        currentTask = task;

        ComplexityAnalyzer.LOGGER.info("[ATOMIC] {} - task {}/{}: {} (need {} chunks)",
                workerName, taskNum, totalTasks.get(),
                task.biome().location().getPath(), targetChunks);

        final AtomicInteger foundCount = new AtomicInteger(0);
        final AtomicInteger attemptCount = new AtomicInteger(0);
        final AtomicBoolean taskDone = new AtomicBoolean(false);

        final int maxAttempts = Math.min(ATOMIC_MAX_ATTEMPTS,
                Math.max(ATOMIC_MIN_ATTEMPTS, targetChunks * ATOMIC_MAX_ATTEMPTS_PER_CHUNK));

        Optional<ChunkPos> startPosOpt = worldScanner.findBiomeLocation(task.dimension(), task.biome(), false);
        if (startPosOpt.isEmpty()) {
            ComplexityAnalyzer.LOGGER.warn("[ATOMIC] {} - could not find biome {}, skipping",
                    workerName, task.biome().location());
            return;
        }

        SpiralChunkSearcher searcher = new SpiralChunkSearcher();
        searcher.startAt(startPosOpt.get().x, startPosOpt.get().z);

        int relocations = 0;
        final AtomicInteger lastFoundAtAttempt = new AtomicInteger(0);

        while (!taskDone.get()
                && attemptCount.get() < maxAttempts
                && !isShutdown.get()
                && !stopRequested.get()
                && !Thread.currentThread().isInterrupted()) {

            if (foundCount.get() >= targetChunks) {
                taskDone.set(true);
                break;
            }

            int attempts = attemptCount.get();
            int attemptsSinceLastFind = attempts - lastFoundAtAttempt.get();

            if (attemptsSinceLastFind > 500 && relocations < ATOMIC_MAX_RELOCATIONS) {
                Optional<ChunkPos> newStart = worldScanner.findBiomeLocation(task.dimension(), task.biome(), true);
                if (newStart.isPresent()) {
                    searcher.startAt(newStart.get().x, newStart.get().z);
                    relocations++;
                    lastFoundAtAttempt.set(attempts);
                    ComplexityAnalyzer.LOGGER.debug("[ATOMIC] {} - relocated (stuck), now at [{}, {}]",
                            workerName, newStart.get().x, newStart.get().z);
                }
            }

            boolean acquired;
            try {
                acquired = pendingSlots.tryAcquire(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (!acquired) {
                continue;
            }

            if (taskDone.get() || foundCount.get() >= targetChunks || isShutdown.get() || stopRequested.get()) {
                pendingSlots.release();
                break;
            }

            ChunkPos nextPos = findNextChunk(searcher, attemptCount);
            if (nextPos == null) {
                pendingSlots.release();
                continue;
            }

            taskPhaser.register();

            worldScanner.processChunk(task.dimension(), task.biome(), nextPos, (resultOpt, success) -> {
                try {
                    if (taskDone.get()) return;

                    if (success && resultOpt.isPresent()) {
                        WorldScanner.ScanResult result = resultOpt.get();
                        ChunkSnapshot snapshot = result.snapshot();
                        ResourceLocation actualBiome = result.actualBiome();

                        if (database.analyzeSnapshotForRecon(snapshot)) {
                            ResourceLocation targetBiome = task.biome().location();
                            boolean isTargetBiome = actualBiome != null && actualBiome.equals(targetBiome);

                            if (isTargetBiome) {
                                int newCount = foundCount.incrementAndGet();
                                totalChunksFound.incrementAndGet();
                                lastFoundAtAttempt.set(attemptCount.get());

                                if (newCount <= targetChunks) {
                                    biomeSnapshots.add(snapshot);

                                    if (newCount >= targetChunks) {
                                        taskDone.set(true);
                                        ComplexityAnalyzer.LOGGER.info("[ATOMIC] {} - {} COMPLETE: {}/{} chunks",
                                                workerName, task.biome().location().getPath(),
                                                newCount, targetChunks);
                                    } else if (newCount % 10 == 0) {
                                        ComplexityAnalyzer.LOGGER.info("[ATOMIC] {} - {} progress: {}/{} chunks",
                                                workerName, task.biome().location().getPath(),
                                                newCount, targetChunks);
                                    }
                                }
                            } else if (actualBiome != null) {
                                String otherBiomeKey = task.dimension().location() + "|" + actualBiome;
                                resultsByBiome.computeIfAbsent(otherBiomeKey, k -> new CopyOnWriteArrayList<>())
                                        .add(snapshot);
                                ComplexityAnalyzer.LOGGER.debug("[ATOMIC] {} - bonus chunk for {} at [{}, {}]",
                                        workerName, actualBiome.getPath(), snapshot.chunkX(), snapshot.chunkZ());
                            }
                        }
                    }
                } finally {
                    pendingSlots.release();
                    taskPhaser.arriveAndDeregister();
                }
            });
        }

        awaitPendingRequests(workerName, taskPhaser);

        trimExcessSnapshots(biomeSnapshots, targetChunks);

        ComplexityAnalyzer.LOGGER.info("[ATOMIC] {} - finished {}: saved {} chunks (found {} in {} attempts)",
                workerName, task.biome().location().getPath(),
                Math.min(biomeSnapshots.size(), targetChunks),
                foundCount.get(), attemptCount.get());
    }

    private ChunkPos findNextChunk(SpiralChunkSearcher searcher, AtomicInteger attemptCount) {
        for (int i = 0; i < 100; i++) {
            ChunkPos candidate = searcher.next();
            attemptCount.incrementAndGet();
            if (attemptedChunks.add(candidate.toLong())) {
                return candidate;
            }
        }
        return null;
    }

    private void awaitPendingRequests(String workerName, Phaser phaser) {
        try {
            int phase = phaser.arrive();
            phaser.awaitAdvanceInterruptibly(phase, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ComplexityAnalyzer.LOGGER.warn("[ATOMIC] {} - interrupted while waiting for pending requests", workerName);
        } catch (TimeoutException e) {
            ComplexityAnalyzer.LOGGER.warn("[ATOMIC] {} - timeout waiting for pending requests (30s)", workerName);
        }
    }

    private void trimExcessSnapshots(List<ChunkSnapshot> snapshots, int targetCount) {
        while (snapshots.size() > targetCount) {
            snapshots.removeLast();
        }
    }

    private void atomicCompletionHandler(
            CountDownLatch allWorkersDone,
            ConcurrentHashMap<String, CopyOnWriteArrayList<ChunkSnapshot>> resultsByBiome,
            long startTime
    ) {
        try {
            boolean finished = allWorkersDone.await(60, TimeUnit.MINUTES);
            if (!finished) {
                notifier.logWarn("[ATOMIC] Scan timed out after 60 minutes.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifier.logWarn("[ATOMIC] Scan interrupted.");
        }

        notifier.logInfo("[ATOMIC] All workers finished. Saving results...");
        resultsByBiome.forEach((biomeKey, snapshots) -> {
            if (!snapshots.isEmpty()) {
                String[] parts = biomeKey.split("\\|");
                ResourceLocation dim = ResourceLocation.parse(parts[0]);
                ResourceLocation biome = ResourceLocation.parse(parts[1]);
                database.appendReconData(dim, biome, new ArrayList<>(snapshots));
                notifier.logInfo(String.format("[ATOMIC] Saved %d chunks for %s", snapshots.size(), biome.getPath()));
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        notifier.logInfo(String.format("[ATOMIC] Reconnaissance complete in %.2f seconds.", duration / 1000.0));

        server.execute(() -> {
            if (isShutdown.get() || stopRequested.get()) {
                scanPhase = ScanMetadata.ScanPhase.IDLE;
                taskQueue.clear();
                stopRequested.set(false);
                notifier.notifyReconnaissanceFinished(true);
            } else {
                finishReconnaissance(false);
            }
        });
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
                notifier.logWarn("Could not find biome " + task.biome().location() + ". Skipping.");
                currentTask = null;
            }
            isProcessingChunk.set(false);
        }, server).exceptionally(ex -> {
            ComplexityAnalyzer.LOGGER.error("Error in startNextTask for biome search", ex);
            currentTask = null;
            isProcessingChunk.set(false);
            return null;
        });

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

        worldScanner.processChunk(task.dimension(), task.biome(), nextPos, (resultOpt, success) -> {
            if (isShutdown.get()) {
                isProcessingChunk.set(false);
                return;
            }

            if (success && resultOpt.isPresent()) {
                WorldScanner.ScanResult result = resultOpt.get();
                ChunkSnapshot snapshot = result.snapshot();
                ResourceLocation actualBiome = result.actualBiome();

                if (database.analyzeSnapshotForRecon(snapshot)) {
                    ResourceLocation targetBiome = task.biome().location();

                    if (actualBiome != null && actualBiome.equals(targetBiome)) {
                        pristineSnapshotsForCurrentTask.add(snapshot);
                        consecutiveScanFailures.set(0);
                    } else if (actualBiome != null) {
                        database.appendReconData(task.dimension().location(), actualBiome, List.of(snapshot));
                        ComplexityAnalyzer.LOGGER.debug("Bonus chunk for {} at [{}, {}]",
                                actualBiome.getPath(), snapshot.chunkX(), snapshot.chunkZ());
                        consecutiveScanFailures.set(0);
                    } else {
                        consecutiveScanFailures.incrementAndGet();
                    }
                } else {
                    consecutiveScanFailures.incrementAndGet();
                }
            } else {
                consecutiveScanFailures.incrementAndGet();
            }
            isProcessingChunk.set(false);
        });

        notifier.logProgress(true, getStatus());
    }

    private void finishCurrentTask() {
        ScanTask task = currentTask;
        if (task == null) return;

        if (!pristineSnapshotsForCurrentTask.isEmpty()) {
            notifier.logInfo(String.format("Finished reconnaissance for biome %s, found %d new candidates. Saving...",
                    task.biome().location(), pristineSnapshotsForCurrentTask.size()));
            database.appendReconData(task.dimension().location(), task.biome().location(),
                    new ArrayList<>(pristineSnapshotsForCurrentTask));
        }
        currentTask = null;
        currentSearcher = null;
        pristineSnapshotsForCurrentTask.clear();
    }

    private void handleRelocation() {
        ScanTask task = currentTask;
        SpiralChunkSearcher searcher = currentSearcher;

        if (task == null || searcher == null || isShutdown.get()) return;

        notifier.logWarn("Too many failures for " + task.biome().location() + ". Relocating...");
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
                if (currentSearcherRef != null) currentSearcherRef.startAt(newStartPos.get().x, newStartPos.get().z);
                consecutiveScanFailures.set(0);
            } else {
                notifier.logError("Could not relocate for " + task.biome().location() + ". Skipping.");
                finishCurrentTask();
            }
            isProcessingChunk.set(false);
        }, server).exceptionally(ex -> {
            ComplexityAnalyzer.LOGGER.error("Error during relocation", ex);
            isProcessingChunk.set(false);
            return null;
        });
    }

    private void finishReconnaissance(boolean wasStopped) {
        finishCurrentTask();
        scanPhase = ScanMetadata.ScanPhase.IDLE;
        taskQueue.clear();
        stopRequested.set(false);
        notifier.notifyReconnaissanceFinished(wasStopped);

        if (!wasStopped && !isShutdown.get()) beginGlobalRefinement(null);
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
                allReconPaths.forEach((dim, biomeMap) ->
                        biomeMap.forEach((biome, path) -> {
                            if (isShutdown.get()) return;

                            try (Stream<ChunkSnapshot> snapshotStream = database.streamReconFile(path)) {
                                BiomeScanData finalData = database.refineRawDataFromStream(snapshotStream, dim);
                                if (finalData.getChunksScanned() > 0) database.saveBiomeData(dim, biome, finalData);
                            }
                        }));

                if (!isShutdown.get()) finishRefinement();
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

        if (!isShutdown.get()) analysisEngine.onGeoScanFinished();
        notifier.notifyRefinementFinished();
    }

    private boolean handleCountdown() {
        int ticks = countdownTicks;
        if (ticks > 0) {
            countdownTicks = --ticks;
            if (ticks % 20 == 0) {
                int secondsLeft = ticks / 20;
                notifier.notifyScanCountdown(secondsLeft);
                if (secondsLeft == 0) startScanInternal(scheduledChunksPerBiome, scheduledInitiator, scheduledProfile);
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

    private void handleServerOverload() {
        long now = System.currentTimeMillis();
        if (!wasPaused) {
            notifier.logInfo("Server is under heavy load (tick time > " + currentProfile.maxTickTimeMs + "ms). Geo-scan is paused.");
            wasPaused = true;
            lastPauseLogTime = now;
        } else if (now - lastPauseLogTime > LOG_THROTTLE_MS) {
            notifier.logInfo("Geo-scan still paused due to server load.");
            lastPauseLogTime = now;
        }
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
}