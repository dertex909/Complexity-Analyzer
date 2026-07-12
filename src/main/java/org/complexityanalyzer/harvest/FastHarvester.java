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

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.modules.*;

import static java.util.Locale.ROOT;

public final class FastHarvester {

    private static final ThreadLocal<ObjectArrayList<ItemStack>> TL_INPUT_ITEMS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(32));
    private static final ThreadLocal<ObjectArrayList<ItemStack>> TL_OUTPUT_ITEMS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<FluidStack>> TL_INPUT_FLUIDS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<FluidStack>> TL_OUTPUT_FLUIDS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<HarvestedItems.HarvestedIngredient>> TL_INPUT_INGREDIENTS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ReferenceOpenHashSet<Object>> TL_VISITED =
            ThreadLocal.withInitial(() -> new ReferenceOpenHashSet<>(128));
    private static final ThreadLocal<ReferenceOpenHashSet<Item>> TL_TRANSITIONAL_ITEMS =
            ThreadLocal.withInitial(() -> new ReferenceOpenHashSet<>(8));
    private static final ThreadLocal<ReferenceOpenHashSet<Ingredient>> TL_VISITED_INGREDIENTS =
            ThreadLocal.withInitial(() -> new ReferenceOpenHashSet<>(64));

    public static boolean visitIngredient(Ingredient ing) {
        if (ing == null || ing.isEmpty()) return false;
        return TL_VISITED_INGREDIENTS.get().add(ing);
    }

    private static void clearThreadLocals() {
        TL_INPUT_ITEMS.get().clear();
        TL_OUTPUT_ITEMS.get().clear();
        TL_INPUT_FLUIDS.get().clear();
        TL_OUTPUT_FLUIDS.get().clear();
        TL_INPUT_INGREDIENTS.get().clear();
        TL_VISITED.get().clear();
        TL_TRANSITIONAL_ITEMS.get().clear();
        TL_VISITED_INGREDIENTS.get().clear();
    }

    private static <T> ObjectArrayList<T> borrowList(ThreadLocal<ObjectArrayList<T>> tl) {
        var l = tl.get();
        l.clear();
        return l;
    }

    private static ReferenceOpenHashSet<Object> borrowMap() {
        var m = FastHarvester.TL_VISITED.get();
        m.clear();
        return m;
    }

    public HarvestedItems harvest(Object recipe, Level level) {
        if (recipe == null) return HarvestedItems.EMPTY;

        TL_VISITED_INGREDIENTS.get().clear();
        var inputItems = borrowList(TL_INPUT_ITEMS);
        var outputItems = borrowList(TL_OUTPUT_ITEMS);
        var inputIngredients = borrowList(TL_INPUT_INGREDIENTS);
        var inputFluids = borrowList(TL_INPUT_FLUIDS);
        var outputFluids = borrowList(TL_OUTPUT_FLUIDS);
        var visited = borrowMap();
        var transitional = TL_TRANSITIONAL_ITEMS.get();
        transitional.clear();

        try {
            var apiResult = ItemStack.EMPTY;
            boolean isVanillaRecipe = false;

            if (recipe instanceof Recipe<?> r) {
                var type = r.getType();
                if (type == RecipeType.CRAFTING || type == RecipeType.SMELTING || type == RecipeType.BLASTING
                        || type == RecipeType.SMOKING || type == RecipeType.CAMPFIRE_COOKING
                        || type == RecipeType.STONECUTTING || type == RecipeType.SMITHING) isVanillaRecipe = true;

                for (var ing : r.getIngredients())
                    if (ing != null && !ing.isEmpty()) {
                        visitIngredient(ing);
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                    }

                try {
                    apiResult = r.getResultItem(level.registryAccess());
                    if (!apiResult.isEmpty()) outputItems.add(apiResult.copy());
                } catch (Throwable ignored) {
                }
            }

            if (!isVanillaRecipe) {
                var accessors = RecipeReflection.getAccessors(recipe.getClass());
                for (var acc : accessors.itemAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw != null) DeepItemCollector.collect(raw, inputItems, 0, visited);
                    } catch (Throwable ignored) {
                    }
                }
                for (var acc : accessors.ingredientAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw instanceof Ingredient ing && !ing.isEmpty()) {
                            if (visitIngredient(ing)) {
                                inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                            }
                        } else if (raw != null) {
                            DeepIngredientCollector.collect(raw, inputIngredients, 0, visited);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                for (var acc : accessors.fluidAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw == null) continue;

                        if (acc.name().contains("output")) {
                            DeepFluidCollector.collect(raw, outputFluids, 0, new ReferenceOpenHashSet<>(64));
                        } else {
                            if (raw instanceof FluidStack fs && !fs.isEmpty()) inputFluids.add(fs.copy());
                            else DeepFluidCollector.collect(raw, inputFluids, 0, visited);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                for (var acc : accessors.probeAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw != null && !HarvestUtility.isEmptyContainer(raw)) {
                            String nameLower = acc.name().toLowerCase(ROOT);
                            if (nameLower.contains("output") || nameLower.contains("result")) {
                                var tempItems = new ObjectArrayList<ItemStack>(8);
                                DeepItemCollector.collect(raw, tempItems, 0, new ReferenceOpenHashSet<>(64));
                                outputItems.addAll(tempItems);
                                DeepFluidCollector.collect(raw, outputFluids, 0, new ReferenceOpenHashSet<>(64));
                            } else {
                                DeepUniversalCollector.collect(raw, inputItems, outputItems, inputIngredients, inputFluids, 0, visited, apiResult, level, transitional);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (inputItems.isEmpty() && outputItems.isEmpty() && inputIngredients.isEmpty() && inputFluids.isEmpty() && outputFluids.isEmpty()) {
                    visited.clear();
                    DeepUniversalCollector.collect(recipe, inputItems, outputItems, inputIngredients, inputFluids, 0, visited, apiResult, level, transitional);
                }
            }

            if (inputIngredients.isEmpty() && inputItems.isEmpty() && recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty())
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                }
            }
            if (outputItems.isEmpty() && recipe instanceof Recipe<?> r) try {
                var res = r.getResultItem(level.registryAccess());
                if (!res.isEmpty()) outputItems.add(res.copy());
            } catch (Throwable ignored) {
            }

            if (!transitional.isEmpty()) {
                inputItems.removeIf(stack -> transitional.contains(stack.getItem()));
                inputIngredients.removeIf(hi -> {
                    ItemStack[] items = hi.ingredient().getItems();
                    if (items.length == 0) return false;
                    for (ItemStack s : items) if (!transitional.contains(s.getItem())) return false;
                    return true;
                });
            }

            var registryAccess = level != null ? level.registryAccess() : null;
            if (!inputItems.isEmpty()) {
                var uniqueInputItems = new ObjectArrayList<ItemStack>();
                for (var stack : inputItems) {
                    boolean isDup = false;
                    for (var existing : uniqueInputItems) {
                        if (ItemStackIdentity.sameItemData(stack, existing, registryAccess)) {
                            isDup = true;
                            break;
                        }
                    }
                    if (!isDup) {
                        uniqueInputItems.add(stack);
                    }
                }
                inputItems.clear();
                inputItems.addAll(uniqueInputItems);
            }

            if (!inputIngredients.isEmpty()) inputItems.removeIf(stack -> {
                for (var hi : inputIngredients) {
                    for (ItemStack ingStack : hi.ingredient().getItems()) {
                        if (ItemStackIdentity.sameItemData(ingStack, stack, registryAccess)) return true;
                    }
                }
                return false;
            });

            return new HarvestedItems(
                    inputItems.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputItems),
                    outputItems.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(outputItems),
                    inputIngredients.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputIngredients),
                    inputFluids.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputFluids),
                    outputFluids.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(outputFluids),
                    recipe,
                    new ReferenceOpenHashSet<>(transitional)
            );
        } finally {
            clearThreadLocals();
        }
    }

    public void clearCaches() {
        RecipeReflection.clearCaches();
        AntivirusStyleDetector.clearCache();
        PatternSignatureEngine.clearCache();
        UniversalTypeResolver.clearCache();
        UniversalAccessorResolver.clearCache();
        TerminalTypeRegistry.clearCache();
    }
}