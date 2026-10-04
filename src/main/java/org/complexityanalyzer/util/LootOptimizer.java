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

package org.complexityanalyzer.util;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LootOptimizer {
    public static final int PROBE_THRESHOLD = 30;
    public static final Map<ResourceLocation, TableStatus> STATUS_CACHE = new ConcurrentHashMap<>();
    public static final Map<ResourceLocation, AtomicInteger> PROBE_COUNTERS = new ConcurrentHashMap<>();
    public static final AtomicInteger SKIPPED_CALLS = new AtomicInteger(0);
    public static final AtomicInteger FULL_CALLS = new AtomicInteger(0);
    private static final ThreadLocal<Integer> NESTING_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static boolean isIdle() {
        return NESTING_DEPTH.get() <= 0;
    }

    public static Scope open() {
        int current = NESTING_DEPTH.get();
        NESTING_DEPTH.set(current + 1);

        return () -> {
            int depth = NESTING_DEPTH.get() - 1;
            if (depth <= 0) {
                NESTING_DEPTH.remove();
            } else {
                NESTING_DEPTH.set(depth);
            }
        };
    }

    public static boolean areListsIdentical(ObjectArrayList<ItemStack> original, ObjectArrayList<ItemStack> modified) {
        if (original == null || modified == null) return original == modified;
        if (original.size() != modified.size()) return false;

        for (int i = 0; i < original.size(); i++) {
            var s1 = original.get(i);
            var s2 = modified.get(i);
            if (!ItemStack.matches(s1, s2) || s1.getCount() != s2.getCount()) return false;
        }
        return true;
    }

    public enum TableStatus {
        UNKNOWN,
        MODIFIED,
        UNMODIFIED
    }
}