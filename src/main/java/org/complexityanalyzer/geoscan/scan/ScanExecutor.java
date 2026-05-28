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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.config.ScanConfig;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.task.ChunkBatchProcessor;
import org.complexityanalyzer.geoscan.task.ScanNotifier;
import org.complexityanalyzer.geoscan.task.SpiralChunkSearcher;
import org.complexityanalyzer.geoscan.task.WorldScanner;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class ScanExecutor {

    private final MinecraftServer server;
    private final GeoDatabase database;
    private final WorldScanner worldScanner;
    private final ChunkBatchProcessor batchProcessor;
    private final ScanNotifier notifier;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicInteger activeWorkerCount = new AtomicInteger(0);

    private final AtomicReference<SessionContext> sessionRef = new AtomicReference<>(null);

    private final ConcurrentHashMap<Thread, Boolean> workerThreads = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<ScanSession.BiomeKey, BufferedSnapshots> resultBuffers = new ConcurrentHashMap<>();

    private final AtomicInteger throttlePauseCount = new AtomicInteger(0);
    private final AtomicLong totalThrottleTimeMs = new AtomicLong(0);

    private final ExecutorService analysisExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService workerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private record SessionContext(ScanSession session, Runnable onComplete, EmergencyManager.MsptTracker monitor) {
    }

    private static final class BufferedSnapshots {
        private final ConcurrentLinkedQueue<ChunkSnapshot> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger(0);

        ObjectArrayList<ChunkSnapshot> addAndDrainIfNeeded(ChunkSnapshot snapshot) {
            queue.offer(snapshot);
            int currentSize = size.incrementAndGet();
            if (currentSize < ScanConfig.BATCH_SAVE_THRESHOLD) return null;
            return drainUpTo();
        }

        ObjectArrayList<ChunkSnapshot> drainUpTo() {
            ObjectArrayList<ChunkSnapshot> result = new ObjectArrayList<>(ScanConfig.BATCH_SAVE_THRESHOLD);
            for (int i = 0; i < ScanConfig.BATCH_SAVE_THRESHOLD; i++) {
                ChunkSnapshot s = queue.poll();
                if (s == null) break;
                size.decrementAndGet();
                result.add(s);
            }
            return result;
        }

        ObjectArrayList<ChunkSnapshot> drainAll() {
            ObjectArrayList<ChunkSnapshot> result = new ObjectArrayList<>();
            ChunkSnapshot s;
            while ((s = queue.poll()) != null) {
                size.decrementAndGet();
                result.add(s);
            }
            return result;
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

        SessionContext oldCtx = sessionRef.get();
        if (oldCtx != null) {
            oldCtx.session().invalidate();
            unparkAllWorkers();
        }

        long waitStart = System.currentTimeMillis();
        while (activeWorkerCount.get() > 0 && (System.currentTimeMillis() - waitStart) < 2000) {
            Thread.onSpinWait();
        }

        if (activeWorkerCount.get() > 0) {
            ComplexityAnalyzer.LOGGER.warn("[SCAN] {} old scan tasks still running after 2s wait", activeWorkerCount.get());
        }

        batchProcessor.resetForNewSession();

        if (isShutdown.get()) {
            onComplete.run();
            return;
        }

        worldScanner.clearStopRequest();
        throttlePauseCount.set(0);
        totalThrottleTimeMs.set(0);

        flushAllBuffers();
        resultBuffers.clear();

        EmergencyManager.MsptTracker monitor = new EmergencyManager.MsptTracker(server, newSession.getProfile());
        SessionContext newCtx = new SessionContext(newSession, onComplete, monitor);
        sessionRef.set(newCtx);

        String msptInfo = monitor.hasLimit()
                ? Component.translatable("complexityanalyzer.log.scan.mspt_limit", monitor.getMsptLimit()).getString()
                : Component.translatable("complexityanalyzer.log.scan.no_mspt_limit").getString();

        notifier.logInfo(Component.translatable("complexityanalyzer.log.scan.profile_info",
                newSession.getProfile().displayName.toUpperCase(), msptInfo).getString());

        startWorker(newCtx);
    }

    public void stop() {
        SessionContext current = sessionRef.getAndSet(null);
        if (current == null) return;
        current.session().invalidate();
        worldScanner.requestStop();
        flushAllBuffers();
        logThrottleStats();
    }

    public void updateMsptMonitor() {
        SessionContext ctx = sessionRef.get();
        if (ctx != null) ctx.monitor().update();
    }

    public boolean isThrottled() {
        SessionContext ctx = sessionRef.get();
        return ctx != null && ctx.monitor().isThrottled();
    }

    public float getCurrentMspt() {
        SessionContext ctx = sessionRef.get();
        return ctx != null ? ctx.monitor().getCurrentMspt() : -1;
    }

    private void unparkAllWorkers() {
        for (Thread worker : workerThreads.keySet()) {
            LockSupport.unpark(worker);
        }
    }

    private void startWorker(SessionContext ctx) {
        if (isShutdown.get()) return;
        activeWorkerCount.incrementAndGet();

        try {
            workerExecutor.execute(() -> workerLoop(ctx));
        } catch (RejectedExecutionException e) {
            activeWorkerCount.decrementAndGet();
        }
    }

    private void workerLoop(SessionContext myCtx) {
        Thread self = Thread.currentThread();
        workerThreads.put(self, Boolean.TRUE);
        ComplexityAnalyzer.LOGGER.info("[SCAN] Worker started");

        final ScanSession mySession = myCtx.session();

        try {
            while (!isShutdown.get() && !self.isInterrupted()) {
                SessionContext current = sessionRef.get();
                if (current != myCtx || !mySession.isValid()) {
                    ComplexityAnalyzer.LOGGER.debug("[SCAN] Stopping — session changed or invalidated");
                    break;
                }

                if (checkMemoryAndThrottling(myCtx)) {
                    if (sessionRef.get() != myCtx || !mySession.isValid()) {
                        ComplexityAnalyzer.LOGGER.debug("[SCAN] Stopping — session invalidated during pause");
                        break;
                    }
                    continue;
                }

                if (!mySession.hasAnyNeeds()) {
                    tryComplete(myCtx);
                    break;
                }

                ObjectArrayList<ResourceLocation> dims = mySession.getDimensionsWithNeeds();
                if (dims.isEmpty()) {
                    tryComplete(myCtx);
                    break;
                }

                ResourceLocation dimId = dims.get(ThreadLocalRandom.current().nextInt(dims.size()));
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);

                ResourceLocation biomeId = mySession.getRandomNeededBiome(dimId);
                if (biomeId == null) continue;

                ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biomeId);

                scanArea(myCtx, dimension, biomeKey);
            }
        } catch (Exception e) {
            if (!isShutdown.get()) ComplexityAnalyzer.LOGGER.error("[SCAN] Worker crashed", e);
        } finally {
            workerThreads.remove(self);
            activeWorkerCount.decrementAndGet();
            ComplexityAnalyzer.LOGGER.info("[SCAN] Worker stopped");
        }
    }

    private boolean checkMemoryAndThrottling(SessionContext myCtx) {
        boolean throttled = myCtx.monitor().isThrottled();

        if (!throttled) return false;

        long pauseStart = System.currentTimeMillis();
        throttlePauseCount.incrementAndGet();

        ScanSession mySession = myCtx.session();
        while (myCtx.monitor().isThrottled() && !isShutdown.get()) {
            if (sessionRef.get() != myCtx || !mySession.isValid()) break;

            LockSupport.parkNanos(200_000_000L);
            if (Thread.currentThread().isInterrupted()) break;
        }

        long pauseDuration = System.currentTimeMillis() - pauseStart;
        totalThrottleTimeMs.addAndGet(pauseDuration);
        return true;
    }

    private void scanArea(SessionContext myCtx, ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey) {
        ScanSession mySession = myCtx.session();
        ResourceLocation dimId = dimension.location();
        ResourceLocation biomeId = biomeKey.location();

        if (shouldStop(myCtx)) return;
        if (mySession.doesNotNeedBiome(dimId, biomeId)) return;
        if (checkMemoryAndThrottling(myCtx)) return;

        Optional<ChunkPos> startPos = worldScanner.findBiomeLocation(dimension, biomeKey, false);

        if (startPos.isEmpty() || shouldStop(myCtx)) {
            mySession.abandonBiome(dimId, biomeId);
            return;
        }

        ScanContext ctx = new ScanContext(myCtx, dimension, biomeKey, dimId, biomeId);
        ctx.searcher.startAt(startPos.get().x, startPos.get().z);

        performScan(ctx);
        logResults(ctx);
    }

    private boolean shouldStop(SessionContext myCtx) {
        return !myCtx.session().isValid() || isShutdown.get() || sessionRef.get() != myCtx;
    }

    private boolean waitAndCheckStop(SessionContext myCtx) {
        if (checkMemoryAndThrottling(myCtx)) return shouldStop(myCtx);
        return false;
    }

    private final class ScanContext {
        record AnalysisBatchResult(ObjectArrayList<ChunkBatchProcessor.ScanResult> results, int claimed) {
        }

        final SessionContext myCtx;
        final ScanSession mySession;
        final ResourceKey<Level> dimension;
        final ResourceKey<Biome> biomeKey;
        final ResourceLocation dimId;
        final ResourceLocation biomeId;
        final SpiralChunkSearcher searcher = new SpiralChunkSearcher();
        final Object2IntOpenHashMap<ResourceLocation> foundByBiome = new Object2IntOpenHashMap<>();
        final int maxScannedBudget;
        final int emptyBatchTolerance;
        final int stagnantBatchTolerance;
        final int maxPendingAnalysisBatches;
        final ObjectArrayList<CompletableFuture<AnalysisBatchResult>> pendingAnalysis = new ObjectArrayList<>();
        final Long2ObjectOpenHashMap<ResourceLocation> transientAreaBiomeCache = new Long2ObjectOpenHashMap<>();

        int scanned = 0;
        int found = 0;
        int emptyBatches = 0;
        int stagnantBatches = 0;
        int relocations = 0;

        ScanContext(SessionContext myCtx, ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey,
                    ResourceLocation dimId, ResourceLocation biomeId) {
            this.myCtx = myCtx;
            this.mySession = myCtx.session();
            this.dimension = dimension;
            this.biomeKey = biomeKey;
            this.dimId = dimId;
            this.biomeId = biomeId;
            foundByBiome.defaultReturnValue(0);

            EmergencyManager.MsptTracker monitor = myCtx.monitor();
            boolean limited = monitor.hasLimit();
            float mspt = monitor.getCurrentMspt();

            var policy = mySession.getProfile().policy(mySession.getChunksPerBiome(), mspt, limited);
            this.maxScannedBudget = policy.maxScannedBudget();
            this.emptyBatchTolerance = policy.emptyBatchTolerance();
            this.stagnantBatchTolerance = policy.stagnantBatchTolerance();
            this.maxPendingAnalysisBatches = policy.maxPendingAnalysisBatches();
        }

        boolean canContinue() {
            return scanned < maxScannedBudget && mySession.isValid() && sessionRef.get() == myCtx && !isShutdown.get()
                    && mySession.hasAnyNeeds();
        }
    }

    private void performScan(ScanContext ctx) {
        while (ctx.canContinue()) {
            drainCompletedAnalysis(ctx, false);
            if (waitAndCheckStop(ctx.myCtx)) break;
            LongArrayList batch = collectBatch(ctx);
            if (batch.isEmpty()) {
                if (handleEmptyBatch(ctx)) break;
                continue;
            }
            ctx.emptyBatches = 0;
            if (waitAndCheckStop(ctx.myCtx)) break;
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
            CompletableFuture<ScanContext.AnalysisBatchResult> next = ctx.pendingAnalysis.getFirst();
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
            ObjectArrayList<ChunkBatchProcessor.ScanResult> results = batchResult.results();
            for (int i = 0, n = results.size(); i < n; i++) {
                if (shouldStop(ctx.myCtx)) return;
                processResult(ctx, results.get(i));
            }
        }
    }

    private LongArrayList collectBatch(ScanContext ctx) {
        int batchSize = calculateBatchSize();
        LongArrayList batch = new LongArrayList(batchSize);
        for (int i = 0; i < batchSize && ctx.scanned < ctx.maxScannedBudget; i++) {
            long packed = ctx.searcher.nextPacked();
            ctx.scanned++;
            if (ctx.mySession.tryMarkChunkPacked(ctx.dimId, packed)) batch.add(packed);
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
        if (waitAndCheckStop(ctx.myCtx)) return true;
        if (shouldStop(ctx.myCtx)) return true;
        drainCompletedAnalysis(ctx, true);
        boolean allowCachedLocation = !forceFreshSearch && (ctx.relocations % 3 != 2);
        Optional<ChunkPos> newPos = worldScanner.findBiomeLocation(
                ctx.dimension,
                ctx.biomeKey,
                true,
                allowCachedLocation
        );
        if (newPos.isEmpty() || shouldStop(ctx.myCtx)) {
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

    private int enqueueBatchAnalysis(ScanContext ctx, LongArrayList batch) {
        ObjectArrayList<ChunkBatchProcessor.LoadedChunk> loadedChunks = batchProcessor.loadBatch(
                ctx.dimension,
                batch,
                ctx.mySession,
                ctx.transientAreaBiomeCache
        );
        if (loadedChunks.isEmpty()) return 0;

        CompletableFuture<ScanContext.AnalysisBatchResult> future = CompletableFuture.supplyAsync(() -> {
            ObjectArrayList<ChunkBatchProcessor.ScanResult> results = batchProcessor.analyzeLoadedBatch(loadedChunks, ctx.mySession);
            int claimed = 0;
            for (int i = 0, n = results.size(); i < n; i++) {
                if (!ctx.mySession.isValid()) break;
                ResourceLocation biome = results.get(i).biome();
                if (!ctx.mySession.doesNotNeedBiome(ctx.dimId, biome)) claimed++;
            }
            return new ScanContext.AnalysisBatchResult(results, claimed);
        }, analysisExecutor);

        ctx.pendingAnalysis.add(future);
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
            ctx.foundByBiome.addTo(biome, 1);
        }
    }

    private void logResults(ScanContext ctx) {
        if (ctx.found == 0) return;
        StringBuilder biomeStats = new StringBuilder();
        ObjectIterator<Object2IntMap.Entry<ResourceLocation>> it = ctx.foundByBiome.object2IntEntrySet().fastIterator();
        boolean first = true;
        while (it.hasNext()) {
            Object2IntMap.Entry<ResourceLocation> entry = it.next();
            if (!first) biomeStats.append(", ");
            biomeStats.append(entry.getKey().getPath()).append(':').append(entry.getIntValue());
            first = false;
        }

        ComplexityAnalyzer.LOGGER.info("[SCAN] Scanned around {}: {} chunks ({})", ctx.biomeId.getPath(), ctx.found, biomeStats);
    }

    private int calculateBatchSize() {
        SessionContext ctx = sessionRef.get();
        if (ctx == null) return 1;
        EmergencyManager.MsptTracker monitor = ctx.monitor();
        boolean limited = monitor.hasLimit();
        float mspt = monitor.getCurrentMspt();
        return ctx.session().getProfile().policy(ctx.session().getChunksPerBiome(), mspt, limited).batchSize();
    }

    private void tryComplete(SessionContext myCtx) {
        if (!sessionRef.compareAndSet(myCtx, null)) return;

        myCtx.session().invalidate();

        flushAllBuffers();
        logThrottleStats();

        notifier.logInfo(Component.translatable("complexityanalyzer.log.scan.complete").getString());

        Runnable callback = myCtx.onComplete();
        if (callback != null && !isShutdown.get()) try {
            server.execute(callback);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[SCAN] onComplete failed", e);
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
        ObjectArrayList<ChunkSnapshot> batch = buffer.addAndDrainIfNeeded(snapshot);
        if (batch != null && !batch.isEmpty()) database.appendReconData(key.dim(), key.biome(), batch);
    }

    private void flushAllBuffers() {
        for (var entry : resultBuffers.entrySet()) {
            ScanSession.BiomeKey key = entry.getKey();
            ObjectArrayList<ChunkSnapshot> remaining = entry.getValue().drainAll();
            if (!remaining.isEmpty()) database.appendReconData(key.dim(), key.biome(), remaining);
        }
    }

    public void shutdown() {
        isShutdown.set(true);
        SessionContext ctx = sessionRef.getAndSet(null);
        if (ctx != null) ctx.session().invalidate();

        for (Thread worker : workerThreads.keySet()) {
            LockSupport.unpark(worker);
            worker.interrupt();
        }
        analysisExecutor.shutdownNow();
        workerExecutor.shutdownNow();
        flushAllBuffers();
    }
}