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
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.FastHarvester;
import org.complexityanalyzer.harvest.HarvestedItems;
import org.complexityanalyzer.harvest.RecipeReflection;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class DeepIngredientCollector {

    private DeepIngredientCollector() {
    }

    public static void collect(Object obj, ObjectList<HarvestedItems.HarvestedIngredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o -> collect(o, acc, depth + 1, visited));
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                var ing = si.ingredient();
                if (!ing.isEmpty() && FastHarvester.visitIngredient(ing))
                    acc.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (FastHarvester.visitIngredient(ing))
                    acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
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
        if (obj instanceof ItemStack || obj instanceof FluidStack) return;
        if (HarvestUtility.isTerminal(obj)) return;
        if (!visited.add(obj)) return;
        var meta = RecipeReflection.getMeta(obj.getClass());

        if (depth <= 5) for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            var h = meta.allHandles[i];
            if (h == null) continue;
            try {
                var rt = m.getReturnType();
                String rtName = rt.getName();

                if (Ingredient.class.isAssignableFrom(rt) || rt.isArray() || Iterable.class.isAssignableFrom(rt)
                        || Stream.class.isAssignableFrom(rt) || rtName.contains("Ingredient")) {

                    String mName = m.getName();
                    if (mName.equals("toString") || mName.equals("hashCode") || mName.equals("getClass")) continue;

                    var val = h.invoke(obj);
                    if (val != null && val != obj) if (val instanceof Stream<?> stream) {
                        stream.limit(100).forEach(element -> collect(element, acc, depth + 1, visited));
                    } else {
                        collect(val, acc, depth + 1, visited);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        for (var f : meta.scanFields) {
            try {
                collect(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }
}