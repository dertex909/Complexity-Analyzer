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

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;

public record HarvestedItems(
        ObjectList<ItemStack> inputItems,
        ObjectList<ItemStack> outputItems,
        ObjectList<HarvestedIngredient> inputIngredients,
        ObjectList<FluidStack> inputFluids,
        ObjectList<FluidStack> outputFluids,
        Object root,
        ReferenceSet<Item> transitionalItems
) {
    public static final HarvestedItems EMPTY = new HarvestedItems(
            ObjectLists.emptyList(), ObjectLists.emptyList(),
            ObjectLists.emptyList(), ObjectLists.emptyList(),
            ObjectLists.emptyList(), null,
            ReferenceSets.emptySet()
    );

    public boolean isEmpty() {
        return inputItems.isEmpty() && outputItems.isEmpty() && inputIngredients.isEmpty()
                && inputFluids.isEmpty() && outputFluids.isEmpty();
    }

    public record HarvestedIngredient(Ingredient ingredient, int count) {
    }
}