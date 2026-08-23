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

package org.complexityanalyzer.harvest.engine;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
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
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.harvest.inspector.RecipeMetadata;
import org.complexityanalyzer.harvest.inspector.TerminalTypeRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static net.minecraft.core.registries.Registries.ITEM;
import static net.minecraft.world.item.Items.AIR;

public final class DeepCollector {

    private static final int MAX_DEPTH = 8;
    private static final int SMALL_COLLECTION = 50;

    private DeepCollector() {
    }

    public static void traverse(Object obj, int depth, ReferenceOpenHashSet<Object> visited, Visitor visitor) {
        if (obj == null || depth > MAX_DEPTH || visitor.visit(obj, depth)) return;

        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o -> traverse(o, depth + 1, visited, visitor));
                return;
            }
            case Either<?, ?> either -> {
                if (visited.add(either)) {
                    either.left().ifPresent(o -> traverse(o, depth + 1, visited, visitor));
                    either.right().ifPresent(o -> traverse(o, depth + 1, visited, visitor));
                }
                return;
            }
            case Pair<?, ?> pair -> {
                if (visited.add(pair)) {
                    traverse(pair.getFirst(), depth + 1, visited, visitor);
                    traverse(pair.getSecond(), depth + 1, visited, visitor);
                }
                return;
            }
            case Iterable<?> coll when !(coll instanceof Collection<?> c) || c.size() <= SMALL_COLLECTION -> {
                if (visited.add(coll)) for (var item : coll) traverse(item, depth + 1, visited, visitor);
                return;
            }
            case Map<?, ?> map when map.size() <= SMALL_COLLECTION -> {
                if (visited.add(map)) for (var e : map.entrySet()) {
                    traverse(e.getKey(), depth + 1, visited, visitor);
                    traverse(e.getValue(), depth + 1, visited, visitor);
                }
                return;
            }
            case Object[] arr when arr.length <= SMALL_COLLECTION -> {
                if (visited.add(arr)) for (var item : arr) traverse(item, depth + 1, visited, visitor);
                return;
            }
            default -> {
            }
        }
        if (TerminalTypeRegistry.isTerminalType(obj.getClass()) || !visited.add(obj)) return;

        var meta = RecipeMetadata.getMeta(obj.getClass());
        for (var f : meta.scanFields()) {
            try {
                traverse(f.get(obj), depth + 1, visited, visitor);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void collectItems(Object obj, ObjectList<ItemStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        traverse(obj, depth, visited, (node, d) -> {
            switch (node) {
                case TagKey<?> tagKey -> {
                    var item = extractItem(tagKey);
                    if (item != null) acc.add(new ItemStack(item));
                    return true;
                }
                case SizedIngredient si when si.count() > 0 -> {
                    var stacks = si.ingredient().getItems();
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
                    if (visited.add(holder)) collectItems(holder.value(), acc, d + 1, visited);
                    return true;
                }
                default -> {
                }
            }
            return node instanceof FluidStack || node instanceof Ingredient;
        });
    }

    public static void collectIngredients(Object obj, ObjectList<HarvestedItems.HarvestedIngredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        traverse(obj, depth, visited, (node, d) -> {
            if (extractIngredient(node, acc)) return true;
            return node instanceof ItemStack || node instanceof FluidStack;
        });
    }

    public static void collectFluids(Object obj, ObjectList<FluidStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        traverse(obj, depth, visited, (node, d) -> {
            if (node instanceof TagKey<?> tagKey) {
                var firstFluid = GameRegistryManager.getFirstFluidByTag(tagKey);
                if (firstFluid != Fluids.EMPTY) acc.add(new FluidStack(firstFluid, 1000));
                return true;
            }
            if (extractFluids(node, acc)) return true;
            return node instanceof ItemStack || node instanceof Ingredient;
        });
    }

    public static void collectUniversal(Object obj, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                        ObjectList<HarvestedItems.HarvestedIngredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                                        int depth, ReferenceOpenHashSet<Object> visited, Item apiResultItem, Level level,
                                        ReferenceOpenHashSet<Item> transitionalItems) {
        traverse(obj, depth, visited, (node, d) -> {
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

                    collectFluids(subRecipe, inputFluids, 0, visited);
                } catch (Throwable ignored) {
                }
                return true;
            }
            switch (node) {
                case TagKey<?> tagKey -> {
                    var item = extractItem(tagKey);
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
                    if (visited.add(holder)) collectUniversal(holder.value(), inputItems, outputItems, inputIngredients,
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

    @Nullable
    private static Item extractItem(Object node) {
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

    private static boolean extractIngredient(Object node, ObjectList<HarvestedItems.HarvestedIngredient> acc) {
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

    private static boolean extractFluids(Object node, ObjectList<FluidStack> acc) {
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
        } else if (apiResultItem != null) {
            outputItems.add(stack.copy());
        } else {
            inputItems.add(stack.copy());
        }
    }

    @FunctionalInterface
    public interface Visitor {
        boolean visit(Object node, int depth);
    }
}