package org.complexityanalyzer.geoscan.scan;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.config.ScanConfig;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
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

    private final Set<Thread> activeWorkers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile CountDownLatch activeLatch;
    private volatile ConcurrentHashMap<String, Queue<ChunkSnapshot>> resultBuffers;

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

    public void stop() {
        ComplexityAnalyzer.LOGGER.info("[SCAN] 🛑 STOP REQUESTED");
        stopRequested.set(true);
        worldScanner.requestStop();

        for (Thread worker : activeWorkers) {
            worker.interrupt();
        }
    }

    private boolean shouldStop() {
        return isShutdown.get()
                || stopRequested.get()
                || !server.isRunning()
                || Thread.currentThread().isInterrupted();
    }

    private boolean shouldPause() {
        return server.isPaused() || isShutdown.get();
    }

    private void waitWhilePaused() {
        while (server.isPaused() && !shouldStop()) {
            LockSupport.parkNanos(100_000_000L);
            if (Thread.currentThread().isInterrupted()) break;
        }
    }

    public void execute(ScanSession session, Runnable onComplete) {
        stopRequested.set(false);
        worldScanner.clearStopRequest();

        if (!session.isValid() || isShutdown.get()) {
            onComplete.run();
            return;
        }

        long startTime = System.currentTimeMillis();
        ScanProfile profile = session.getProfile();
        int totalThreads = ThreadPoolManager.getInstance().getParallelism();
        int numWorkers = profile.getWorkerCount(totalThreads);

        notifier.logInfo(String.format("[SCAN] 🚀 %s MODE — using %d/%d threads",
                profile.name(), numWorkers, totalThreads));

        ConcurrentHashMap<String, Queue<ChunkSnapshot>> buffers = new ConcurrentHashMap<>();
        this.resultBuffers = buffers;

        CountDownLatch latch = new CountDownLatch(numWorkers);
        this.activeLatch = latch;

        AtomicInteger activeCount = new AtomicInteger(numWorkers);
        ExecutorService pool = ThreadPoolManager.getInstance().getComputePool();

        for (int i = 0; i < numWorkers; i++) {
            final int workerId = i;
            try {
                CompletableFuture.runAsync(
                        () -> runWorker(workerId, session, buffers, latch, activeCount),
                        pool
                );
            } catch (RejectedExecutionException e) {
                ComplexityAnalyzer.LOGGER.warn("[SCAN] Worker {} rejected", workerId);
                latch.countDown();
                activeCount.decrementAndGet();
            }
        }

        CompletableFuture.runAsync(() -> {
            awaitCompletion(latch);
            flushAllBuffers(buffers);

            long duration = System.currentTimeMillis() - startTime;
            notifier.logInfo(String.format("[SCAN] 🎉 Complete in %.2f seconds! (%s mode, %d workers)",
                    duration / 1000.0, profile.name(), numWorkers));

            try {
                server.execute(onComplete);
            } catch (RejectedExecutionException e) {
                ComplexityAnalyzer.LOGGER.warn("[SCAN] Server rejected completion callback");
            }
        }, pool);
    }

    private void runWorker(
            int workerId,
            ScanSession session,
            ConcurrentHashMap<String, Queue<ChunkSnapshot>> buffers,
            CountDownLatch latch,
            AtomicInteger activeCount
    ) {
        Thread currentThread = Thread.currentThread();
        activeWorkers.add(currentThread);
        String workerName = "Worker-" + workerId;

        ComplexityAnalyzer.LOGGER.info("[SCAN] 🚀 {} started on {}", workerName, currentThread.getName());

        try {
            while (session.isValid() && !shouldStop()) {
                if (shouldPause()) {
                    waitWhilePaused();
                    if (shouldStop()) break;
                }

                ScanTask task = session.pollTask();
                if (task == null) break;

                processTask(workerName, task, session, buffers);
            }
        } catch (Exception e) {
            if (session.isValid() && !shouldStop()) {
                ComplexityAnalyzer.LOGGER.error("[SCAN] {} crashed", workerName, e);
            }
        } finally {
            activeWorkers.remove(currentThread);
            int remaining = activeCount.decrementAndGet();
            ComplexityAnalyzer.LOGGER.info("[SCAN] {} finished. {} workers active", workerName, remaining);
            latch.countDown();
        }
    }

    private void processTask(
            String workerName,
            ScanTask task,
            ScanSession session,
            ConcurrentHashMap<String, Queue<ChunkSnapshot>> buffers
    ) {
        if (!session.isValid() || shouldStop()) return;

        int targetChunks = task.chunksToFind();
        int taskNum = session.incrementTasksCompleted();

        ComplexityAnalyzer.LOGGER.info("[SCAN] {} - task {}/{}: {} (need {} chunks)",
                workerName, taskNum, session.getTotalTasks(),
                task.biome().location().getPath(), targetChunks);

        AtomicInteger foundCount = new AtomicInteger(0);
        AtomicInteger attemptCount = new AtomicInteger(0);
        AtomicBoolean taskDone = new AtomicBoolean(false);

        int maxAttempts = Math.min(ScanConfig.MAX_ATTEMPTS,
                Math.max(ScanConfig.MIN_ATTEMPTS, targetChunks * ScanConfig.MAX_ATTEMPTS_PER_CHUNK));

        if (shouldStop() || !session.isValid()) return;

        Optional<ChunkPos> startPos = findBiome(task, false);
        if (startPos.isEmpty() || shouldStop() || !session.isValid()) return;

        SpiralChunkSearcher searcher = new SpiralChunkSearcher();
        searcher.startAt(startPos.get().x, startPos.get().z);

        int relocations = 0;
        AtomicInteger lastFoundAt = new AtomicInteger(0);

        while (!taskDone.get() && attemptCount.get() < maxAttempts
                && session.isValid() && !shouldStop()) {

            if (shouldPause()) {
                waitWhilePaused();
                if (shouldStop()) break;
            }

            if (foundCount.get() >= targetChunks) {
                taskDone.set(true);
                break;
            }

            int attemptsSinceFind = attemptCount.get() - lastFoundAt.get();
            if (attemptsSinceFind > ScanConfig.STUCK_THRESHOLD
                    && relocations < ScanConfig.MAX_RELOCATIONS) {

                if (shouldStop() || !session.isValid()) break;

                Optional<ChunkPos> newStart = findBiome(task, true);
                if (shouldStop() || !session.isValid()) break;

                if (newStart.isPresent()) {
                    searcher.startAt(newStart.get().x, newStart.get().z);
                    relocations++;
                    lastFoundAt.set(attemptCount.get());
                }
            }

            List<ChunkPos> batch = collectBatch(searcher, session, attemptCount);
            if (batch.isEmpty()) continue;

            if (shouldStop() || !session.isValid()) break;

            List<ChunkBatchProcessor.ScanResult> results = batchProcessor
                    .processBatchSync(task.dimension(), batch);

            for (ChunkBatchProcessor.ScanResult result : results) {
                if (taskDone.get() || !session.isValid() || shouldStop()) break;

                processResult(result, task, session, buffers, foundCount, targetChunks,
                        lastFoundAt, attemptCount, workerName, taskDone);
            }
        }

        ComplexityAnalyzer.LOGGER.info("[SCAN] {} - finished {}: {} chunks in {} attempts",
                workerName, task.biome().location().getPath(), foundCount.get(), attemptCount.get());
    }

    private Optional<ChunkPos> findBiome(ScanTask task, boolean isRelocation) {
        if (shouldStop()) return Optional.empty();
        return worldScanner.findBiomeLocation(task.dimension(), task.biome(), isRelocation);
    }

    private List<ChunkPos> collectBatch(SpiralChunkSearcher searcher, ScanSession session, AtomicInteger attemptCount) {
        List<ChunkPos> batch = new ArrayList<>(ScanConfig.BATCH_SIZE);

        for (int i = 0; i < ScanConfig.CHUNK_SEARCH_BATCH && batch.size() < ScanConfig.BATCH_SIZE; i++) {
            if (!session.isValid() || shouldStop()) break;

            ChunkPos candidate = searcher.next();
            attemptCount.incrementAndGet();

            if (session.tryMarkChunk(candidate)) batch.add(candidate);
        }

        return batch;
    }

    private void processResult(
            ChunkBatchProcessor.ScanResult result,
            ScanTask task,
            ScanSession session,
            ConcurrentHashMap<String, Queue<ChunkSnapshot>> buffers,
            AtomicInteger foundCount,
            int targetChunks,
            AtomicInteger lastFoundAt,
            AtomicInteger attemptCount,
            String workerName,
            AtomicBoolean taskDone
    ) {
        ChunkSnapshot snapshot = result.snapshot();
        ResourceLocation actualBiome = result.actualBiome();

        if (actualBiome == null || !database.analyzeSnapshotForRecon(snapshot)) return;

        ResourceLocation targetBiome = task.biome().location();
        boolean isTarget = actualBiome.equals(targetBiome);

        if (session.consumeChunkNeed(task.dimension().location(), actualBiome)) {
            saveToBuffer(task.dimension().location(), actualBiome, snapshot, buffers);

            if (isTarget) {
                lastFoundAt.set(attemptCount.get());
                int newCount = foundCount.incrementAndGet();

                if (newCount >= targetChunks) {
                    taskDone.set(true);
                    ComplexityAnalyzer.LOGGER.info("[SCAN] {} - {} COMPLETE: {}/{} chunks",
                            workerName, targetBiome.getPath(), newCount, targetChunks);
                } else if (newCount % 10 == 0) { // Логируем каждые 10 чанков
                    ComplexityAnalyzer.LOGGER.info("[SCAN] {} - {} progress: {}/{} chunks",
                            workerName, targetBiome.getPath(), newCount, targetChunks);
                }
            } else {
                ComplexityAnalyzer.LOGGER.debug("[SCAN] {} - Found bonus chunk for {}", workerName, actualBiome.getPath());
            }
        } else {
            if (isTarget) {
                taskDone.set(true);
                ComplexityAnalyzer.LOGGER.info("[SCAN] {} - {} quota already fulfilled by other workers! Stopping task.",
                        workerName, targetBiome.getPath());
            }
        }
    }

    private void saveToBuffer(
            ResourceLocation dimension,
            ResourceLocation biome,
            ChunkSnapshot snapshot,
            ConcurrentHashMap<String, Queue<ChunkSnapshot>> buffers
    ) {
        String key = dimension + "|" + biome;
        Queue<ChunkSnapshot> buffer = buffers.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        buffer.add(snapshot);

        if (buffer.size() >= ScanConfig.BATCH_SAVE_THRESHOLD) flushBuffer(buffer, dimension, biome);
    }

    private void flushBuffer(Queue<ChunkSnapshot> buffer, ResourceLocation dim, ResourceLocation biome) {
        List<ChunkSnapshot> batch = new ArrayList<>(ScanConfig.BATCH_SAVE_THRESHOLD);
        ChunkSnapshot item;
        while (batch.size() < ScanConfig.BATCH_SAVE_THRESHOLD && (item = buffer.poll()) != null) {
            batch.add(item);
        }

        if (!batch.isEmpty()) {
            CompletableFuture.runAsync(() -> database.appendReconData(dim, biome, batch),
                    ThreadPoolManager.getInstance().getComputePool()
            );
        }
    }

    private void flushAllBuffers(ConcurrentHashMap<String, Queue<ChunkSnapshot>> buffers) {
        buffers.forEach((key, buffer) -> {
            List<ChunkSnapshot> remaining = new ArrayList<>();
            ChunkSnapshot item;
            while ((item = buffer.poll()) != null) {
                remaining.add(item);
            }
            if (!remaining.isEmpty()) {
                try {
                    String[] parts = key.split("\\|");
                    ResourceLocation dim = ResourceLocation.parse(parts[0]);
                    ResourceLocation biome = ResourceLocation.parse(parts[1]);
                    database.appendReconData(dim, biome, remaining);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.debug("[SCAN] Failed to flush {}", key);
                }
            }
        });
    }

    private void awaitCompletion(CountDownLatch latch) {
        try {
            boolean finished = latch.await(ScanConfig.SCAN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                notifier.logWarn("[SCAN] Scan timed out after " + ScanConfig.SCAN_TIMEOUT_MINUTES + " minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifier.logWarn("[SCAN] Scan interrupted");
        }
    }

    public void shutdown() {
        ComplexityAnalyzer.LOGGER.info("[SCAN] shutdown() called");
        isShutdown.set(true);
        stopRequested.set(true);

        for (Thread worker : activeWorkers) {
            worker.interrupt();
        }
        activeWorkers.clear();

        CountDownLatch latch = activeLatch;
        if (latch != null) {
            try {
                boolean finished = latch.await(ScanConfig.SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
                if (!finished) ComplexityAnalyzer.LOGGER.debug("[SCAN] Workers still running, continuing shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        ConcurrentHashMap<String, Queue<ChunkSnapshot>> buffers = resultBuffers;
        if (buffers != null && !buffers.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("[SCAN] Saving {} buffer entries...", buffers.size());
            flushAllBuffers(buffers);
        }

        ComplexityAnalyzer.LOGGER.info("[SCAN] shutdown() complete");
    }
}