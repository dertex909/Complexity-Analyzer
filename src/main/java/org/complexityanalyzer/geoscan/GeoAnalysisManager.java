package org.complexityanalyzer.geoscan;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private int tickCounter = 0;

    private int countdownTicks = -1;
    private int scheduledChunksPerBiome = 0;
    private String scheduledInitiator = "";

    private ScanProfile scheduledProfile = ScanProfile.LITE;
    private ScanProfile currentProfile = ScanProfile.LITE;

    private final Queue<ScanTask> taskQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> attemptedChunks = ConcurrentHashMap.newKeySet();
    private int totalTasks = 0;
    private int tasksCompleted = 0;

    private @Nullable ScanTask currentTask;
    private SpiralChunkSearcher currentSearcher;
    private final List<ChunkSnapshot> pristineSnapshotsForCurrentTask = new ArrayList<>();
    private int consecutiveScanFailures = 0;

    public GeoAnalysisManager(MinecraftServer server, GeoDatabase database, AnalysisEngine engine) {
        this.server = server;
        this.database = database;
        this.analysisEngine = engine;
        this.worldScanner = new WorldScanner(server);
        this.notifier = new ScanNotifier(server);
        NeoForge.EVENT_BUS.register(this);
    }

    public void startInitialScanIfNeeded() {
        Executor executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot start initial scan, executor is not ready!");
            return;
        }

        executor.execute(() -> {
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
        Executor executor = analysisEngine.getBackgroundExecutor();
        if (executor == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot run ATOMIC scan, executor is not available!");
            return;
        }

        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            notifier.logInfo("[ATOMIC] Starting blocking scan...");

            List<ScanTask> preparedTasks = prepareScanTasks(chunksPerBiome);
            if (preparedTasks.isEmpty()) {
                server.execute(notifier::notifyDatabaseIsUpToDate);
                return;
            }

            scanPhase = ScanMetadata.ScanPhase.RECONNAISSANCE;
            database.setScanPhase(ScanMetadata.ScanPhase.RECONNAISSANCE);
            taskQueue.addAll(preparedTasks);
            totalTasks = taskQueue.size();
            tasksCompleted = 0;
            stopRequested.set(false);
            attemptedChunks.clear();
            currentTask = null;

            while ((this.currentTask = taskQueue.poll()) != null) {
                if (stopRequested.get()) {
                    notifier.logInfo("[ATOMIC] Stop requested, aborting remaining tasks.");
                    break;
                }
                tasksCompleted++;

                pristineSnapshotsForCurrentTask.clear();
                consecutiveScanFailures = 0;

                ScanTask task = this.currentTask;

                Optional<ChunkPos> startPosOpt = worldScanner.findBiomeLocation(task.dimension(), task.biome(), false);
                if (startPosOpt.isEmpty()) {
                    notifier.logWarn(String.format("[ATOMIC] Could not find location for %s, skipping.", task.biome().location()));
                    continue;
                }

                SpiralChunkSearcher searcher = new SpiralChunkSearcher();
                searcher.startAt(startPosOpt.get().x, startPosOpt.get().z);
                int attempts = 0;
                int relocateTries = 0;

                while (pristineSnapshotsForCurrentTask.size() < task.chunksToFind() && attempts < 10000 && relocateTries < 5 && !stopRequested.get()) {
                    if (attempts > 0 && attempts % 250 == 0) {
                        Optional<ChunkPos> newStart = worldScanner.findBiomeLocation(task.dimension(), task.biome(), true);
                        if (newStart.isPresent()) {
                            searcher.startAt(newStart.get().x, newStart.get().z);
                            relocateTries++;
                        } else {
                            break;
                        }
                    }

                    ChunkPos currentPos = searcher.next();
                    if (!attemptedChunks.add(currentPos.toLong())) continue;

                    var future = new CompletableFuture<Optional<ChunkSnapshot>>();
                    worldScanner.processChunk(task.dimension(), task.biome(), currentPos, (snapshotOpt, success) -> {
                        if (success && snapshotOpt.isPresent() && database.analyzeSnapshotForRecon(snapshotOpt.get())) {
                            future.complete(snapshotOpt);
                        } else {
                            future.complete(Optional.empty());
                        }
                    });

                    try {
                        future.get().ifPresent(pristineSnapshotsForCurrentTask::add);
                    } catch (Exception e) {
                        ComplexityAnalyzer.LOGGER.warn("[ATOMIC] Error processing chunk future", e);
                    }
                    attempts++;
                }

                if (!pristineSnapshotsForCurrentTask.isEmpty()) {
                    database.appendReconData(task.dimension().location(), task.biome().location(), new ArrayList<>(pristineSnapshotsForCurrentTask));
                    notifier.logInfo(String.format("[ATOMIC] Task %d/%d: %s... found %d candidates.", tasksCompleted, totalTasks, task.biome().location().getPath(), pristineSnapshotsForCurrentTask.size()));
                }
            }

            this.currentTask = null;

            if (stopRequested.get()) {
                server.execute(() -> {
                    scanPhase = ScanMetadata.ScanPhase.IDLE;
                    taskQueue.clear();
                    stopRequested.set(false);
                    notifier.notifyReconnaissanceFinished(true);
                });
            } else {
                long duration = System.currentTimeMillis() - startTime;
                notifier.logInfo(String.format("[ATOMIC] Reconnaissance phase complete in %.2f seconds.", duration / 1000.0));
                server.execute(() -> finishReconnaissance(false));
            }
        });
    }

    public void startScanImmediately(int chunksPerBiome, String initiatorName, ScanProfile profile) {
        server.execute(() -> {
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
        if(isCountdownActive()) {
            return String.format("Scan scheduled in %s mode, starting in %d seconds...", scheduledProfile.name().toLowerCase(), countdownTicks / 20);
        }
        return switch (scanPhase) {
            case IDLE -> "Idle";
            case RECONNAISSANCE -> {
                if (currentTask == null) yield "Phase 1: Reconnaissance (Initializing next task...)";
                yield String.format("Phase 1: Reconnaissance. Task %d/%d: %s (%d/%d)",
                        tasksCompleted, totalTasks, currentTask.biome().location().getPath(),
                        pristineSnapshotsForCurrentTask.size(), currentTask.chunksToFind());
            }
            case REFINING -> "Phase 2: Refining all collected data...";
            case COMPLETE -> "Complete";
        };
    }

    public boolean isScanning() {
        return scanPhase == ScanMetadata.ScanPhase.RECONNAISSANCE || scanPhase == ScanMetadata.ScanPhase.REFINING;
    }

    public boolean isCountdownActive() {
        return this.countdownTicks > 0;
    }


    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (handleCountdown()) return;
        if (handleStopRequest()) return;

        if (scanPhase != ScanMetadata.ScanPhase.RECONNAISSANCE || isProcessingChunk.get()) return;

        if (isServerHealthy()) {
            if (tickCounter % 200 == 0) {
                notifier.logInfo("Server is under heavy load (tick time > " + currentProfile.maxTickTimeMs + "ms). Geo-scan is paused.");
            }
            return;
        }

        if (isServerHealthy() || !isTickScheduled()) return;

        if (currentTask == null) {
            if (!startNextTask()) {
                finishReconnaissance(false);
            }
            return;
        }

        if (pristineSnapshotsForCurrentTask.size() >= currentTask.chunksToFind()) {
            finishCurrentTask();
            return;
        }

        if (consecutiveScanFailures >= currentProfile.relocateFailureThreshold) {
            handleRelocation();
            return;
        }

        processNextChunk();
    }

    private void startScanInternal(int chunksPerBiome, String initiatorName, ScanProfile profile) {

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
                List<ScanTask> tasks = prepareScanTasks(chunksPerBiome);

                server.execute(() -> {
                    if (tasks.isEmpty()) {
                        notifier.notifyDatabaseIsUpToDate();
                        this.scanPhase = ScanMetadata.ScanPhase.IDLE;
                        return;
                    }

                    database.setScanPhase(ScanMetadata.ScanPhase.RECONNAISSANCE);
                    taskQueue.addAll(tasks);
                    totalTasks = tasks.size();
                    tasksCompleted = 0;
                    attemptedChunks.clear();
                    notifier.notifyScanPreparationComplete(totalTasks);
                });
            });
        }
    }
    private List<ScanTask> prepareScanTasks(int chunksPerBiome) {
        database.loadAll();
        List<ScanTask> tasksToQueue = new ArrayList<>();
        var biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        ComplexityAnalyzer.LOGGER.debug("[Prepare] Starting to build scan tasks. Chunks per biome: {}", chunksPerBiome);

        for (ServerLevel level : server.getAllLevels()) {
            if (stopRequested.get()) break;
            TagKey<Biome> dimensionTag = getTagForDimension(level.dimension());
            if (dimensionTag == null) continue;

            for (Holder.Reference<Biome> biomeHolder : biomeRegistry.holders().toList()) {
                if (biomeHolder.is(dimensionTag)) {
                    ResourceKey<Biome> biomeKey = biomeHolder.key();
                    int finalChunks = database.getBiomeData(level.dimension().location(), biomeKey.location()).map(BiomeScanData::getChunksScanned).orElse(0);
                    int reconChunks = database.countReconChunks(level.dimension().location(), biomeKey.location());
                    int chunksNeeded = chunksPerBiome - Math.max(finalChunks, reconChunks);
                    if (chunksNeeded > 0) {
                        tasksToQueue.add(new ScanTask(level.dimension(), biomeKey, chunksNeeded));
                    }
                }
            }
        }

        ComplexityAnalyzer.LOGGER.debug("[Prepare] Found {} potential tasks. Reachability will be checked on the fly.", tasksToQueue.size());
        tasksToQueue.sort(Comparator.naturalOrder());
        return tasksToQueue;
    }

    private boolean startNextTask() {
        currentTask = taskQueue.poll();
        if (currentTask == null) {
            return false;
        }

        Optional<ChunkPos> startPos = worldScanner.findBiomeLocation(currentTask.dimension(), currentTask.biome(), false);

        if (startPos.isPresent()) {
            tasksCompleted++;
            pristineSnapshotsForCurrentTask.clear();
            consecutiveScanFailures = 0;
            currentSearcher = new SpiralChunkSearcher();
            currentSearcher.startAt(startPos.get().x, startPos.get().z);
            notifier.logInfo(String.format("Starting reconnaissance for %s (%d/%d). Chunks to find: %d. Starting at %s",
                    currentTask.biome().location().getPath(), tasksCompleted, totalTasks, currentTask.chunksToFind(), startPos.get()));
            return true;
        } else {
            notifier.logWarn("Could not find a reachable location for biome " + currentTask.biome().location() + ". Skipping.");
            currentTask = null;
            return startNextTask();
        }
    }

    private void processNextChunk() {
        if (!isProcessingChunk.compareAndSet(false, true)) return;

        final long timeBudgetNanos = 1_000_000L;
        long searchStartTime = System.nanoTime();

        ChunkPos nextPos = null;
        while(System.nanoTime() - searchStartTime < timeBudgetNanos) {
            ChunkPos candidatePos = currentSearcher.next();
            if(attemptedChunks.add(candidatePos.toLong())) {
                nextPos = candidatePos;
                break;
            }
        }

        if(nextPos == null) {
            isProcessingChunk.set(false);
            return;
        }

        worldScanner.processChunk(currentTask.dimension(), currentTask. biome(), nextPos, (snapshotOpt, success) -> {
            if (success) {
                snapshotOpt.ifPresent(snapshot -> {
                    if (database.analyzeSnapshotForRecon(snapshot)) {
                        pristineSnapshotsForCurrentTask.add(snapshot);
                        consecutiveScanFailures = 0;
                    } else {
                        consecutiveScanFailures++;
                    }
                });
            } else {
                consecutiveScanFailures++;
            }
            isProcessingChunk.set(false);
        });

        notifier.logProgress(true, getStatus());
    }

    private void finishCurrentTask() {
        if (currentTask != null && !pristineSnapshotsForCurrentTask.isEmpty()) {
            notifier.logInfo(String.format("Finished reconnaissance for biome %s, found %d new candidates. Saving...",
                    currentTask.biome().location(), pristineSnapshotsForCurrentTask.size()));
            database.appendReconData(currentTask.dimension().location(), currentTask.biome().location(), new ArrayList<>(pristineSnapshotsForCurrentTask));
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

        if (!wasStopped) {
            beginGlobalRefinement(null);
        }
    }

    public void beginGlobalRefinement(@Nullable CommandSourceStack source) {
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
            try {
                Map<ResourceLocation, Map<ResourceLocation, Path>> allReconPaths = database.getAllReconFilePaths();
                if (allReconPaths.isEmpty()) {
                    notifier.logWarn("Refinement started, but no reconnaissance data was found.");
                    finishRefinement();
                    return;
                }

                notifier.logInfo("Building heuristics from reconnaissance data...");
                database.buildHeuristicFromFiles(allReconPaths);

                notifier.logInfo("Clearing old final data...");
                database.clearFinalData();

                notifier.logInfo("Refining data for each biome...");
                allReconPaths.forEach((dim, biomeMap) -> biomeMap.forEach((biome, path) -> {
                    try (Stream<ChunkSnapshot> snapshotStream = database.streamReconFile(path)) {
                        BiomeScanData finalData = database.refineRawDataFromStream(snapshotStream, dim);
                        if (finalData.getChunksScanned() > 0) {
                            database.saveBiomeData(dim, biome, finalData);
                        }
                    }
                }));

                finishRefinement();
            } catch (IOException e) {
                notifier.logError("A critical error occurred during the refinement phase!", e);
                scanPhase = ScanMetadata.ScanPhase.IDLE;
                database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
            }
        });
    }

    private void finishRefinement() {
        notifier.logInfo("Finalizing refinement process...");
        scanPhase = ScanMetadata.ScanPhase.COMPLETE;
        database.setScanPhase(ScanMetadata.ScanPhase.COMPLETE);
        database.loadAll();
        analysisEngine.onGeoScanFinished();
        notifier.notifyRefinementFinished();
    }

    private boolean handleCountdown() {
        if (countdownTicks > 0) {
            if (--countdownTicks % 20 == 0) {
                int secondsLeft = countdownTicks / 20;
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
        notifier.logWarn("Too many consecutive scan failures for biome " + currentTask.biome().location() + ". Relocating...");
        Optional<ChunkPos> newStartPos = worldScanner.findBiomeLocation(currentTask.dimension(), currentTask.biome(), true);
        if (newStartPos.isPresent()) {
            currentSearcher.startAt(newStartPos.get().x, newStartPos.get().z);
            consecutiveScanFailures = 0;
        } else {
            notifier.logError("Could not relocate for biome " + currentTask.biome().location() + ". Skipping task.");
            finishCurrentTask();
        }
    }

    private boolean isServerHealthy() {
        return !(server.getAverageTickTimeNanos() / 1_000_000.0F <= currentProfile.maxTickTimeMs);
    }

    private boolean isTickScheduled() {
        tickCounter++;
        if (tickCounter >= currentProfile.ticksBetweenScans) {
            tickCounter = 0;
            return true;
        }
        return false;
    }

    private TagKey<Biome> getTagForDimension(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) return BiomeTags.IS_OVERWORLD;
        if (dimension.equals(Level.NETHER)) return BiomeTags.IS_NETHER;
        if (dimension.equals(Level.END)) return BiomeTags.IS_END;
        return null;
    }
    public void shutdown() {
        ComplexityAnalyzer.LOGGER.info("Shutting down GeoAnalysisManager...");
        
        this.stopRequested.set(true);

        NeoForge.EVENT_BUS.unregister(this);

        ComplexityAnalyzer.LOGGER.info("GeoAnalysisManager has been shut down.");
    }
}