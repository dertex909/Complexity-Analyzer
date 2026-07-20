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
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.RecipeReflection;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static net.minecraft.world.item.Items.AIR;

public final class DeepItemCollector {

    private DeepItemCollector() {
    }

    public static void collect(Object obj, ObjectList<ItemStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o -> collect(o, acc, depth + 1, visited));
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                if (visited.add(si)) {
                    ItemStack[] stacks = si.ingredient().getItems();
                    if (stacks.length > 0) {
                        var stack = stacks[0].copy();
                        stack.setCount(si.count());
                        acc.add(stack);
                    }
                }
                return;
            }
            case ItemStack stack when !stack.isEmpty() -> {
                acc.add(stack.copy());
                return;
            }
            case Item item -> {
                if (item != AIR) acc.add(new ItemStack(item));
                return;
            }
            case Block block -> {
                var item = block.asItem();
                if (item != AIR) acc.add(new ItemStack(item));
                return;
            }
            case Holder<?> holder -> {
                if (visited.add(holder)) collect(holder.value(), acc, depth + 1, visited);
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
        if (obj instanceof FluidStack || obj instanceof Ingredient) return;
        if (HarvestUtility.isTerminal(obj)) return;
        if (!visited.add(obj)) return;
        var meta = RecipeReflection.getMeta(obj.getClass());

        if (depth <= 5) for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            var h = meta.allHandles[i];
            try {
                var rt = m.getReturnType();
                String rtName = rt.getName();

                if (ItemStack.class.isAssignableFrom(rt) || rt.isArray() || Iterable.class.isAssignableFrom(rt)
                        || Stream.class.isAssignableFrom(rt) || rtName.contains("Item") || rtName.contains("Stack")) {

                    String mName = m.getName();
                    if (mName.equals("toString") || mName.equals("hashCode") || mName.equals("getClass")
                            || mName.equals("getItem")) continue;

                    var val = invokeSafe(m, h, obj);
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

    private static Object invokeSafe(Method m, MethodHandle h, Object obj) throws Throwable {
        return h != null ? h.invoke(obj) : m.invoke(obj);
    }
}