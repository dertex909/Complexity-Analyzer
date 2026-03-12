package org.complexityanalyzer.geoscan.worldgen;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class OptimizedChunkCache {

    private static final int MAX_CACHE_SIZE = 16384;

    private final ConcurrentHashMap<Long, CachedChunk> cache = new ConcurrentHashMap<>(MAX_CACHE_SIZE);
    private final ConcurrentLinkedDeque<Long> accessOrder = new ConcurrentLinkedDeque<>();

    public record CachedChunk(ChunkAccess chunk, ChunkStatus status, long timestamp) {
    }

    public ChunkAccess get(ChunkPos pos, ChunkStatus minStatus) {
        CachedChunk cached = cache.get(pos.toLong());
        if (cached != null && cached.status.isOrAfter(minStatus)) {
            long key = pos.toLong();
            accessOrder.remove(key);
            accessOrder.addFirst(key);
            return cached.chunk;
        }
        return null;
    }

    public void put(ChunkPos pos, ChunkAccess chunk, ChunkStatus status) {
        long key = pos.toLong();

        while (cache.size() >= MAX_CACHE_SIZE) {
            Long oldest = accessOrder.pollLast();
            if (oldest != null) cache.remove(oldest);
        }

        cache.put(key, new CachedChunk(chunk, status, System.currentTimeMillis()));
        accessOrder.remove(key);
        accessOrder.addFirst(key);
    }

    public void clear() {
        cache.clear();
        accessOrder.clear();
    }

    public int size() {
        return cache.size();
    }
}