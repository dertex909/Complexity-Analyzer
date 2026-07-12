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

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;
import java.util.concurrent.atomic.AtomicLong;

public class ComplexityCache {

    private final Reference2ObjectMap<Item, SoftReference<ItemComplexity>> cache =
            Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    private final Reference2ObjectMap<Item, SoftReference<ComplexityCategory>> categoryCache =
            Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    public ComplexityCache() {
    }

    @Nullable
    public ItemComplexity get(Item item) {
        var ref = cache.get(item);
        var result = (ref != null) ? ref.get() : null;

        if (result != null) {
            hits.incrementAndGet();
            return result;
        } else {
            if (ref != null) cache.remove(item);
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
        var result = (ref != null) ? ref.get() : null;

        if (result == null && ref != null) categoryCache.remove(item);
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