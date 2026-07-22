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
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.FastHarvester;
import org.complexityanalyzer.harvest.HarvestedItems;

import static net.minecraft.core.registries.Registries.ITEM;

public final class DeepIngredientCollector {

    private DeepIngredientCollector() {
    }

    public static void collect(Object obj, ObjectList<HarvestedItems.HarvestedIngredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        DeepGraphTraverser.traverse(obj, depth, visited, (node, d) -> {
            switch (node) {
                case TagKey<?> tagKey -> {
                    if (tagKey.registry().equals(ITEM)) {
                        @SuppressWarnings("unchecked")
                        var itemTag = (TagKey<Item>) tagKey;
                        var ing = Ingredient.of(itemTag);
                        if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                            acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                        }
                    }
                    return true;
                }
                case SizedIngredient si when si.count() > 0 -> {
                    var ing = si.ingredient();
                    if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                        acc.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                    }
                    return true;
                }
                case Ingredient ing when !ing.isEmpty() -> {
                    if (FastHarvester.visitIngredient(ing)) acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                    return true;
                }
                default -> {
                }
            }
            return node instanceof ItemStack || node instanceof FluidStack;
        });
    }
}