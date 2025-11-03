/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.cache;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ComplexityCache {
    private final Map<Item, ItemComplexity> cache = new ConcurrentHashMap<>();
    private final Map<Item, ComplexityCategory> categoryCache = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    public ComplexityCache() {}

    public Optional<ItemComplexity> get(Item item) {
        ItemComplexity result = cache.get(item);

        if (result != null) {
            hits.incrementAndGet();
            return Optional.of(result);
        } else {
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    public void put(Item item, ItemComplexity complexity) {
        lock.writeLock().lock();
        try {
            cache.put(item, complexity);
            categoryCache.put(item, complexity.getCategory());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ComplexityCategory> getCategory(Item item) {
        return Optional.ofNullable(categoryCache.get(item));
    }

    public boolean contains(Item item) {
        return cache.containsKey(item);
    }

    public Set<Item> getCachedItems() {
        lock.readLock().lock();
        try {
            return new HashSet<>(cache.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void remove(Item item) {
        lock.writeLock().lock();
        try {
            cache.remove(item);
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
