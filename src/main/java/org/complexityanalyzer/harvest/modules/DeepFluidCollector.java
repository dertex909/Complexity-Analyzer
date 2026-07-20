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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.complexityanalyzer.harvest.RecipeReflection;

import java.util.Map;
import java.util.Optional;

public final class DeepFluidCollector {

    private DeepFluidCollector() {
    }

    public static void collect(Object obj, ObjectList<FluidStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o -> collect(o, acc, depth + 1, visited));
                return;
            }
            case SizedFluidIngredient sfi -> {
                for (var fs : sfi.getFluids()) if (!fs.isEmpty()) acc.add(fs.copy());
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                acc.add(fs.copy());
                return;
            }
            case Iterable<?> coll when HarvestUtility.isTooSmall(coll) -> {
                if (visited.add(coll)) for (var item : coll) collect(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when HarvestUtility.isTooSmall(map) -> {
                if (visited.add(map)) for (var e : map.entrySet()) {
                    collect(e.getKey(), acc, depth + 1, visited);
                    collect(e.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                if (visited.add(arr)) for (var item : arr) collect(item, acc, depth + 1, visited);
                return;
            }
            default -> {
            }
        }
        if (obj instanceof ItemStack || obj instanceof Ingredient) return;
        if (HarvestUtility.isTerminal(obj)) return;
        if (!visited.add(obj)) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields) {
            try {
                collect(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }
}