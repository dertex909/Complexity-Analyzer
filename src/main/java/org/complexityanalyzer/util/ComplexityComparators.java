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

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeNode.ChemicalIngredient;
import org.complexityanalyzer.graph.RecipeNode.ChemicalOutput;
import org.complexityanalyzer.harvest.ItemStackIdentity;

import java.util.Comparator;

public final class ComplexityComparators {
    public static final Comparator<ItemStack> ITEM_STACK_BY_ID = (a, b) ->
            compareIds(GameRegistryManager.getItemId(a.getItem()), GameRegistryManager.getItemId(b.getItem()));
    public static final Comparator<ItemStack> ITEM_STACK_BY_ID_AND_COUNT = (a, b) -> {
        int c = compareIds(GameRegistryManager.getItemId(a.getItem()), GameRegistryManager.getItemId(b.getItem()));
        if (c != 0) return c;
        return Integer.compare(a.getCount(), b.getCount());
    };
    public static final Comparator<Fluid> FLUID_BY_ID = (f1, f2) ->
            compareIds(GameRegistryManager.getFluidId(f1), GameRegistryManager.getFluidId(f2));
    public static final Comparator<FluidStack> FLUID_STACK_BY_ID_AND_AMOUNT = (a, b) -> {
        int c = compareIds(GameRegistryManager.getFluidId(a.getFluid()), GameRegistryManager.getFluidId(b.getFluid()));
        if (c != 0) return c;
        return Integer.compare(a.getAmount(), b.getAmount());
    };
    public static final Comparator<ChemicalIngredient> CHEMICAL_INGREDIENT = Comparator.comparing(ChemicalIngredient::id).thenComparingInt(ChemicalIngredient::amount);
    public static final Comparator<ChemicalOutput> CHEMICAL_OUTPUT = Comparator.comparing(ChemicalOutput::id).thenComparingLong(ChemicalOutput::amount);

    private ComplexityComparators() {
    }

    private static int compareIds(ResourceLocation a, ResourceLocation b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    public static Comparator<ItemStack> createDeepItemStackComparator(HolderLookup.Provider provider) {
        return (a, b) -> {
            int byId = compareIds(GameRegistryManager.getItemId(a.getItem()), GameRegistryManager.getItemId(b.getItem()));
            if (byId != 0) return byId;
            return ItemStackIdentity.dataKey(a, provider).compareTo(ItemStackIdentity.dataKey(b, provider));
        };
    }
}