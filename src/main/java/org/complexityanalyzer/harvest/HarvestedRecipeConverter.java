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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

public final class HarvestedRecipeConverter {

    private HarvestedRecipeConverter() {
    }

    public static RecipeNode convert(HarvestedItems harvested, Level level) {
        if (harvested == null || harvested.isEmpty()) return null;

        ItemStack declaredResult = declaredRecipeResult(harvested.root(), level);
        var inputIngredients = harvested.inputIngredients();
        var inputStacks = harvested.inputItems();
        var outputStacks = harvested.outputItems();
        var inputFluids = harvested.inputFluids();
        var outputFluids = harvested.outputFluids();

        ItemStack output;
        boolean isPlaceholder = false;
        String placeholderId = "";

        if (declaredResult.isEmpty() && outputStacks.isEmpty() && !outputFluids.isEmpty()) {
            output = new ItemStack(Items.AIR);
            isPlaceholder = true;
            placeholderId = GameRegistryManager.getFluidId(outputFluids.getFirst().getFluid()).toString();
        } else {
            output = selectOutput(declaredResult, outputStacks.isEmpty() ? inputStacks : outputStacks);
        }

        if (output.isEmpty() && !isPlaceholder && !inputIngredients.isEmpty()) {
            ItemStack[] items = inputIngredients.getFirst().ingredient().getItems();
            if (items.length > 0) {
                output = items[0].copy();
                output.setCount(1);
            }
        }

        if (output.isEmpty() && !isPlaceholder) return null;

        var builder = new RecipeNode.Builder(
                output.getItem()).category(RecipeCategory.PRIMARY).resultCount(output.getCount()).rawRecipe();

        if (isPlaceholder) {
            builder.isPlaceholder(true);
            builder.placeholderId(placeholderId);
        }

        if (harvested.root() instanceof Recipe<?> recipe) builder.recipeType(recipe.getType());

        var transitionalItems = harvested.transitionalItems();
        boolean isSeqAss = !transitionalItems.isEmpty();

        var mergedIngredients = new Object2IntLinkedOpenHashMap<ObjectList<Item>>();

        for (var hi : inputIngredients) {
            Ingredient ingredient = hi.ingredient();
            int ingredientCount = hi.count();
            var variants = new ObjectArrayList<Item>();
            ItemStack[] stacks = ingredient.getItems();
            int limit = ComplexityConfig.MAX_INGREDIENT_VARIANTS.get();
            for (int i = 0; i < Math.min(stacks.length, limit); i++) {
                ItemStack stack = stacks[i];
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (isSeqAss && transitionalItems.contains(item)) continue;
                if (!variants.contains(item)) variants.add(item);
            }

            if (!variants.isEmpty()) {
                variants.sort((a, b) -> {
                    var idA = GameRegistryManager.getItemId(a);
                    var idB = GameRegistryManager.getItemId(b);
                    return idA.compareTo(idB);
                });
                if (isSeqAss) {
                    mergedIngredients.put(variants, ingredientCount);
                } else {
                    mergedIngredients.addTo(variants, ingredientCount);
                }
            }
        }

        if (isSeqAss && !transitionalItems.isEmpty() && !mergedIngredients.isEmpty()) {
            var firstKey = mergedIngredients.keySet().getFirst();
            for (Item transItem : transitionalItems) if (!firstKey.contains(transItem)) firstKey.add(transItem);
            firstKey.sort((a, b) -> {
                var idA = GameRegistryManager.getItemId(a);
                var idB = GameRegistryManager.getItemId(b);
                return idA.compareTo(idB);
            });
        }

        for (ItemStack stack : inputStacks) {
            if (!transitionalItems.isEmpty() && transitionalItems.contains(stack.getItem())) continue;
            if (!sameStackIdentity(stack, output)) {
                var variants = new ObjectArrayList<Item>();
                variants.add(stack.getItem());
                int count = Math.max(1, stack.getCount());
                mergedIngredients.addTo(variants, count);
            }
        }

        for (var entry : mergedIngredients.object2IntEntrySet()) {
            builder.addIngredient(entry.getKey(), entry.getIntValue());
        }

        if (!inputFluids.isEmpty()) {
            var seenFluids = new Reference2IntOpenHashMap<Fluid>();
            for (FluidStack fluid : inputFluids) {
                Fluid f = normalizeFluid(fluid.getFluid());
                if (f == Fluids.EMPTY) continue;
                int amt = fluid.getAmount();
                int existing = seenFluids.getInt(f);
                if (amt > existing) seenFluids.put(f, amt);
            }
            for (var entry : seenFluids.reference2IntEntrySet()) {
                var variants = new ObjectArrayList<Fluid>();
                variants.add(entry.getKey());
                builder.addFluidIngredient(variants, entry.getIntValue());
            }
        }

        if (!outputStacks.isEmpty()) {
            var deduplicatedOutputs = new ObjectArrayList<ItemStack>();
            for (ItemStack stack : outputStacks) {
                if (stack.isEmpty()) continue;
                boolean alreadyAdded = false;
                for (ItemStack existing : deduplicatedOutputs) {
                    if (existing.getItem() == stack.getItem() && existing.getCount() == stack.getCount()) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) deduplicatedOutputs.add(stack);
            }
            builder.itemOutputs(deduplicatedOutputs);
        }

        if (!outputFluids.isEmpty()) {
            var mergedOutputs = new Reference2IntOpenHashMap<Fluid>();
            for (FluidStack fluid : outputFluids) {
                Fluid f = normalizeFluid(fluid.getFluid());
                if (f == Fluids.EMPTY) continue;
                int amt = fluid.getAmount();
                int existing = mergedOutputs.getInt(f);
                if (amt > existing) mergedOutputs.put(f, amt);
            }
            var deduplicatedOutputs = new ObjectArrayList<FluidStack>();
            for (var entry : mergedOutputs.reference2IntEntrySet()) {
                deduplicatedOutputs.add(new FluidStack(entry.getKey(), entry.getIntValue()));
            }
            builder.fluidOutputs(deduplicatedOutputs);
        }

        return builder.build();
    }

    private static Fluid normalizeFluid(Fluid fluid) {
        var id = GameRegistryManager.getFluidId(fluid);
        var fluidName = id.toString();
        if (fluidName.contains("flowing_")) {
            var staticName = fluidName.replace("flowing_", "");
            try {
                var staticFluid = GameRegistryManager.getFluid(ResourceLocation.parse(staticName));
                if (staticFluid != null) return staticFluid;
            } catch (Throwable ignored) {
            }
        }
        return fluid;
    }

    private static ItemStack declaredRecipeResult(Object root, Level level) {
        if (root instanceof Recipe<?> recipe && level != null) try {
            return recipe.getResultItem(level.registryAccess()).copy();
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack selectOutput(ItemStack declaredResult, ObjectList<ItemStack> stacks) {
        if (!declaredResult.isEmpty()) return declaredResult;
        ItemStack best = ItemStack.EMPTY;
        int bestScore = Integer.MAX_VALUE;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            int score = stack.getCount() <= 0 ? Integer.MAX_VALUE : stack.getCount();
            if (best.isEmpty() || score < bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        return best.copy();
    }

    private static boolean sameStackIdentity(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && a.getItem() == b.getItem() && a.getCount() == b.getCount();
    }
}