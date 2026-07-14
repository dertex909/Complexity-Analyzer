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