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

package org.complexityanalyzer.cache;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ComplexityCache {

    private final Map<Item, ItemComplexity> cache = new ConcurrentHashMap<>();
    private final Map<Item, ComplexityCategory> categoryCache = new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    public ComplexityCache() {
    }

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
        if (item == null || complexity == null) return;

        cache.put(item, complexity);
        categoryCache.put(item, complexity.getCategory());
    }

    public Optional<ComplexityCategory> getCategory(Item item) {
        return Optional.ofNullable(categoryCache.get(item));
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