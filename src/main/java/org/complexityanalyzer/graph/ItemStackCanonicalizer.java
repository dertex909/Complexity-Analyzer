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

package org.complexityanalyzer.graph;

import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.harvest.inspector.ItemStackIdentity;

import java.util.concurrent.ConcurrentHashMap;

public final class ItemStackCanonicalizer {
    private static final ConcurrentHashMap<Key, ItemStack> CACHE = new ConcurrentHashMap<>(8192);

    private ItemStackCanonicalizer() {
    }

    public static ItemStack canonicalize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        int count = stack.getCount();
        var base = CACHE.computeIfAbsent(new Key(stack), k -> stack.copyWithCount(1));
        return base.copyWithCount(count);
    }

    public static void clear() {
        CACHE.clear();
    }

    private record Key(ItemStack stack) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key(var stack1))) return false;
            return ItemStackIdentity.sameItemData(this.stack, stack1);
        }

        @Override
        public int hashCode() {
            return ItemStackIdentity.hashItemData(stack);
        }
    }
}