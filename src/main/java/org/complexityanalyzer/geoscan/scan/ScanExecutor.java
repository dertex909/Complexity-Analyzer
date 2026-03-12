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
import java.util.concurrent.locks.LockSupport;

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
    private final Object sessionLock = new Object();
    private final AtomicBoolean completing = new AtomicBoolean(false);

    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private final int maxWorkers;

    private final ConcurrentHashMap<String, Queue<ChunkSnapshot>> resultBuffers = new ConcurrentHashMap<>();

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

    public void execute(ScanSession newSession, Runnable onComplete) {
        if (isShutdown.get()) {
            onComplete.run();
            return;
        }

        synchronized (sessionLock) {
            if (currentSession != null) currentSession.invalidate();

            currentSession = newSession;
            currentOnComplete = onComplete;
            completing.set(false);
            worldScanner.clearStopRequest();

            flushAllBuffers();
            resultBuffers.clear();

            int targetWorkers = newSession.getProfile().getWorkerCount(maxWorkers);
            notifier.logInfo(String.format("[SCAN] 🚀 %s MODE — %d workers", newSession.getProfile().name(), targetWorkers));

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
                currentSession = null;
                currentOnComplete = null;
            }
        }
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

        try {
            while (!isShutdown.get() && !Thread.currentThread().isInterrupted()) {
                ScanSession session = currentSession;
                if (session == null || !session.isValid()) {
                    if (isShutdown.get()) break;
                    LockSupport.parkNanos(50_000_000L);
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

                scanArea(workerName, session, dimension, biomeKey);
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

    private void scanArea(String workerName, ScanSession session, ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey) {
        if (!session.isValid() || isShutdown.get()) return;

        ResourceLocation dimId = dimension.location();
        ResourceLocation biomeId = biomeKey.location();

        if (session.NotNeedsBiome(dimId, biomeId)) return;

        Optional<ChunkPos> startPos = worldScanner.findBiomeLocation(dimension, biomeKey, false);
        if (startPos.isEmpty() || !session.isValid()) return;

        SpiralChunkSearcher searcher = new SpiralChunkSearcher();
        searcher.startAt(startPos.get().x, startPos.get().z);

        int scanned = 0;
        int found = 0;
        int emptyBatches = 0;
        int batchSize = 128;
        int maxScanned = 384;
        Map<ResourceLocation, Integer> foundByBiome = new HashMap<>();

        while (scanned < maxScanned && session.isValid() && !isShutdown.get() && session.hasAnyNeeds()) {
            if (session.NotNeedsBiome(dimId, biomeId)) break;

            List<ChunkPos> batch = new ArrayList<>(batchSize);

            for (int i = 0; i < batchSize && scanned < maxScanned; i++) {
                ChunkPos pos = searcher.next();
                scanned++;
                if (session.tryMarkChunk(pos)) batch.add(pos);
            }

            if (batch.isEmpty()) {
                emptyBatches++;
                if (emptyBatches >= 2) {
                    Optional<ChunkPos> newPos = worldScanner.findBiomeLocation(dimension, biomeKey, true);
                    if (newPos.isEmpty()) break;
                    searcher.startAt(newPos.get().x, newPos.get().z);
                    emptyBatches = 0;
                    scanned = 0;
                }
                continue;
            }

            emptyBatches = 0;

            List<ChunkBatchProcessor.ScanResult> results = batchProcessor.processBatch(dimension, batch, session);

            for (ChunkBatchProcessor.ScanResult result : results) {
                if (!session.isValid()) break;
                ChunkSnapshot snapshot = result.snapshot();
                ResourceLocation biome = result.biome();
                if (!database.analyzeSnapshotForRecon(snapshot)) continue;
                if (session.tryClaimChunk(dimId, biome)) {
                    saveToBuffer(dimId, biome, snapshot);
                    found++;
                    foundByBiome.merge(biome, 1, Integer::sum);
                }
            }
        }

        if (found > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[SCAN] %s scanned around %s: %d chunks (", workerName, biomeId.getPath(), found));
            List<String> biomeStats = new ArrayList<>();
            for (var entry : foundByBiome.entrySet()) {
                biomeStats.add(entry.getKey().getPath() + ":" + entry.getValue());
            }
            sb.append(String.join(", ", biomeStats)).append(")");
            ComplexityAnalyzer.LOGGER.info(sb.toString());
        }
    }

    private void tryComplete(ScanSession session) {
        if (!completing.compareAndSet(false, true)) return;
        synchronized (sessionLock) {
            if (currentSession != session) {
                completing.set(false);
                return;
            }

            Runnable callback = currentOnComplete;
            currentSession = null;
            currentOnComplete = null;

            flushAllBuffers();
            notifier.logInfo("[SCAN] ✅ Complete!");

            if (callback != null && !isShutdown.get()) {
                try {
                    server.execute(callback);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.warn("[SCAN] onComplete failed", e);
                }
            }
        }
    }

    private void saveToBuffer(ResourceLocation dim, ResourceLocation biome, ChunkSnapshot snapshot) {
        String key = dim + "|" + biome;
        Queue<ChunkSnapshot> buffer = resultBuffers.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        buffer.add(snapshot);

        if (buffer.size() >= ScanConfig.BATCH_SAVE_THRESHOLD) {
            List<ChunkSnapshot> batch = new ArrayList<>();
            ChunkSnapshot item;
            while (batch.size() < ScanConfig.BATCH_SAVE_THRESHOLD && (item = buffer.poll()) != null) {
                batch.add(item);
            }
            if (!batch.isEmpty()) database.appendReconData(dim, biome, batch);
        }
    }

    private void flushAllBuffers() {
        resultBuffers.forEach((key, buffer) -> {
            List<ChunkSnapshot> remaining = new ArrayList<>();
            ChunkSnapshot item;
            while ((item = buffer.poll()) != null) remaining.add(item);
            if (!remaining.isEmpty()) try {
                String[] parts = key.split("\\|");
                database.appendReconData(ResourceLocation.parse(parts[0]), ResourceLocation.parse(parts[1]), remaining);
            } catch (Exception ignored) {
            }
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
        }
        for (Thread worker : workerThreads) {
            LockSupport.unpark(worker);
            worker.interrupt();
        }
        flushAllBuffers();
    }
}