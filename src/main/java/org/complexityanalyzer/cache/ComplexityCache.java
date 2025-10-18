package org.complexityanalyzer.cache;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.data.PathType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ComplexityCache {
    private final Map<CacheKey, ItemComplexity> cache = new ConcurrentHashMap<>();
    private final Map<Item, ComplexityCategory> categoryCache = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    public ComplexityCache() {
    }

    public Optional<ItemComplexity> get(Item item, PathType pathType) {
        ItemComplexity result = cache.get(new CacheKey(item, pathType));

        if (result != null) {
            hits.incrementAndGet();
            return Optional.of(result);
        } else {
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    public void put(Item item, PathType pathType, ItemComplexity complexity) {
        lock.writeLock().lock();
        try {
            CacheKey key = new CacheKey(item, pathType);
            cache.put(key, complexity);
            if (pathType == PathType.OPTIMAL) {
                categoryCache.put(item, complexity.getCategory());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ComplexityCategory> getCategory(Item item) {
        return Optional.ofNullable(categoryCache.get(item));
    }

    public boolean contains(Item item, PathType pathType) {
        return cache.containsKey(new CacheKey(item, pathType));
    }

    public Set<Item> getCachedItems() {
        lock.readLock().lock();
        try {
            Set<Item> items = new HashSet<>();
            for (CacheKey key : cache.keySet()) {
                items.add(key.item());
            }
            return items;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<PathType, ItemComplexity> getAllForItem(Item item) {
        Map<PathType, ItemComplexity> results = new EnumMap<>(PathType.class);
        for (PathType pathType : PathType.values()) {
            get(item, pathType).ifPresent(complexity -> results.put(pathType, complexity));
        }
        return results;
    }

    public void remove(Item item) {
        lock.writeLock().lock();
        try {
            for (PathType pathType : PathType.values()) {
                cache.remove(new CacheKey(item, pathType));
            }
            categoryCache.remove(item);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            int size = cache.size();
            cache.clear();
            categoryCache.clear();
            hits.set(0);
            misses.set(0);
            ComplexityAnalyzer.LOGGER.debug("ComplexityCache cleared ({} entries removed)", size);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        return cache.size();
    }

    public CacheStats getStats() {
        long currentHits = hits.get();
        long currentMisses = misses.get();
        long total = currentHits + currentMisses;
        double hitRate = total > 0 ? (double) currentHits / total * 100.0 : 0.0;

        return new CacheStats(
                cache.size(),
                categoryCache.size(),
                currentHits,
                currentMisses,
                hitRate
        );
    }

    public void resetStats() {
        hits.set(0);
        misses.set(0);
    }

    public List<Item> getItemsByCategory(ComplexityCategory category) {
        lock.readLock().lock();
        try {
            List<Item> items = new ArrayList<>();
            for (Map.Entry<Item, ComplexityCategory> entry : categoryCache.entrySet()) {
                if (entry.getValue() == category) {
                    items.add(entry.getKey());
                }
            }
            return items;
        } finally {
            lock.readLock().unlock();
        }
    }

    private record CacheKey(Item item, PathType pathType) {}

    public record CacheStats(
            int cacheSize,
            int categoryCacheSize,
            long hits,
            long misses,
            double hitRate
    ) {
        @Override
        public @NotNull String toString() {
            return String.format(
                    "CacheStats{size=%d, categories=%d, hits=%d, misses=%d, hitRate=%.2f%%}",
                    cacheSize, categoryCacheSize, hits, misses, hitRate
            );
        }
    }
}