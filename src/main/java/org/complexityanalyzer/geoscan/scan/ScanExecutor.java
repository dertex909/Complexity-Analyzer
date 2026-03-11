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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
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

    private final AtomicInteger nextWorkerId = new AtomicInteger(0);

    private volatile ScanSession currentSession = null;
    private volatile Runnable currentOnComplete = null;
    private final Object sessionLock = new Object();

    private final Set<Thread> permanentWorkers = ConcurrentHashMap.newKeySet();
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
            worldScanner.clearStopRequest();

            batchProcessor.setProfile(newSession.getProfile());

            flushAllBuffers(resultBuffers);
            resultBuffers.clear();

            notifier.logInfo(String.format("[SCAN] 🚀 %s MODE — using %d/%d threads, max %d parallel chunks",
                    newSession.getProfile().name(),
                    newSession.getProfile().getWorkerCount(maxWorkers),
                    maxWorkers,
                    newSession.getProfile().maxParallelChunks));

            ensureWorkersRunning(newSession.getProfile().getWorkerCount(maxWorkers));
        }
    }

    public void stop() {
        synchronized (sessionLock) {
            if (currentSession != null) {
                ComplexityAnalyzer.LOGGER.info("[SCAN] 🛑 STOP REQUESTED");
                currentSession.invalidate();
                worldScanner.requestStop();

                flushAllBuffers(resultBuffers);

                currentSession = null;
                currentOnComplete = null;
            }
        }
    }

    private void ensureWorkersRunning(int neededWorkers) {
        ExecutorService pool = ThreadPoolManager.getInstance().getComputePool();

        while (permanentWorkers.size() < neededWorkers) {
            final int workerId = nextWorkerId.getAndIncrement();

            try {
                CompletableFuture.runAsync(() -> workerLoop(workerId), pool);
            } catch (RejectedExecutionException e) {
                ComplexityAnalyzer.LOGGER.warn("[SCAN] Worker {} rejected", workerId);
                break;
            }
        }
    }

    private void workerLoop(int workerId) {
        Thread currentThread = Thread.currentThread();
        permanentWorkers.add(currentThread);
        String workerName = "Worker-" + workerId;

        ComplexityAnalyzer.LOGGER.info("[SCAN] 🚀 {} started (permanent)", workerName);

        try {
            while (!isShutdown.get() && !Thread.currentThread().isInterrupted()) {
                ScanSession session = currentSession;

                if (session == null || !session.isValid()) {
                    LockSupport.parkNanos(100_000_000L);
                    continue;
                }

                ScanTask task = session.pollTask();

                if (task == null) {
                    if (session.getTasksCompleted() >= session.getTotalTasks()) completeSession(session);
                    LockSupport.parkNanos(100_000_000L);
                    continue;
                }

                processTask(workerName, task, session);
            }
        } catch (Exception e) {
            if (!isShutdown.get()) ComplexityAnalyzer.LOGGER.error("[SCAN] {} crashed", workerName, e);
        } finally {
            permanentWorkers.remove(currentThread);
            ComplexityAnalyzer.LOGGER.info("[SCAN] {} stopped", workerName);
        }
    }

    private void completeSession(ScanSession session) {
        synchronized (sessionLock) {
            if (currentSession != session) return;

            Runnable callback = currentOnComplete;
            currentSession = null;
            currentOnComplete = null;

            flushAllBuffers(resultBuffers);

            notifier.logInfo("[SCAN] 🎉 Session complete!");

            if (callback != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        server.execute(callback);
                    } catch (Exception e) {
                        ComplexityAnalyzer.LOGGER.warn("[SCAN] onComplete failed", e);
                    }
                }, ThreadPoolManager.getInstance().getComputePool());
            }
        }
    }

    private void processTask(
            String workerName,
            ScanTask task,
            ScanSession session
    ) {
        if (!session.isValid() || isShutdown.get()) return;

        int targetChunks = task.chunksToFind();

        ComplexityAnalyzer.LOGGER.info("[SCAN] {} - starting: {} (need {} chunks)",
                workerName, task.biome().location().getPath(), targetChunks);

        AtomicInteger foundCount = new AtomicInteger(0);
        AtomicInteger attemptCount = new AtomicInteger(0);
        AtomicBoolean taskDone = new AtomicBoolean(false);

        int maxAttempts = Math.min(ScanConfig.MAX_ATTEMPTS,
                Math.max(ScanConfig.MIN_ATTEMPTS, targetChunks * ScanConfig.MAX_ATTEMPTS_PER_CHUNK));

        if (isShutdown.get() || !session.isValid()) return;

        Optional<ChunkPos> startPos = findBiome(task, false);
        if (startPos.isEmpty() || isShutdown.get() || !session.isValid()) return;

        SpiralChunkSearcher searcher = new SpiralChunkSearcher();
        searcher.startAt(startPos.get().x, startPos.get().z);

        int relocations = 0;
        AtomicInteger lastFoundAt = new AtomicInteger(0);

        while (!taskDone.get() && attemptCount.get() < maxAttempts
                && session.isValid() && !isShutdown.get()) {

            if (server.isPaused()) {
                waitWhilePaused(session);
                if (isShutdown.get() || !session.isValid()) break;
            }

            if (foundCount.get() >= targetChunks) {
                taskDone.set(true);
                break;
            }

            int attemptsSinceFind = attemptCount.get() - lastFoundAt.get();
            if (attemptsSinceFind > ScanConfig.STUCK_THRESHOLD
                    && relocations < ScanConfig.MAX_RELOCATIONS) {

                if (isShutdown.get() || !session.isValid()) break;

                Optional<ChunkPos> newStart = findBiome(task, true);
                if (isShutdown.get() || !session.isValid()) break;

                if (newStart.isPresent()) {
                    searcher.startAt(newStart.get().x, newStart.get().z);
                    relocations++;
                    lastFoundAt.set(attemptCount.get());
                }
            }

            List<ChunkPos> batch = collectBatch(searcher, session, attemptCount);
            if (batch.isEmpty()) continue;

            if (isShutdown.get() || !session.isValid()) break;

            try {
                List<ChunkBatchProcessor.ScanResult> results = batchProcessor
                        .processBatchAsync(task.dimension(), batch, session)
                        .get(30, TimeUnit.SECONDS);

                for (ChunkBatchProcessor.ScanResult result : results) {
                    if (taskDone.get() || !session.isValid() || isShutdown.get()) break;
                    processResult(result, task, session, foundCount, targetChunks,
                            lastFoundAt, attemptCount, workerName, taskDone);
                }
            } catch (TimeoutException e) {
                ComplexityAnalyzer.LOGGER.debug("[SCAN] {} - batch timeout, continuing", workerName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                ComplexityAnalyzer.LOGGER.debug("[SCAN] {} - batch failed: {}", workerName, e.getMessage());
            }
        }

        int completed = session.incrementTasksCompleted();
        ComplexityAnalyzer.LOGGER.info("[SCAN] {} - finished {}: {} chunks in {} attempts ({}/{})",
                workerName, task.biome().location().getPath(), foundCount.get(), attemptCount.get(),
                completed, session.getTotalTasks());
    }

    private void waitWhilePaused(ScanSession session) {
        while (server.isPaused() && !isShutdown.get() && session.isValid()) {
            LockSupport.parkNanos(100_000_000L);
            if (Thread.currentThread().isInterrupted()) break;
        }
    }

    private Optional<ChunkPos> findBiome(ScanTask task, boolean isRelocation) {
        if (isShutdown.get()) return Optional.empty();
        return worldScanner.findBiomeLocation(task.dimension(), task.biome(), isRelocation);
    }

    private List<ChunkPos> collectBatch(SpiralChunkSearcher searcher, ScanSession session, AtomicInteger attemptCount) {
        List<ChunkPos> batch = new ArrayList<>(ScanConfig.BATCH_SIZE);

        while (batch.size() < ScanConfig.BATCH_SIZE) {
            if (!session.isValid() || isShutdown.get()) break;
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
            AtomicInteger foundCount,
            int targetChunks,
            AtomicInteger lastFoundAt,
            AtomicInteger attemptCount,
            String workerName,
            AtomicBoolean taskDone
    ) {
        ChunkSnapshot snapshot = result.snapshot();
        ResourceLocation actualBiome = result.actualBiome();

        ComplexityAnalyzer.LOGGER.info("[SCAN] processResult: chunk [{}, {}], biome={}",
                snapshot.chunkX(), snapshot.chunkZ(), actualBiome);

        if (actualBiome == null || !database.analyzeSnapshotForRecon(snapshot)) {
            ComplexityAnalyzer.LOGGER.info("[SCAN] processResult: REJECTED (biome={}, analyze={})",
                    actualBiome, actualBiome != null);
            return;
        }

        ResourceLocation targetBiome = task.biome().location();
        boolean isTarget = actualBiome.equals(targetBiome);

        if (session.consumeChunkNeed(task.dimension().location(), actualBiome)) {
            saveToBuffer(task.dimension().location(), actualBiome, snapshot);

            if (isTarget) {
                lastFoundAt.set(attemptCount.get());
                int newCount = foundCount.incrementAndGet();

                if (newCount >= targetChunks) {
                    taskDone.set(true);
                    ComplexityAnalyzer.LOGGER.info("[SCAN] {} - {} COMPLETE: {}/{} chunks",
                            workerName, targetBiome.getPath(), newCount, targetChunks);
                } else if (newCount % 10 == 0) {
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
            ChunkSnapshot snapshot
    ) {
        ComplexityAnalyzer.LOGGER.info("[SCAN] saveToBuffer: [{}, {}] -> {}/{}",
                snapshot.chunkX(), snapshot.chunkZ(), dimension, biome);
        String key = dimension + "|" + biome;
        Queue<ChunkSnapshot> buffer = resultBuffers.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
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

    public void shutdown() {
        ComplexityAnalyzer.LOGGER.info("[SCAN] shutdown() called");
        isShutdown.set(true);

        synchronized (sessionLock) {
            if (currentSession != null) {
                currentSession.invalidate();
                currentSession = null;
            }
            currentOnComplete = null;
        }

        for (Thread worker : permanentWorkers) {
            worker.interrupt();
        }

        int waited = 0;
        while (!permanentWorkers.isEmpty() && waited < 2000) {
            LockSupport.parkNanos(100_000_000L);
            waited += 100;
        }

        flushAllBuffers(resultBuffers);

        ComplexityAnalyzer.LOGGER.info("[SCAN] shutdown() complete");
    }
}