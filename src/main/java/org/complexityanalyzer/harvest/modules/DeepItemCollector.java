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
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.core.GameRegistryManager;

import static net.minecraft.world.item.Items.AIR;

public final class DeepItemCollector {

    private DeepItemCollector() {
    }

    public static void collect(Object obj, ObjectList<ItemStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        DeepGraphTraverser.traverse(obj, depth, visited, (node, d) -> {
            switch (node) {
                case TagKey<?> tagKey -> {
                    if (tagKey.registry().equals(Registries.ITEM)) {
                        @SuppressWarnings("unchecked")
                        var itemTag = (TagKey<Item>) tagKey;
                        var firstItem = GameRegistryManager.getFirstItemByTag(itemTag);
                        if (firstItem != AIR) acc.add(new ItemStack(firstItem));
                    }
                    return true;
                }
                case SizedIngredient si when si.count() > 0 -> {
                    ItemStack[] stacks = si.ingredient().getItems();
                    if (stacks.length > 0) {
                        var stack = stacks[0].copy();
                        stack.setCount(si.count());
                        acc.add(stack);
                    }
                    return true;
                }
                case ItemStack stack when !stack.isEmpty() -> {
                    acc.add(stack.copy());
                    return true;
                }
                case Item item -> {
                    if (item != AIR) acc.add(new ItemStack(item));
                    return true;
                }
                case Block block -> {
                    var item = block.asItem();
                    if (item != AIR) acc.add(new ItemStack(item));
                    return true;
                }
                case Holder<?> holder -> {
                    if (visited.add(holder)) collect(holder.value(), acc, d + 1, visited);
                    return true;
                }
                default -> {
                }
            }
            return node instanceof FluidStack || node instanceof Ingredient;
        });
    }
}