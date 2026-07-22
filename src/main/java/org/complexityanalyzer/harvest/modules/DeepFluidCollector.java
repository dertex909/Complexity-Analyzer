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

package org.complexityanalyzer.harvest.modules;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.complexityanalyzer.core.GameRegistryManager;

public final class DeepFluidCollector {

    private DeepFluidCollector() {
    }

    public static void collect(Object obj, ObjectList<FluidStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        DeepGraphTraverser.traverse(obj, depth, visited, (node, d) -> {
            switch (node) {
                case TagKey<?> tagKey -> {
                    if (tagKey.registry().equals(Registries.FLUID)) {
                        @SuppressWarnings("unchecked")
                        var fluidTag = (TagKey<Fluid>) tagKey;
                        var optionalTag = BuiltInRegistries.FLUID.getTag(fluidTag);
                        if (optionalTag.isPresent()) for (var holder : optionalTag.get()) {
                            var fluid = holder.value();
                            var id = BuiltInRegistries.FLUID.getKey(fluid);
                            var registeredFluid = GameRegistryManager.getFluid(id);
                            if (registeredFluid != null && registeredFluid != Fluids.EMPTY) {
                                acc.add(new FluidStack(registeredFluid, 1000));
                                break;
                            }
                        }
                    }
                    return true;
                }
                case SizedFluidIngredient sfi -> {
                    for (var fs : sfi.getFluids()) if (!fs.isEmpty()) acc.add(fs.copy());
                    return true;
                }
                case FluidStack fs when !fs.isEmpty() -> {
                    acc.add(fs.copy());
                    return true;
                }
                default -> {
                }
            }
            return node instanceof ItemStack || node instanceof Ingredient;
        });
    }
}