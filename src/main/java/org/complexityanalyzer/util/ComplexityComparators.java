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
import org.complexityanalyzer.harvest.inspector.ItemStackIdentity;

import java.util.Comparator;

public final class ComplexityComparators {
    private static final Comparator<ResourceLocation> RESOURCE_LOCATION_COMPARATOR = Comparator.nullsFirst(ResourceLocation::compareTo);
    public static final Comparator<ItemStack> ITEM_STACK_BY_ID = Comparator.comparing(stack -> GameRegistryManager.getItemId(stack.getItem()), RESOURCE_LOCATION_COMPARATOR);
    public static final Comparator<ItemStack> ITEM_STACK_BY_ID_AND_COUNT = ITEM_STACK_BY_ID.thenComparingInt(ItemStack::getCount);
    public static final Comparator<Fluid> FLUID_BY_ID = Comparator.comparing(GameRegistryManager::getFluidId, RESOURCE_LOCATION_COMPARATOR);
    public static final Comparator<FluidStack> FLUID_STACK_BY_ID_AND_AMOUNT = Comparator.comparing(FluidStack::getFluid, FLUID_BY_ID).thenComparingInt(FluidStack::getAmount);

    private ComplexityComparators() {
    }

    public static Comparator<ItemStack> createDeepItemStackComparator(HolderLookup.Provider provider) {
        return ITEM_STACK_BY_ID.thenComparing(stack -> ItemStackIdentity.dataKey(stack, provider));
    }
}