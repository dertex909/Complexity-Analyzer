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

package org.complexityanalyzer.harvest.collector;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.complexityanalyzer.harvest.engine.FastHarvester;
import org.complexityanalyzer.harvest.engine.HarvestedItems;

import static net.minecraft.world.item.Items.AIR;

public final class DeepUniversalCollector {

    private DeepUniversalCollector() {
    }

    public static void collect(Object obj, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                               ObjectList<HarvestedItems.HarvestedIngredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                               int depth, ReferenceOpenHashSet<Object> visited, Item apiResultItem, Level level,
                               ReferenceOpenHashSet<Item> transitionalItems) {
        DeepGraphTraverser.traverse(obj, depth, visited, (node, d) -> {
            if (d > 0 && node instanceof Recipe<?> subRecipe && level != null) {
                try {
                    var subOutput = subRecipe.getResultItem(level.registryAccess());
                    if (!subOutput.isEmpty() && apiResultItem != null && subOutput.getItem() != apiResultItem) {
                        transitionalItems.add(subOutput.getItem());
                    }

                    var subIngs = subRecipe.getIngredients();
                    if (subIngs.size() > 1) {
                        var toolIng = subIngs.get(1);
                        if (!toolIng.isEmpty() && FastHarvester.visitIngredient(toolIng)) {
                            inputIngredients.add(new HarvestedItems.HarvestedIngredient(toolIng, 1));
                        }
                    }

                    DeepFluidCollector.collect(subRecipe, inputFluids, 0, visited);
                } catch (Throwable ignored) {
                }
                return true;
            }

            switch (node) {
                case TagKey<?> tagKey -> {
                    var item = CollectorHelper.extractItem(tagKey);
                    if (item != null) routeItemStack(new ItemStack(item), inputItems, outputItems, apiResultItem);
                    return true;
                }
                case ItemStack stack -> {
                    if (!stack.isEmpty()) routeItemStack(stack, inputItems, outputItems, apiResultItem);
                    return true;
                }
                case Item item -> {
                    if (item != AIR) routeItemStack(new ItemStack(item), inputItems, outputItems, apiResultItem);
                    return true;
                }
                case Block block -> {
                    var item = block.asItem();
                    if (item != AIR) routeItemStack(new ItemStack(item), inputItems, outputItems, apiResultItem);
                    return true;
                }
                case Holder<?> holder -> {
                    if (visited.add(holder)) collect(holder.value(), inputItems, outputItems, inputIngredients,
                            inputFluids, d + 1, visited, apiResultItem, level, transitionalItems);
                    return true;
                }
                case SizedIngredient si -> {
                    if (si.count() > 0) {
                        var ing = si.ingredient();
                        if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                            inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                        }
                    }
                    return true;
                }
                case SizedFluidIngredient sfi -> {
                    for (var fs : sfi.getFluids()) if (!fs.isEmpty()) inputFluids.add(fs);
                    return true;
                }
                case Ingredient ing -> {
                    if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                    }
                    return true;
                }
                case FluidStack fs -> {
                    if (!fs.isEmpty()) inputFluids.add(fs.copy());
                    return true;
                }
                default -> {
                }
            }
            return false;
        });
    }

    private static void addOrUpdateOutput(ObjectList<ItemStack> outputItems, ItemStack stack, Item apiResultItem) {
        for (var existing : outputItems) {
            if (existing.getItem() == apiResultItem) {
                if (stack.getCount() > existing.getCount()) existing.setCount(stack.getCount());
                return;
            }
        }
        outputItems.add(stack.copy());
    }

    private static void routeItemStack(ItemStack stack, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems, Item apiResultItem) {
        if (stack == null || stack.isEmpty() || stack.getItem() == AIR) return;
        if (apiResultItem != null && stack.getItem() == apiResultItem) {
            addOrUpdateOutput(outputItems, stack, apiResultItem);
        } else {
            inputItems.add(stack.copy());
        }
    }
}