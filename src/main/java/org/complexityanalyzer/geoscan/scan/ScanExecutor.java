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

package org.complexityanalyzer.geoscan.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
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

import java.util.concurrent.*;
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

        var oldCtx = sessionRef.get();
        if (oldCtx != null) {
            oldCtx.session().invalidate();
            unparkAllWorkers();
        }

        long waitStart = System.currentTimeMillis();
        while (activeWorkerCount.get() > 0 && (System.currentTimeMillis() - waitStart) < 2000) Thread.onSpinWait();

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

        var monitor = new EmergencyManager.MsptTracker(server, newSession.getProfile());
        var newCtx = new SessionContext(newSession, onComplete, monitor);
        sessionRef.set(newCtx);

        Component msptInfo = monitor.hasLimit()
                ? Component.translatable("complexityanalyzer.log.scan.mspt_limit", monitor.getMsptLimit())
                : Component.translatable("complexityanalyzer.log.scan.no_mspt_limit");

        notifier.logInfo(Component.translatable("complexityanalyzer.log.scan.profile_info",
                newSession.getProfile().displayName.toUpperCase(), msptInfo));

        startWorker(newCtx);
    }

    public void stop() {
        var current = sessionRef.getAndSet(null);
        if (current == null) return;
        current.session().invalidate();
        worldScanner.requestStop();
        flushAllBuffers();
        logThrottleStats();
    }

    public void updateMsptMonitor() {
        var ctx = sessionRef.get();
        if (ctx != null) ctx.monitor().update();
    }

    public boolean isThrottled() {
        var ctx = sessionRef.get();
        return ctx != null && ctx.monitor().isThrottled();
    }

    public float getCurrentMspt() {
        var ctx = sessionRef.get();
        return ctx != null ? ctx.monitor().getCurrentMspt() : -1;
    }

    private void unparkAllWorkers() {
        for (var worker : workerThreads.keySet()) LockSupport.unpark(worker);
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
        var self = Thread.currentThread();
        workerThreads.put(self, Boolean.TRUE);
        ComplexityAnalyzer.LOGGER.info("[SCAN] Worker started");

        final var mySession = myCtx.session();

        try {
            while (!isShutdown.get() && !self.isInterrupted()) {
                var current = sessionRef.get();
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

                var dims = mySession.getDimensionsWithNeeds();
                if (dims.isEmpty()) {
                    tryComplete(myCtx);
                    break;
                }

                var dimId = dims.get(ThreadLocalRandom.current().nextInt(dims.size()));
                var dimension = ResourceKey.create(Registries.DIMENSION, dimId);

                var biomeId = mySession.getRandomNeededBiome(dimId);
                if (biomeId == null) continue;

                var biomeKey = ResourceKey.create(Registries.BIOME, biomeId);

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

        var mySession = myCtx.session();
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
        var mySession = myCtx.session();
        var dimId = dimension.location();
        var biomeId = biomeKey.location();

        if (shouldStop(myCtx)) return;
        if (mySession.doesNotNeedBiome(dimId, biomeId)) return;
        if (checkMemoryAndThrottling(myCtx)) return;

        var startPos = worldScanner.findBiomeLocation(dimension, biomeKey, false);

        if (startPos.isEmpty() || shouldStop(myCtx)) {
            mySession.abandonBiome(dimId, biomeId);
            return;
        }

        var ctx = new ScanContext(myCtx, dimension, biomeKey, dimId, biomeId);
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

    private void performScan(ScanContext ctx) {
        while (ctx.canContinue()) {
            drainCompletedAnalysis(ctx, false);
            if (waitAndCheckStop(ctx.myCtx)) break;
            var batch = collectBatch(ctx);
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
            var next = ctx.pendingAnalysis.getFirst();
            if (!waitForAll && !next.isDone()) break;
            ctx.pendingAnalysis.removeFirst();
            ScanContext.AnalysisBatchResult batchResult;
            try {
                batchResult = next.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                var cause = e.getCause() != null ? e.getCause() : e;
                ComplexityAnalyzer.LOGGER.warn("[SCAN] Analysis batch failed: {}", cause.getMessage());
                continue;
            }

            if (batchResult.claimed() > 0) ctx.stagnantBatches = 0;
            var results = batchResult.results();
            for (int i = 0, n = results.size(); i < n; i++) {
                if (shouldStop(ctx.myCtx)) return;
                processResult(ctx, results.get(i));
            }
        }
    }

    private LongArrayList collectBatch(ScanContext ctx) {
        int batchSize = calculateBatchSize();
        var batch = new LongArrayList(batchSize);
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
        var newPos = worldScanner.findBiomeLocation(
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
        var loadedChunks = batchProcessor.loadBatch(
                ctx.dimension,
                batch,
                ctx.mySession,
                ctx.transientAreaBiomeCache
        );
        if (loadedChunks.isEmpty()) return 0;

        var future = CompletableFuture.supplyAsync(() -> {
            var results = batchProcessor.analyzeLoadedBatch(loadedChunks, ctx.mySession);
            int claimed = 0;
            for (int i = 0, n = results.size(); i < n; i++) {
                if (!ctx.mySession.isValid()) break;
                var biome = results.get(i).biome();
                if (!ctx.mySession.doesNotNeedBiome(ctx.dimId, biome)) claimed++;
            }
            return new ScanContext.AnalysisBatchResult(results, claimed);
        }, analysisExecutor);

        ctx.pendingAnalysis.add(future);
        while (ctx.pendingAnalysis.size() >= ctx.maxPendingAnalysisBatches) drainCompletedAnalysis(ctx, true);
        return loadedChunks.size();
    }

    private void processResult(ScanContext ctx, ChunkBatchProcessor.ScanResult result) {
        var snapshot = result.snapshot();
        var biome = result.biome();
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
        var biomeStats = new StringBuilder();
        var it = ctx.foundByBiome.object2IntEntrySet().fastIterator();
        boolean first = true;
        while (it.hasNext()) {
            var entry = it.next();
            if (!first) biomeStats.append(", ");
            biomeStats.append(entry.getKey().getPath()).append(':').append(entry.getIntValue());
            first = false;
        }

        ComplexityAnalyzer.LOGGER.info("[SCAN] Scanned around {}: {} chunks ({})", ctx.biomeId.getPath(), ctx.found, biomeStats);
    }

    private int calculateBatchSize() {
        var ctx = sessionRef.get();
        if (ctx == null) return 1;
        var monitor = ctx.monitor();
        boolean limited = monitor.hasLimit();
        float mspt = monitor.getCurrentMspt();
        return ctx.session().getProfile().policy(ctx.session().getChunksPerBiome(), mspt, limited).batchSize();
    }

    private void tryComplete(SessionContext myCtx) {
        if (!sessionRef.compareAndSet(myCtx, null)) return;

        myCtx.session().invalidate();

        flushAllBuffers();
        logThrottleStats();

        notifier.logInfo(Component.translatable("complexityanalyzer.log.scan.complete"));

        var callback = myCtx.onComplete();
        if (callback != null && !isShutdown.get()) try {
            server.execute(callback);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[SCAN] onComplete failed", e);
        }
    }

    private void logThrottleStats() {
        int pauses = throttlePauseCount.get();
        if (pauses > 0) notifier.logInfo(
                Component.translatable("complexityanalyzer.log.scan.throttle_stats", pauses, totalThrottleTimeMs.get()));
    }

    private void saveToBuffer(ResourceLocation dim, ResourceLocation biome, ChunkSnapshot snapshot) {
        var key = new ScanSession.BiomeKey(dim, biome);
        var buffer = resultBuffers.computeIfAbsent(key, k -> new BufferedSnapshots());
        var batch = buffer.addAndDrainIfNeeded(snapshot);
        if (batch != null && !batch.isEmpty()) database.appendReconData(key.dim(), key.biome(), batch);
    }

    private void flushAllBuffers() {
        for (var entry : resultBuffers.entrySet()) {
            var key = entry.getKey();
            var remaining = entry.getValue().drainAll();
            if (!remaining.isEmpty()) database.appendReconData(key.dim(), key.biome(), remaining);
        }
    }

    public void shutdown() {
        isShutdown.set(true);
        var ctx = sessionRef.getAndSet(null);
        if (ctx != null) ctx.session().invalidate();

        for (var worker : workerThreads.keySet()) {
            LockSupport.unpark(worker);
            worker.interrupt();
        }
        analysisExecutor.shutdownNow();
        workerExecutor.shutdownNow();
        flushAllBuffers();
    }

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
            var result = new ObjectArrayList<ChunkSnapshot>(ScanConfig.BATCH_SAVE_THRESHOLD);
            for (int i = 0; i < ScanConfig.BATCH_SAVE_THRESHOLD; i++) {
                var s = queue.poll();
                if (s == null) break;
                size.decrementAndGet();
                result.add(s);
            }
            return result;
        }

        ObjectArrayList<ChunkSnapshot> drainAll() {
            var result = new ObjectArrayList<ChunkSnapshot>();
            ChunkSnapshot s;
            while ((s = queue.poll()) != null) {
                size.decrementAndGet();
                result.add(s);
            }
            return result;
        }
    }

    private final class ScanContext {
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

            var monitor = myCtx.monitor();
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

        record AnalysisBatchResult(ObjectArrayList<ChunkBatchProcessor.ScanResult> results, int claimed) {
        }
    }
}