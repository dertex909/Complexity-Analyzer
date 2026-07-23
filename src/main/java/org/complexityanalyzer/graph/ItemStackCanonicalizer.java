/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 */

package org.complexityanalyzer.graph;

import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.harvest.ItemStackIdentity;

import java.util.concurrent.ConcurrentHashMap;

public final class ItemStackCanonicalizer {
    private static final ConcurrentHashMap<Key, ItemStack> CACHE = new ConcurrentHashMap<>(8192);

    private ItemStackCanonicalizer() {
    }

    public static ItemStack canonicalize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        return CACHE.computeIfAbsent(new Key(stack), k -> stack.copyWithCount(1));
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