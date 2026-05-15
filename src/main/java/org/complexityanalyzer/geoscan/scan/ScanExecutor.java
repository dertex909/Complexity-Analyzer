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

package org.complexityanalyzer.geoscan.scan;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.EmergencyManager;
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

    private volatile ScanSession currentSession = null;
    private volatile Runnable currentOnComplete = null;
    private volatile MsptMonitor msptMonitor = null;

    private final Object sessionLock = new Object();
    private final AtomicBoolean completing = new AtomicBoolean(false);

    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<ScanSession.BiomeKey, BufferedSnapshots> resultBuffers = new ConcurrentHashMap<>();

    private final AtomicInteger throttlePauseCount = new AtomicInteger(0);
    private final AtomicLong totalThrottleTimeMs = new AtomicLong(0);
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Complexity-Scan-Analysis");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

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
            ComplexityAnalyzer.LOGGER.warn("[SCAN] {} old scan tasks still running after 2s wait", activeWorkerCount.get());
        }

        batchProcessor.resetForNewSession();

        synchronized (sessionLock) {
            if (isShutdown.get()) {
                onComplete.run();
                return;
            }

            currentSession = newSession;
            currentOnComplete = onComplete;
            completing.set(false);
            worldScanner.clearStopRequest();

            msptMonitor = new MsptMonitor(server, newSession.getProfile());
            throttlePauseCount.set(0);
            totalThrottleTimeMs.set(0);

            flushAllBuffers();
            resultBuffers.clear();

            String msptInfo = msptMonitor.hasLimit()
                    ? Component.translatable("complexityanalyzer.log.scan.mspt_limit", msptMonitor.getMsptLimit()).getString()
                    : Component.translatable("complexityanalyzer.log.scan.no_mspt_limit").getString();

            notifier.logInfo(Component.translatable("complexityanalyzer.log.scan.profile_info",
                    newSession.getProfile().displayName.toUpperCase(), msptInfo).getString());

            startWorker();
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

        try {
            CompletableFuture.runAsync(this::workerLoop, ThreadPoolManager.getInstance().getComputePool());
        } catch (RejectedExecutionException e) {
            activeWorkerCount.decrementAndGet();
        }
    }

    private void workerLoop() {
        workerThreads.add(Thread.currentThread());
        ComplexityAnalyzer.LOGGER.info("[SCAN] Worker started");

        final ScanSession mySession = currentSession;

        try {
            while (!isShutdown.get() && !Thread.currentThread().isInterrupted()) {
                ScanSession session = currentSession;

                if (session != mySession || session == null || !session.isValid()) {
                    ComplexityAnalyzer.LOGGER.debug("[SCAN] Stopping — session changed or invalidated");
                    break;
                }

                if (checkMemoryAndThrottling(mySession)) {
                    if (currentSession != mySession || !mySession.isValid()) {
                        ComplexityAnalyzer.LOGGER.debug("[SCAN] Stopping — session invalidated during pause");
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

                scanArea(mySession, dimension, biomeKey);
            }
        } catch (Exception e) {
            if (!isShutdown.get()) ComplexityAnalyzer.LOGGER.error("[SCAN] Worker crashed", e);
        } finally {
            workerThreads.remove(Thread.currentThread());
            activeWorkerCount.decrementAndGet();
            ComplexityAnalyzer.LOGGER.info("[SCAN] Worker stopped");
        }
    }

    private boolean checkMemoryAndThrottling(ScanSession mySession) {
        if (MemoryMonitor.isMemoryCritical()) {
            EmergencyManager.panic("Heap usage critical (" + String.format("%.1f%%", MemoryMonitor.getUsedMemoryRatio() * 100) + ")");
            return true;
        }

        boolean throttled = false;
        if (MemoryMonitor.isMemoryPressureHigh()) {
            throttled = true;
            if (throttlePauseCount.get() % 10 == 0) MemoryMonitor.logMemoryStatus();
        } else if (isThrottled()) {
            throttled = true;
        }

        if (!throttled) return false;

        long pauseStart = System.currentTimeMillis();
        throttlePauseCount.incrementAndGet();

        while ((MemoryMonitor.isMemoryPressureHigh() || isThrottled()) && !isShutdown.get()) {
            if (MemoryMonitor.isMemoryCritical()) {
                EmergencyManager.panic("Heap usage critical during pause");
                return true;
            }

            ScanSession current = currentSession;
            if (current != mySession || current == null || !current.isValid()) break;

            LockSupport.parkNanos(200_000_000L);
            if (Thread.currentThread().isInterrupted()) break;
        }

        long pauseDuration = System.currentTimeMillis() - pauseStart;
        totalThrottleTimeMs.addAndGet(pauseDuration);
        return true;
    }

    private void scanArea(ScanSession mySession,
                          ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey) {

        ResourceLocation dimId = dimension.location();
        ResourceLocation biomeId = biomeKey.location();

        if (shouldStop(mySession)) return;
        if (mySession.doesNotNeedBiome(dimId, biomeId)) return;
        if (checkMemoryAndThrottling(mySession)) return;

        Optional<ChunkPos> startPos = worldScanner.findBiomeLocation(dimension, biomeKey, false);

        if (startPos.isEmpty() || shouldStop(mySession)) {
            mySession.abandonBiome(dimId, biomeId);
            return;
        }

        ScanContext ctx = new ScanContext(mySession, dimension, biomeKey, dimId, biomeId);
        ctx.searcher.startAt(startPos.get().x, startPos.get().z);

        performScan(ctx);
        logResults(ctx);
    }

    private boolean shouldStop(ScanSession mySession) {
        return !mySession.isValid() || isShutdown.get() || currentSession != mySession;
    }

    private boolean waitAndCheckStop(ScanSession mySession) {
        if (checkMemoryAndThrottling(mySession)) return shouldStop(mySession);
        return false;
    }

    private class ScanContext {
        record AnalysisBatchResult(List<ChunkBatchProcessor.ScanResult> results, int claimed) {
        }

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
        final int maxPendingAnalysisBatches;
        final ArrayDeque<CompletableFuture<AnalysisBatchResult>> pendingAnalysis = new ArrayDeque<>();
        final HashMap<Long, ResourceLocation> transientAreaBiomeCache = new HashMap<>();

        int scanned = 0;
        int found = 0;
        int emptyBatches = 0;
        int stagnantBatches = 0;
        int relocations = 0;

        ScanContext(ScanSession mySession, ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey,
                    ResourceLocation dimId, ResourceLocation biomeId) {
            this.mySession = mySession;
            this.dimension = dimension;
            this.biomeKey = biomeKey;
            this.dimId = dimId;
            this.biomeId = biomeId;

            MsptMonitor monitor = msptMonitor;
            boolean limited = monitor != null && monitor.hasLimit();
            float mspt = monitor != null ? monitor.getCurrentMspt() : 0f;

            var policy = mySession.getProfile().policy(mySession.getChunksPerBiome(), mspt, limited);
            this.maxScannedBudget = policy.maxScannedBudget();
            this.emptyBatchTolerance = policy.emptyBatchTolerance();
            this.stagnantBatchTolerance = policy.stagnantBatchTolerance();
            this.maxPendingAnalysisBatches = policy.maxPendingAnalysisBatches();
        }

        boolean canContinue() {
            return scanned < maxScannedBudget && mySession.isValid() && currentSession == mySession && !isShutdown.get()
                    && mySession.hasAnyNeeds();
        }
    }

    private void performScan(ScanContext ctx) {
        while (ctx.canContinue()) {
            drainCompletedAnalysis(ctx, false);
            if (waitAndCheckStop(ctx.mySession)) break;
            List<ChunkPos> batch = collectBatch(ctx);
            if (batch.isEmpty()) {
                if (handleEmptyBatch(ctx)) break;
                continue;
            }
            ctx.emptyBatches = 0;
            if (waitAndCheckStop(ctx.mySession)) break;
            int useful = enqueueBatchAnalysis(ctx, batch);
            if (useful > 0) {
                ctx.stagnantBatches = 0;
            } else if (handleStagnantBatch(ctx)) {
                break;
            }
        }

        drainCompletedAnalysis(ctx, true);
    }

    private void drainCompletedAnalysis(ScanContext ctx, boolean waitForAll) {
        while (!ctx.pendingAnalysis.isEmpty()) {
            CompletableFuture<ScanContext.AnalysisBatchResult> next = ctx.pendingAnalysis.peekFirst();
            if (!waitForAll && !next.isDone()) break;
            ctx.pendingAnalysis.removeFirst();
            ScanContext.AnalysisBatchResult batchResult;
            try {
                batchResult = next.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                ComplexityAnalyzer.LOGGER.warn("[SCAN] Analysis batch failed: {}", cause.getMessage());
                continue;
            }

            if (batchResult.claimed() > 0) ctx.stagnantBatches = 0;
            for (ChunkBatchProcessor.ScanResult result : batchResult.results()) {
                if (shouldStop(ctx.mySession)) return;
                processResult(ctx, result);
            }
        }
    }

    private List<ChunkPos> collectBatch(ScanContext ctx) {
        int batchSize = calculateBatchSize();
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
        if (waitAndCheckStop(ctx.mySession)) return true;
        if (shouldStop(ctx.mySession)) return true;
        drainCompletedAnalysis(ctx, true);
        boolean allowCachedLocation = !forceFreshSearch && (ctx.relocations % 3 != 2);
        Optional<ChunkPos> newPos = worldScanner.findBiomeLocation(
                ctx.dimension,
                ctx.biomeKey,
                true,
                allowCachedLocation
        );
        if (newPos.isEmpty() || shouldStop(ctx.mySession)) {
            ctx.mySession.abandonBiome(ctx.dimId, ctx.biomeId);
            return true;
        }

        ctx.searcher.startAt(newPos.get().x, newPos.get().z);
        ctx.transientAreaBiomeCache.clear();
        ctx.emptyBatches = 0;
        ctx.stagnantBatches = 0;
        ctx.scanned = 0;
        ctx.relocations++;
        return false;
    }

    private int enqueueBatchAnalysis(ScanContext ctx, List<ChunkPos> batch) {
        List<ChunkBatchProcessor.LoadedChunk> loadedChunks = batchProcessor.loadBatch(
                ctx.dimension,
                batch,
                ctx.mySession,
                ctx.transientAreaBiomeCache
        );
        if (loadedChunks.isEmpty()) return 0;

        CompletableFuture<ScanContext.AnalysisBatchResult> future = CompletableFuture.supplyAsync(() -> {
            List<ChunkBatchProcessor.ScanResult> results = batchProcessor.analyzeLoadedBatch(loadedChunks, ctx.mySession);
            int claimed = 0;
            for (ChunkBatchProcessor.ScanResult result : results) {
                if (!ctx.mySession.isValid()) break;
                ResourceLocation biome = result.biome();
                if (!ctx.mySession.doesNotNeedBiome(ctx.dimId, biome)) claimed++;
            }
            return new ScanContext.AnalysisBatchResult(results, claimed);
        }, analysisExecutor);

        ctx.pendingAnalysis.addLast(future);
        while (ctx.pendingAnalysis.size() >= ctx.maxPendingAnalysisBatches) {
            drainCompletedAnalysis(ctx, true);
        }
        return loadedChunks.size();
    }

    private void processResult(ScanContext ctx, ChunkBatchProcessor.ScanResult result) {
        ChunkSnapshot snapshot = result.snapshot();
        ResourceLocation biome = result.biome();
        if (!database.analyzeSnapshotForRecon(snapshot)) return;
        if (ctx.mySession.tryClaimChunk(ctx.dimId, biome)) {
            worldScanner.recordDiscoveredBiomeChunk(ctx.dimension, biome, new ChunkPos(snapshot.chunkX(), snapshot.chunkZ()));
            saveToBuffer(ctx.dimId, biome, snapshot);
            ctx.mySession.recordChunkScanned();
            ctx.found++;
            ctx.foundByBiome.merge(biome, 1, Integer::sum);
        }
    }

    private void logResults(ScanContext ctx) {
        if (ctx.found == 0) return;
        String biomeStats = ctx.foundByBiome.entrySet().stream()
                .map(e -> e.getKey().getPath() + ":" + e.getValue())
                .collect(Collectors.joining(", "));

        ComplexityAnalyzer.LOGGER.info("[SCAN] Scanned around {}: {} chunks ({})", ctx.biomeId.getPath(), ctx.found, biomeStats);
    }

    private int calculateBatchSize() {
        ScanSession session = currentSession;
        if (session == null) return 1;
        MsptMonitor monitor = msptMonitor;
        boolean limited = monitor != null && monitor.hasLimit();
        float mspt = monitor != null ? monitor.getCurrentMspt() : 0f;
        return session.getProfile().policy(session.getChunksPerBiome(), mspt, limited).batchSize();
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

            notifier.logInfo(Component.translatable("complexityanalyzer.log.scan.complete").getString());

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
                Component.translatable("complexityanalyzer.log.scan.throttle_stats", pauses, totalThrottleTimeMs.get()).getString());
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
        analysisExecutor.shutdownNow();
        flushAllBuffers();
    }
}