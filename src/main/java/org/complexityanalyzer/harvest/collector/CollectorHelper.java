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
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.harvest.engine.FastHarvester;
import org.complexityanalyzer.harvest.engine.HarvestedItems;
import org.jetbrains.annotations.Nullable;

import static net.minecraft.core.registries.Registries.ITEM;
import static net.minecraft.world.item.Items.AIR;

final class CollectorHelper {

    private CollectorHelper() {
    }

    @Nullable
    public static Item extractItem(Object node) {
        return switch (node) {
            case Item item when item != AIR -> item;
            case Block block -> {
                var it = block.asItem();
                yield it != AIR ? it : null;
            }
            case TagKey<?> tagKey -> {
                var it = GameRegistryManager.getFirstItemByTag(tagKey);
                yield it != AIR ? it : null;
            }
            default -> null;
        };
    }

    public static boolean extractIngredient(Object node, ObjectList<HarvestedItems.HarvestedIngredient> acc) {
        switch (node) {
            case TagKey<?> tagKey -> {
                if (tagKey.isFor(ITEM)) {
                    var itemTag = TagKey.create(ITEM, tagKey.location());
                    var ing = Ingredient.of(itemTag);
                    if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                        acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                    }
                }
                return true;
            }
            case SizedIngredient si -> {
                if (si.count() > 0) {
                    var ing = si.ingredient();
                    if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                        acc.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                    }
                }
                return true;
            }
            case Ingredient ing -> {
                if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                    acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public static boolean extractFluids(Object node, ObjectList<FluidStack> acc) {
        switch (node) {
            case SizedFluidIngredient sfi -> {
                for (var fs : sfi.getFluids()) if (!fs.isEmpty()) acc.add(fs.copy());
                return true;
            }
            case FluidStack fs -> {
                if (!fs.isEmpty()) acc.add(fs.copy());
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}