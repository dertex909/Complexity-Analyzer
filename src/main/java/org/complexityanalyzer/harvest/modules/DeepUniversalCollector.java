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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.complexityanalyzer.harvest.FastHarvester;
import org.complexityanalyzer.harvest.HarvestedItems;
import org.complexityanalyzer.harvest.RecipeReflection;

import java.util.Map;
import java.util.Optional;

import static net.minecraft.world.item.Items.AIR;

public final class DeepUniversalCollector {

    private DeepUniversalCollector() {
    }

    public static void collect(Object obj, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                               ObjectList<HarvestedItems.HarvestedIngredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                               int depth, ReferenceOpenHashSet<Object> visited, ItemStack apiResult, Level level,
                               ReferenceOpenHashSet<Item> transitionalItems) {
        if (obj == null || depth > 8) return;

        if (depth > 0 && obj instanceof Recipe<?> subRecipe && level != null) {
            try {
                var subOutput = subRecipe.getResultItem(level.registryAccess());
                if (!subOutput.isEmpty() && !apiResult.isEmpty() && subOutput.getItem() != apiResult.getItem()) {
                    transitionalItems.add(subOutput.getItem());
                }

                var subIngs = subRecipe.getIngredients();
                if (subIngs.size() > 1) {
                    var toolIng = subIngs.get(1);
                    if (!toolIng.isEmpty() && FastHarvester.visitIngredient(toolIng))
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(toolIng, 1));
                }

                DeepFluidCollector.collect(subRecipe, inputFluids, 0, visited);
            } catch (Throwable ignored) {
            }
            return;
        }

        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o ->
                        collect(o, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level, transitionalItems));
                return;
            }
            case ItemStack stack when !stack.isEmpty() -> {
                if (!apiResult.isEmpty() && stack.getItem() == apiResult.getItem()) outputItems.add(stack.copy());
                else inputItems.add(stack.copy());
                return;
            }
            case Item item -> {
                if (item != AIR) {
                    var stack = new ItemStack(item);
                    if (!apiResult.isEmpty() && item == apiResult.getItem()) outputItems.add(stack);
                    else inputItems.add(stack);
                }
                return;
            }
            case Block block -> {
                var item = block.asItem();
                if (item != AIR) {
                    var stack = new ItemStack(item);
                    if (!apiResult.isEmpty() && item == apiResult.getItem()) outputItems.add(stack);
                    else inputItems.add(stack);
                }
                return;
            }
            case Holder<?> holder -> {
                if (visited.add(holder)) collect(holder.value(), inputItems, outputItems, inputIngredients,
                        inputFluids, depth + 1, visited, apiResult, level, transitionalItems);
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                var ing = si.ingredient();
                if (!ing.isEmpty() && FastHarvester.visitIngredient(ing))
                    inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                return;
            }
            case SizedFluidIngredient sfi -> {
                for (FluidStack fs : sfi.getFluids()) if (!fs.isEmpty()) inputFluids.add(fs);
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (FastHarvester.visitIngredient(ing)) {
                    inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                }
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                inputFluids.add(fs.copy());
                return;
            }
            case Iterable<?> coll when HarvestUtility.isTooSmall(coll) -> {
                if (visited.add(coll)) for (var item : coll)
                    collect(item, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level, transitionalItems);
                return;
            }
            case Map<?, ?> map when HarvestUtility.isTooSmall(map) -> {
                if (visited.add(map)) for (var e : map.entrySet()) {
                    var key = e.getKey();
                    if (key != null && !HarvestUtility.isTerminal(key))
                        collect(key, inputItems, outputItems, inputIngredients,
                                inputFluids, depth + 1, visited, apiResult, level, transitionalItems);
                    collect(e.getValue(), inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level, transitionalItems);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                if (visited.add(arr)) for (var item : arr)
                    collect(item, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level, transitionalItems);
                return;
            }
            default -> {
            }
        }
        if (HarvestUtility.isTerminal(obj)) return;
        if (!visited.add(obj)) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields) {
            try {
                var val = f.get(obj);
                if (val != null) collect(val, inputItems, outputItems, inputIngredients, inputFluids,
                        depth + 1, visited, apiResult, level, transitionalItems);
            } catch (Throwable ignored) {
            }
        }
    }
}