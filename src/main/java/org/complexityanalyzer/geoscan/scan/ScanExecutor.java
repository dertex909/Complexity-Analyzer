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

package org.complexityanalyzer.geoscan.scan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.config.ScanConfig;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.task.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

public class ScanExecutor {

    private final MinecraftServer server;
    private final GeoDatabase database;
    private final WorldScanner worldScanner;
    private final ChunkBatchProcessor batchProcessor;
    private final ScanNotifier notifier;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicInteger activeWorkerCount = new AtomicInteger(0);
    private final AtomicInteger workerIdCounter = new AtomicInteger(0);

    private volatile ScanSession currentSession = null;
    private volatile Runnable currentOnComplete = null;
    private volatile MsptMonitor msptMonitor = null;

    private final Object sessionLock = new Object();
    private final AtomicBoolean completing = new AtomicBoolean(false);

    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private final int maxWorkers;

    private final ConcurrentHashMap<ScanSession.BiomeKey, BufferedSnapshots> resultBuffers = new ConcurrentHashMap<>();

    private final AtomicInteger throttlePauseCount = new AtomicInteger(0);
    private final AtomicLong totalThrottleTimeMs = new AtomicLong(0);

    private static final class BufferedSnapshots {
        private final ArrayDeque<ChunkSnapshot> queue = new ArrayDeque<>(ScanConfig.BATCH_SAVE_THRESHOLD * 2);

        synchronized List<ChunkSnapshot> addAndDrainIfNeeded(ChunkSnapshot snapshot) {
            queue.addLast(snapshot);
            if (queue.size() < ScanConfig.BATCH_SAVE_THRESHOLD) return Collections.emptyList();
            List<ChunkSnapshot> batch = new ArrayList<>(ScanConfig.BATCH_SAVE_THRESHOLD);
            while (batch.size() < ScanConfig.BATCH_SAVE_THRESHOLD && !queue.isEmpty()) batch.add(queue.removeFirst());
            return batch;
        }

        synchronized List<ChunkSnapshot> drainAll() {
            if (queue.isEmpty()) return Collections.emptyList();
            List<ChunkSnapshot> remaining = new ArrayList<>(queue);
            queue.clear();
            return remaining;
        }
    }

    public ScanExecutor(
            MinecraftServer server,
            GeoDatabase database,
            WorldScanner worldScanner,
            ChunkBatchProcessor batchProcessor,
            ScanNotifier notifier
    ) {
        this.server = server;
        this.database = database;
        this.worldScanner = worldScanner;
        this.batchProcessor = batchProcessor;
        this.notifier = notifier;
        this.maxWorkers = ThreadPoolManager.getInstance().getParallelism();
    }

    public int getActiveWorkerCount() {
        return activeWorkerCount.get();
    }

    public int getTargetWorkerCount() {
        ScanSession session = currentSession;
        if (session == null) return 0;
        return session.getProfile().getWorkerCount(maxWorkers);
    }

    public void execute(ScanSession newSession, Runnable onComplete) {
        if (isShutdown.get()) {
            onComplete.run();
            return;
        }

        synchronized (sessionLock) {
            if (currentSession != null) currentSession.invalidate();
            for (Thread worker : workerThreads) {
                LockSupport.unpark(worker);
            }
        }

        long waitStart = System.currentTimeMillis();
        while (activeWorkerCount.get() > 0 && (System.currentTimeMillis() - waitStart) < 2000) {
            Thread.onSpinWait();
        }

        if (activeWorkerCount.get() > 0) {
            ComplexityAnalyzer.LOGGER.warn("[SCAN] {} old workers still running after 2s wait", activeWorkerCount.get());
        }

        batchProcessor.resetForNewSession();

        synchronized (sessionLock) {
            if (isShutdown.get()) {
                onComplete.run();
                return;
            }

            workerIdCounter.set(0);

            currentSession = newSession;
            currentOnComplete = onComplete;
            completing.set(false);
            worldScanner.clearStopRequest();

            msptMonitor = new MsptMonitor(server, newSession.getProfile());
            throttlePauseCount.set(0);
            totalThrottleTimeMs.set(0);

            flushAllBuffers();
            resultBuffers.clear();

            int targetWorkers = newSession.getProfile().getWorkerCount(maxWorkers);

            String msptInfo = msptMonitor.hasLimit()
                    ? String.format(", MSPT limit: %.0f", msptMonitor.getMsptLimit()) : ", no MSPT limit";

            notifier.logInfo("[SCAN] 🚀 " + newSession.getProfile().name() +
                    " MODE — " + targetWorkers + " workers" + msptInfo);

            for (int i = 0; i < targetWorkers; i++) {
                startWorker();
            }
        }
    }

    public void stop() {
        synchronized (sessionLock) {
            if (currentSession != null) {
                currentSession.invalidate();
                worldScanner.requestStop();
                flushAllBuffers();
                logThrottleStats();
                currentSession = null;
                currentOnComplete = null;
                msptMonitor = null;
            }
        }
    }

    public void updateMsptMonitor() {
        MsptMonitor monitor = msptMonitor;
        if (monitor != null) monitor.update();
    }

    public boolean isThrottled() {
        MsptMonitor monitor = msptMonitor;
        return monitor != null && monitor.isThrottled();
    }

    public float getCurrentMspt() {
        MsptMonitor monitor = msptMonitor;
        return monitor != null ? monitor.getCurrentMspt() : -1;
    }

    private void startWorker() {
        if (isShutdown.get()) return;
        activeWorkerCount.incrementAndGet();
        int workerId = workerIdCounter.getAndIncrement();

        try {
            CompletableFuture.runAsync(() -> workerLoop(workerId), ThreadPoolManager.getInstance().getComputePool());
        } catch (RejectedExecutionException e) {
            activeWorkerCount.decrementAndGet();
        }
    }

    private void workerLoop(int workerId) {
        workerThreads.add(Thread.currentThread());
        String workerName = "Worker-" + workerId;
        ComplexityAnalyzer.LOGGER.info("[SCAN] {} started", workerName);

        final ScanSession mySession = currentSession;

        try {
            while (!isShutdown.get() && !Thread.currentThread().isInterrupted()) {
                ScanSession session = currentSession;

                if (session != mySession || session == null || !session.isValid()) {
                    ComplexityAnalyzer.LOGGER.debug("[SCAN] {} stopping — session changed or invalidated", workerName);
                    break;
                }

                if (waitIfThrottled(workerName, mySession)) {
                    if (currentSession != mySession || !mySession.isValid()) {
                        ComplexityAnalyzer.LOGGER.debug("[SCAN] {} stopping — session invalidated during pause", workerName);
                        break;
                    }
                    continue;
                }

                if (!session.hasAnyNeeds()) {
                    tryComplete(session);
                    break;
                }

                List<ResourceLocation> dims = session.getDimensionsWithNeeds();
                if (dims.isEmpty()) {
                    tryComplete(session);
                    break;
                }

                ResourceLocation dimId = dims.get(ThreadLocalRandom.current().nextInt(dims.size()));
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);

                ResourceLocation biomeId = session.getRandomNeededBiome(dimId);
                if (biomeId == null) continue;

                ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biomeId);

                scanArea(workerName, mySession, dimension, biomeKey);
            }
        } catch (Exception e) {
            if (!isShutdown.get()) {
                ComplexityAnalyzer.LOGGER.error("[SCAN] {} crashed", workerName, e);
            }
        } finally {
            workerThreads.remove(Thread.currentThread());
            activeWorkerCount.decrementAndGet();
            ComplexityAnalyzer.LOGGER.info("[SCAN] {} stopped", workerName);
        }
    }

    private boolean waitIfThrottled(String workerName, ScanSession mySession) {
        MsptMonitor monitor = msptMonitor;
        if (monitor == null || !monitor.isThrottled()) return false;

        long pauseStart = System.currentTimeMillis();
        int pauseCount = throttlePauseCount.incrementAndGet();

        if (pauseCount <= activeWorkerCount.get()) {
            ComplexityAnalyzer.LOGGER.debug("[SCAN] {} paused — MSPT {} > {}", workerName,
                    String.format("%.1f", monitor.getCurrentMspt()),
                    String.format("%.1f", monitor.getMsptLimit()));
        }

        while (monitor.isThrottled() && !isShutdown.get()) {
            ScanSession current = currentSession;
            if (current != mySession || current == null || !current.isValid()) {
                ComplexityAnalyzer.LOGGER.debug("[SCAN] {} woke up — session changed", workerName);
                break;
            }

            LockSupport.parkNanos(100_000_000L);
            if (Thread.currentThread().isInterrupted()) break;
        }

        long pauseDuration = System.currentTimeMillis() - pauseStart;
        totalThrottleTimeMs.addAndGet(pauseDuration);
        ComplexityAnalyzer.LOGGER.debug("[SCAN] {} resumed after {}ms pause", workerName, pauseDuration);

        return true;
    }

    private void scanArea(String workerName, ScanSession mySession,
                          ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey) {

        ResourceLocation dimId = dimension.location();
        ResourceLocation biomeId = biomeKey.location();

        if (shouldStop(mySession)) return;
        if (mySession.doesNotNeedBiome(dimId, biomeId)) return;
        if (waitAndCheckStop(workerName, mySession)) return;

        Optional<ChunkPos> startPos = worldScanner.findBiomeLocation(dimension, biomeKey, false);

        if (startPos.isEmpty() || shouldStop(mySession)) return;

        ScanContext ctx = new ScanContext(workerName, mySession, dimension, biomeKey, dimId, biomeId);
        ctx.searcher.startAt(startPos.get().x, startPos.get().z);

        performScan(ctx);
        logResults(ctx);
    }

    private boolean shouldStop(ScanSession mySession) {
        return !mySession.isValid() || isShutdown.get() || currentSession != mySession;
    }

    private boolean waitAndCheckStop(String workerName, ScanSession mySession) {
        if (waitIfThrottled(workerName, mySession)) return shouldStop(mySession);
        return false;
    }

    private class ScanContext {
        final String workerName;
        final ScanSession mySession;
        final ResourceKey<Level> dimension;
        final ResourceKey<Biome> biomeKey;
        final ResourceLocation dimId;
        final ResourceLocation biomeId;
        final SpiralChunkSearcher searcher = new SpiralChunkSearcher();
        final Map<ResourceLocation, Integer> foundByBiome = new HashMap<>();
        final int maxScannedBudget;
        final int emptyBatchTolerance;
        final int stagnantBatchTolerance;

        int scanned = 0;
        int found = 0;
        int emptyBatches = 0;
        int stagnantBatches = 0;
        int relocations = 0;

        ScanContext(String workerName, ScanSession mySession,
                    ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey,
                    ResourceLocation dimId, ResourceLocation biomeId) {
            this.workerName = workerName;
            this.mySession = mySession;
            this.dimension = dimension;
            this.biomeKey = biomeKey;
            this.dimId = dimId;
            this.biomeId = biomeId;
            this.maxScannedBudget = calculateMaxScannedBudget(mySession);
            this.emptyBatchTolerance = calculateEmptyBatchTolerance(mySession);
            this.stagnantBatchTolerance = calculateStagnantBatchTolerance(mySession);
        }

        boolean canContinue() {
            return scanned < maxScannedBudget && mySession.isValid() && currentSession == mySession && !isShutdown.get()
                    && mySession.hasAnyNeeds();
        }

        boolean needsBiome() {
            return !mySession.doesNotNeedBiome(dimId, biomeId);
        }
    }

    private void performScan(ScanContext ctx) {
        while (ctx.canContinue()) {
            if (waitAndCheckStop(ctx.workerName, ctx.mySession)) break;
            if (!ctx.needsBiome()) break;
            List<ChunkPos> batch = collectBatch(ctx);
            if (batch.isEmpty()) {
                if (handleEmptyBatch(ctx)) break;
                continue;
            }
            ctx.emptyBatches = 0;
            if (waitAndCheckStop(ctx.workerName, ctx.mySession)) break;
            int claimed = processBatchResults(ctx, batch);
            if (claimed > 0) {
                ctx.stagnantBatches = 0;
                continue;
            }
            if (handleStagnantBatch(ctx)) break;
        }
    }

    private List<ChunkPos> collectBatch(ScanContext ctx) {
        int batchSize = calculateBatchSize(ctx.mySession);
        List<ChunkPos> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize && ctx.scanned < ctx.maxScannedBudget; i++) {
            ChunkPos pos = ctx.searcher.next();
            ctx.scanned++;
            if (ctx.mySession.tryMarkChunk(ctx.dimId, pos)) batch.add(pos);
        }
        return batch;
    }

    private boolean handleEmptyBatch(ScanContext ctx) {
        ctx.emptyBatches++;
        if (ctx.emptyBatches < ctx.emptyBatchTolerance) return false;
        return relocateSearch(ctx, false);
    }

    private boolean handleStagnantBatch(ScanContext ctx) {
        ctx.stagnantBatches++;
        if (ctx.stagnantBatches < ctx.stagnantBatchTolerance) return false;
        return relocateSearch(ctx, true);
    }

    private boolean relocateSearch(ScanContext ctx, boolean forceFreshSearch) {
        if (waitAndCheckStop(ctx.workerName, ctx.mySession)) return true;
        if (shouldStop(ctx.mySession)) return true;
        boolean allowCachedLocation = !forceFreshSearch && (ctx.relocations % 3 != 2);
        Optional<ChunkPos> newPos = worldScanner.findBiomeLocation(
                ctx.dimension,
                ctx.biomeKey,
                true,
                allowCachedLocation
        );
        if (newPos.isEmpty() || shouldStop(ctx.mySession)) return true;

        ctx.searcher.startAt(newPos.get().x, newPos.get().z);
        ctx.emptyBatches = 0;
        ctx.stagnantBatches = 0;
        ctx.scanned = 0;
        ctx.relocations++;
        return false;
    }

    private int processBatchResults(ScanContext ctx, List<ChunkPos> batch) {
        List<ChunkBatchProcessor.ScanResult> results = batchProcessor.processBatch(ctx.dimension, batch, ctx.mySession);
        int claimed = 0;
        for (ChunkBatchProcessor.ScanResult result : results) {
            if (shouldStop(ctx.mySession)) break;
            if (isThrottled() && waitAndCheckStop(ctx.workerName, ctx.mySession)) break;
            if (processResult(ctx, result)) claimed++;
        }
        return claimed;
    }

    private boolean processResult(ScanContext ctx, ChunkBatchProcessor.ScanResult result) {
        ChunkSnapshot snapshot = result.snapshot();
        ResourceLocation biome = result.biome();
        if (!database.analyzeSnapshotForRecon(snapshot)) return false;
        if (ctx.mySession.tryClaimChunk(ctx.dimId, biome)) {
            worldScanner.recordDiscoveredBiomeChunk(ctx.dimension, biome, new ChunkPos(snapshot.chunkX(), snapshot.chunkZ()));
            saveToBuffer(ctx.dimId, biome, snapshot);
            ctx.mySession.recordChunkScanned();
            ctx.found++;
            ctx.foundByBiome.merge(biome, 1, Integer::sum);
            return true;
        }
        return false;
    }

    private void logResults(ScanContext ctx) {
        if (ctx.found == 0) return;
        String biomeStats = ctx.foundByBiome.entrySet().stream()
                .map(e -> e.getKey().getPath() + ":" + e.getValue())
                .collect(Collectors.joining(", "));

        ComplexityAnalyzer.LOGGER.info("[SCAN] {} scanned around {}: {} chunks ({})",
                ctx.workerName, ctx.biomeId.getPath(), ctx.found, biomeStats);
    }

    private int calculateMaxScannedBudget(ScanSession session) {
        int chunksPerBiome = Math.max(1, session.getChunksPerBiome());
        return switch (session.getProfile()) {
            case FULL -> Math.max(2048, Math.min(chunksPerBiome * 2, 4096));
            case MOST -> Math.max(1024, Math.min(chunksPerBiome * 2, 2048));
            case HALF -> Math.max(512, Math.min(chunksPerBiome * 2, 1024));
            case QUARTER -> 256;
        };
    }

    private int calculateEmptyBatchTolerance(ScanSession session) {
        return switch (session.getProfile()) {
            case FULL -> 4;
            case MOST -> 3;
            case HALF, QUARTER -> 2;
        };
    }

    private int calculateStagnantBatchTolerance(ScanSession session) {
        return switch (session.getProfile()) {
            case FULL -> 4;
            case MOST -> 3;
            case HALF, QUARTER -> 2;
        };
    }

    private int calculateBatchSize(ScanSession session) {
        MsptMonitor monitor = msptMonitor;
        if (monitor == null || !monitor.hasLimit()) {
            return switch (session.getProfile()) {
                case FULL -> 256;
                case MOST -> 192;
                case HALF -> 128;
                case QUARTER -> 64;
            };
        }
        float currentMspt = monitor.getCurrentMspt();
        float limit = monitor.getMsptLimit();

        if (currentMspt > limit * 0.9f) {
            return 16;
        } else if (currentMspt > limit * 0.7f) {
            return 32;
        } else if (currentMspt > limit * 0.5f) {
            return 64;
        } else {
            return 128;
        }
    }

    private void tryComplete(ScanSession session) {
        if (!completing.compareAndSet(false, true)) return;

        synchronized (sessionLock) {
            if (currentSession != session) {
                completing.set(false);
                return;
            }

            session.invalidate();

            Runnable callback = currentOnComplete;
            currentSession = null;
            currentOnComplete = null;

            flushAllBuffers();
            logThrottleStats();
            msptMonitor = null;

            notifier.logInfo("[SCAN] ✅ Complete!");

            if (callback != null && !isShutdown.get()) try {
                server.execute(callback);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("[SCAN] onComplete failed", e);
            }
        }
    }

    private void logThrottleStats() {
        int pauses = throttlePauseCount.get();
        if (pauses > 0) ComplexityAnalyzer.LOGGER.info(
                "[SCAN] Throttle stats: {} pauses, total pause time: {}ms", pauses, totalThrottleTimeMs.get());
    }

    private void saveToBuffer(ResourceLocation dim, ResourceLocation biome, ChunkSnapshot snapshot) {
        ScanSession.BiomeKey key = new ScanSession.BiomeKey(dim, biome);
        BufferedSnapshots buffer = resultBuffers.computeIfAbsent(key, k -> new BufferedSnapshots());
        List<ChunkSnapshot> batch = buffer.addAndDrainIfNeeded(snapshot);
        if (!batch.isEmpty()) database.appendReconData(key.dim(), key.biome(), batch);
    }

    private void flushAllBuffers() {
        resultBuffers.forEach((key, buffer) -> {
            List<ChunkSnapshot> remaining = buffer.drainAll();
            if (!remaining.isEmpty()) database.appendReconData(key.dim(), key.biome(), remaining);
        });
    }

    public void shutdown() {
        isShutdown.set(true);
        synchronized (sessionLock) {
            if (currentSession != null) {
                currentSession.invalidate();
                currentSession = null;
            }
            currentOnComplete = null;
            msptMonitor = null;
        }

        for (Thread worker : workerThreads) {
            LockSupport.unpark(worker);
            worker.interrupt();
        }
        flushAllBuffers();
    }
}
