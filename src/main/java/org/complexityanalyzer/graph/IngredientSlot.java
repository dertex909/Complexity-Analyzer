/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IngredientSlot {
    private final List<Item> variants;
    private final int count;

    public IngredientSlot(List<Item> variants, int count) {
        this.variants = new ArrayList<>(variants);
        this.count = Math.max(1, count);
    }

    public IngredientSlot(Item singleItem, int count) {
        this.variants = List.of(singleItem);
        this.count = Math.max(1, count);
    }

    public List<Item> getVariants() {
        return Collections.unmodifiableList(variants);
    }

    public int getCount() {
        return count;
    }

    public boolean hasMultipleVariants() {
        return variants.size() > 1;
    }

    public Item getFirstVariant() {
        return variants.isEmpty() ? null : variants.getFirst();
    }

    @Override
    public String toString() {
        if (variants.size() == 1) {
            return count + "x " + variants.getFirst();
        }
        return count + "x [" + variants.size() + " variants]";
    }
}