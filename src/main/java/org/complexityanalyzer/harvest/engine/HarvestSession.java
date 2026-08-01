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

package org.complexityanalyzer.harvest.engine;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;

public final class HarvestSession {
    public final ObjectArrayList<ItemStack> inputItems = new ObjectArrayList<>(32);
    public final ObjectArrayList<ItemStack> outputItems = new ObjectArrayList<>(16);
    public final ObjectArrayList<FluidStack> inputFluids = new ObjectArrayList<>(16);
    public final ObjectArrayList<FluidStack> outputFluids = new ObjectArrayList<>(16);
    public final ObjectArrayList<HarvestedItems.HarvestedIngredient> inputIngredients = new ObjectArrayList<>(16);

    public final ReferenceOpenHashSet<Object> visited = new ReferenceOpenHashSet<>(128);
    public final ReferenceOpenHashSet<Object> visitedSecondary = new ReferenceOpenHashSet<>(64);
    public final ReferenceOpenHashSet<Item> transitionalItems = new ReferenceOpenHashSet<>(8);
    public final ReferenceOpenHashSet<Ingredient> visitedIngredients = new ReferenceOpenHashSet<>(64);

    public void reset() {
        inputItems.clear();
        outputItems.clear();
        inputFluids.clear();
        outputFluids.clear();
        inputIngredients.clear();
        visited.clear();
        visitedSecondary.clear();
        transitionalItems.clear();
        visitedIngredients.clear();
    }
}