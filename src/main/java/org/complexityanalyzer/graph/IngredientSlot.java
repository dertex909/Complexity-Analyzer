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

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.harvest.inspector.ItemStackIdentity;

import java.util.concurrent.ConcurrentHashMap;

import static org.complexityanalyzer.util.ComplexityComparators.ITEM_STACK_BY_ID;

public class IngredientSlot {

    private static final ConcurrentHashMap<IngredientSlot, IngredientSlot> INTERN_CACHE = new ConcurrentHashMap<>(16384);

    private final ObjectList<ItemStack> variants;
    private final int count;
    private volatile int cachedHashCode = 0;

    public IngredientSlot(ObjectList<ItemStack> variants, int count) {
        this.count = Math.max(1, count);

        var processed = new ObjectArrayList<ItemStack>(variants.size());
        for (var variant : variants) {
            if (variant != null && !variant.isEmpty()) processed.add(ItemStackCanonicalizer.canonicalize(variant));
        }

        if (processed.isEmpty()) {
            this.variants = ObjectLists.emptyList();
        } else if (processed.size() == 1) {
            this.variants = ObjectLists.singleton(processed.getFirst());
        } else {
            processed.sort(ITEM_STACK_BY_ID);
            this.variants = ObjectLists.unmodifiable(processed);
        }
    }

    public static IngredientSlot intern(IngredientSlot slot) {
        if (slot == null) return null;
        if (slot.getVariants().isEmpty()) return slot;
        return INTERN_CACHE.computeIfAbsent(slot, s -> s);
    }

    public static void clearCache() {
        INTERN_CACHE.clear();
    }

    public ObjectList<ItemStack> getVariants() {
        return variants;
    }

    public int getCount() {
        return count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IngredientSlot that)) return false;
        if (count != that.count || variants.size() != that.variants.size()) return false;
        for (int i = 0; i < variants.size(); i++) {
            if (!ItemStackIdentity.sameItemData(variants.get(i), that.variants.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = cachedHashCode;
        if (h == 0) {
            int result = count;
            for (var stack : variants) result = 31 * result + ItemStackIdentity.hashItemData(stack);
            if (result == 0) result = 1;
            cachedHashCode = result;
            return result;
        }
        return h;
    }

    @Override
    public String toString() {
        if (variants.size() == 1) return count + "x " + variants.getFirst();
        return count + "x [" + variants.size() + " variants]";
    }
}