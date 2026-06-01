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
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

import static net.minecraft.world.item.Items.AIR;
import static net.minecraft.world.level.material.Fluids.EMPTY;

public final class HarvestedRecipeConverter {

    private HarvestedRecipeConverter() {
    }

    public static RecipeNode convert(HarvestedItems harvested, Level level) {
        if (harvested == null || harvested.isEmpty()) return null;
        var registryAccess = level != null ? level.registryAccess() : null;

        var declaredResult = declaredRecipeResult(harvested.root(), level);
        var inputIngredients = harvested.inputIngredients();
        var inputStacks = harvested.inputItems();
        var outputStacks = harvested.outputItems();
        var inputFluids = harvested.inputFluids();
        var outputFluids = harvested.outputFluids();

        ItemStack output;
        boolean isPlaceholder = false;
        String placeholderId = "";

        if (declaredResult.isEmpty() && outputStacks.isEmpty() && !outputFluids.isEmpty()) {
            output = new ItemStack(AIR);
            isPlaceholder = true;
            placeholderId = GameRegistryManager.getFluidId(outputFluids.getFirst().getFluid()).toString();
        } else {
            output = selectOutput(declaredResult, outputStacks.isEmpty() ? inputStacks : outputStacks, registryAccess);
        }

        if (output.isEmpty() && !isPlaceholder && !inputIngredients.isEmpty()) {
            ItemStack[] items = inputIngredients.getFirst().ingredient().getItems();
            if (items.length > 0) output = new ItemStack(items[0].getItem(), 1);
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

        var mergedIngredients = new Object2IntLinkedOpenHashMap<ObjectList<ItemStack>>();

        for (var hi : inputIngredients) {
            var ingredient = hi.ingredient();
            int ingredientCount = hi.count();
            var variants = new ObjectArrayList<ItemStack>();
            ItemStack[] stacks = ingredient.getItems();
            int limit = ComplexityConfig.MAX_INGREDIENT_VARIANTS.get();
            for (int i = 0; i < Math.min(stacks.length, limit); i++) {
                var stack = stacks[i];
                if (stack.isEmpty()) continue;
                var item = stack.getItem();
                if (isSeqAss && transitionalItems.contains(item)) continue;
                if (!containsSameStackData(variants, stack)) variants.add(stack.copyWithCount(1));
            }

            if (!variants.isEmpty()) {
                variants.sort((a, b) -> {
                    var idA = GameRegistryManager.getItemId(a.getItem());
                    var idB = GameRegistryManager.getItemId(b.getItem());
                    int byId = idA.compareTo(idB);
                    if (byId != 0) return byId;
                    return ItemStackIdentity.dataKey(a, registryAccess).compareTo(ItemStackIdentity.dataKey(b, registryAccess));
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
            for (var transItem : transitionalItems) {
                var transStack = new ItemStack(transItem);
                if (!containsSameStackData(firstKey, transStack)) firstKey.add(transStack);
            }
            firstKey.sort((a, b) -> {
                var idA = GameRegistryManager.getItemId(a.getItem());
                var idB = GameRegistryManager.getItemId(b.getItem());
                int byId = idA.compareTo(idB);
                if (byId != 0) return byId;
                return ItemStackIdentity.dataKey(a, registryAccess).compareTo(ItemStackIdentity.dataKey(b, registryAccess));
            });
        }

        for (ItemStack stack : inputStacks) {
            if (!transitionalItems.isEmpty() && transitionalItems.contains(stack.getItem())) continue;
            if (!sameStackIdentity(stack, output, registryAccess)) {
                var variants = new ObjectArrayList<ItemStack>();
                variants.add(stack.copyWithCount(1));
                int count = Math.max(1, stack.getCount());
                mergedIngredients.addTo(variants, count);
            }
        }

        for (var entry : mergedIngredients.object2IntEntrySet()) {
            builder.addIngredient(entry.getKey(), entry.getIntValue());
        }

        if (!inputFluids.isEmpty()) {
            var seenFluids = new Reference2IntOpenHashMap<Fluid>();
            for (var fluid : inputFluids) {
                var f = normalizeFluid(fluid.getFluid());
                if (f == EMPTY) continue;
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
            for (var stack : outputStacks) {
                if (stack.isEmpty()) continue;
                boolean alreadyAdded = false;
                for (var existing : deduplicatedOutputs) {
                    if (ItemStackIdentity.sameItemDataAndCount(existing, stack, registryAccess)) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) deduplicatedOutputs.add(stack.copy());
            }
            builder.itemOutputs(deduplicatedOutputs);
        }

        if (!outputFluids.isEmpty()) {
            var mergedOutputs = new Reference2IntOpenHashMap<Fluid>();
            for (var fluid : outputFluids) {
                var f = normalizeFluid(fluid.getFluid());
                if (f == EMPTY) continue;
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

    private static ItemStack selectOutput(ItemStack declaredResult, ObjectList<ItemStack> stacks,
                                          HolderLookup.Provider provider) {
        if (!declaredResult.isEmpty()) {
            for (var stack : stacks) {
                if (stack.isEmpty() || stack.getItem() != declaredResult.getItem()) continue;
                if (!ItemStackIdentity.hasStackData(declaredResult, provider) && ItemStackIdentity.hasStackData(stack, provider)) {
                    return stack.copy();
                }
            }
            return declaredResult.copy();
        }
        var best = ItemStack.EMPTY;
        int bestScore = Integer.MAX_VALUE;
        for (var stack : stacks) {
            if (stack.isEmpty()) continue;
            int score = stack.getCount() <= 0 ? Integer.MAX_VALUE : stack.getCount();
            if (best.isEmpty() || score < bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        return best.copy();
    }

    private static boolean sameStackIdentity(ItemStack a, ItemStack b, HolderLookup.Provider provider) {
        return ItemStackIdentity.sameItemDataAndCount(a, b, provider);
    }

    private static boolean containsSameStackData(ObjectList<ItemStack> stacks, ItemStack candidate) {
        for (var stack : stacks) {
            if (ItemStackIdentity.sameItemData(stack, candidate)) return true;
        }
        return false;
    }
}