package org.complexityanalyzer.cache;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ComplexityCache {

    private final ConcurrentHashMap<Item, SoftReference<ItemComplexity>> cache = new ConcurrentHashMap<>(4096);

    private final ConcurrentHashMap<Item, SoftReference<ComplexityCategory>> categoryCache = new ConcurrentHashMap<>(4096);

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    public ComplexityCache() {
    }

    @Nullable
    public ItemComplexity get(Item item) {
        var ref = cache.get(item);
        if (ref == null) {
            misses.incrementAndGet();
            return null;
        }

        var result = ref.get();
        if (result != null) {
            hits.incrementAndGet();
            return result;
        } else {
            cache.remove(item, ref);
            misses.incrementAndGet();
            return null;
        }
    }

    public void put(Item item, ItemComplexity complexity) {
        if (item == null || complexity == null) return;

        cache.put(item, new SoftReference<>(complexity));
        categoryCache.put(item, new SoftReference<>(complexity.getCategory()));
    }

    @Nullable
    public ComplexityCategory getCategory(Item item) {
        var ref = categoryCache.get(item);
        if (ref == null) return null;

        var result = ref.get();
        if (result == null) categoryCache.remove(item, ref);
        return result;
    }

    public boolean contains(Item item) {
        var ref = cache.get(item);
        return ref != null && ref.get() != null;
    }

    public void clear() {
        int size = cache.size();
        cache.clear();
        categoryCache.clear();
        hits.set(0);
        misses.set(0);
        ComplexityAnalyzer.LOGGER.debug("ComplexityCache cleared ({} entries removed)", size);
    }

    public int size() {
        return cache.size();
    }
}