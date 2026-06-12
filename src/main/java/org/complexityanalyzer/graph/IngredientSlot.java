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

package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.harvest.ItemStackIdentity;

import java.util.Objects;

public class IngredientSlot {
    private final ObjectList<ItemStack> variants;
    private final int count;

    public IngredientSlot(ObjectList<ItemStack> variants, int count) {
        this.variants = new ObjectArrayList<>(variants.size());
        for (var variant : variants) {
            if (variant == null || variant.isEmpty()) continue;
            this.variants.add(variant.copyWithCount(1));
        }
        this.count = Math.max(1, count);
    }

    public ObjectList<ItemStack> getVariants() {
        return ObjectLists.unmodifiable(variants);
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
        int result = Objects.hash(count);
        for (var stack : variants) result = 31 * result + ItemStackIdentity.hashItemData(stack);
        return result;
    }

    @Override
    public String toString() {
        if (variants.size() == 1) return count + "x " + variants.getFirst();
        return count + "x [" + variants.size() + " variants]";
    }
}