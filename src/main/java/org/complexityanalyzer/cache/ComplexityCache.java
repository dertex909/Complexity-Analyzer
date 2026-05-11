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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
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

import java.util.concurrent.atomic.AtomicLong;

public class ComplexityCache {

    private final Reference2ObjectMap<Item, ItemComplexity> cache = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());
    private final Reference2ObjectMap<Item, ComplexityCategory> categoryCache = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    public ComplexityCache() {
    }

    @Nullable
    public ItemComplexity get(Item item) {
        var result = cache.get(item);

        if (result != null) {
            hits.incrementAndGet();
            return result;
        } else {
            misses.incrementAndGet();
            return null;
        }
    }

    public void put(Item item, ItemComplexity complexity) {
        if (item == null || complexity == null) return;

        cache.put(item, complexity);
        categoryCache.put(item, complexity.getCategory());
    }

    @Nullable
    public ComplexityCategory getCategory(Item item) {
        return categoryCache.get(item);
    }

    public boolean contains(Item item) {
        return cache.containsKey(item);
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