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

package org.complexityanalyzer.geoscan.worldgen;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class OptimizedChunkCache {

    private static final int MAX_CACHE_SIZE = 16384;
    private static final int CLEANUP_BATCH = 2048;
    private static final int CLEANUP_TRIGGER = MAX_CACHE_SIZE + CLEANUP_BATCH;
    private static final int SAMPLE_SIZE = 1024;
    private static final int GENERATION_TIMEOUT_SECONDS = 30;

    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>(MAX_CACHE_SIZE, 0.75f, 16);
    private final ConcurrentHashMap<Long, CompletableFuture<ChunkAccess>> generationInProgress = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong(0);
    private final AtomicInteger cleanupLock = new AtomicInteger(0);

    public static final class CacheEntry {
        final ChunkAccess chunk;
        final ChunkStatus status;
        volatile long lastAccess;

        CacheEntry(ChunkAccess chunk, ChunkStatus status, long accessTime) {
            this.chunk = chunk;
            this.status = status;
            this.lastAccess = accessTime;
        }
    }

    public ChunkAccess get(ChunkPos pos, ChunkStatus minStatus) {
        CacheEntry entry = cache.get(pos.toLong());
        if (entry != null && entry.status.isOrAfter(minStatus)) {
            entry.lastAccess = accessCounter.incrementAndGet();
            return entry.chunk;
        }
        return null;
    }

    public void put(ChunkPos pos, ChunkAccess chunk, ChunkStatus status) {
        long key = pos.toLong();
        long accessTime = accessCounter.incrementAndGet();

        CacheEntry existing = cache.get(key);
        if (existing != null && existing.status.isOrAfter(status)) {
            existing.lastAccess = accessTime;
            return;
        }

        cache.put(key, new CacheEntry(chunk, status, accessTime));

        if (cache.size() > CLEANUP_TRIGGER) tryCleanup();
    }

    public ChunkAccess computeIfAbsent(ChunkPos pos, ChunkStatus minStatus,
                                       java.util.function.Supplier<ChunkAccess> generator) {
        long key = pos.toLong();
        CacheEntry existing = cache.get(key);
        if (existing != null && existing.status.isOrAfter(minStatus)) {
            existing.lastAccess = accessCounter.incrementAndGet();
            return existing.chunk;
        }

        CompletableFuture<ChunkAccess> future = generationInProgress.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> generateChunk(key, pos, minStatus, generator))
        );

        try {
            ChunkAccess result = future.get(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.chunk == result) entry.lastAccess = accessCounter.incrementAndGet();
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            generationInProgress.remove(key, future);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            generationInProgress.remove(key, future);
            return null;
        } catch (ExecutionException e) {
            generationInProgress.remove(key, future);
            return null;
        }
    }

    private ChunkAccess generateChunk(long key, ChunkPos pos, ChunkStatus minStatus,
                                      java.util.function.Supplier<ChunkAccess> generator) {
        try {
            CacheEntry cached = cache.get(key);
            if (cached != null && cached.status.isOrAfter(minStatus)) return cached.chunk;
            ChunkAccess chunk = generator.get();
            if (chunk != null) put(pos, chunk, minStatus);
            return chunk;
        } catch (Exception e) {
            return null;
        } finally {
            generationInProgress.remove(key);
        }
    }

    private void tryCleanup() {
        if (!cleanupLock.compareAndSet(0, 1)) return;

        try {
            int currentSize = cache.size();
            if (currentSize <= MAX_CACHE_SIZE) return;
            int toRemove = currentSize - MAX_CACHE_SIZE + CLEANUP_BATCH / 2;
            long threshold = findRemovalThreshold(toRemove);

            int removed = 0;
            for (var iterator = cache.entrySet().iterator(); iterator.hasNext() && removed < toRemove; ) {
                var entry = iterator.next();
                if (entry.getValue().lastAccess < threshold) {
                    iterator.remove();
                    removed++;
                }
            }
        } finally {
            cleanupLock.set(0);
        }
    }

    private long findRemovalThreshold(int toRemove) {
        int cacheSize = cache.size();
        if (cacheSize <= 4096) return findExactThreshold(toRemove);
        return findSampledThreshold(toRemove, cacheSize);
    }

    private long findExactThreshold(int toRemove) {
        PriorityQueue<Long> oldest = new PriorityQueue<>(toRemove, Comparator.reverseOrder());

        for (CacheEntry entry : cache.values()) {
            if (oldest.size() < toRemove) {
                oldest.offer(entry.lastAccess);
            } else if (entry.lastAccess < oldest.peek()) {
                oldest.poll();
                oldest.offer(entry.lastAccess);
            }
        }

        return oldest.isEmpty() ? Long.MAX_VALUE : oldest.peek();
    }

    private long findSampledThreshold(int toRemove, int cacheSize) {
        long[] reservoir = new long[SAMPLE_SIZE];
        int index = 0;

        for (CacheEntry entry : cache.values()) {
            if (index < SAMPLE_SIZE) {
                reservoir[index] = entry.lastAccess;
            } else {
                int j = ThreadLocalRandom.current().nextInt(index + 1);
                if (j < SAMPLE_SIZE) reservoir[j] = entry.lastAccess;
            }
            index++;
        }

        int actualSize = Math.min(index, SAMPLE_SIZE);
        Arrays.sort(reservoir, 0, actualSize);

        int thresholdIndex = (toRemove * actualSize) / cacheSize;
        return reservoir[Math.min(thresholdIndex, actualSize - 1)];
    }

    public void clear() {
        generationInProgress.values().forEach(future ->
                future.cancel(true));
        generationInProgress.clear();
        cache.clear();
        accessCounter.set(0);
    }

    public int size() {
        return cache.size();
    }
}