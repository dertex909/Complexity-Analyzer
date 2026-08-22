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

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.inspector.*;

import static java.util.Locale.ROOT;
import static net.minecraft.world.item.Items.AIR;

public final class FastHarvester {

    private static final ThreadLocal<HarvestSession> SESSION = ThreadLocal.withInitial(HarvestSession::new);

    public static boolean visitIngredient(Ingredient ing) {
        if (ing == null || ing.isEmpty()) return false;
        return SESSION.get().visitedIngredients.add(ing);
    }

    public HarvestedItems harvest(Object recipe, Level level) {
        if (recipe == null) return HarvestedItems.EMPTY;

        var session = SESSION.get();
        session.reset();

        var inputItems = session.inputItems;
        var outputItems = session.outputItems;
        var inputIngredients = session.inputIngredients;
        var inputFluids = session.inputFluids;
        var outputFluids = session.outputFluids;
        var visited = session.visited;
        var transitional = session.transitionalItems;

        var extRaw = new ReferenceOpenHashSet<>(16);

        try {
            var apiResult = ItemStack.EMPTY;
            boolean isVanillaRecipe = false;

            if (recipe instanceof Recipe<?> r) {
                var type = r.getType();
                if (type == RecipeType.CRAFTING || type == RecipeType.SMELTING || type == RecipeType.BLASTING
                        || type == RecipeType.SMOKING || type == RecipeType.CAMPFIRE_COOKING
                        || type == RecipeType.STONECUTTING || type == RecipeType.SMITHING) isVanillaRecipe = true;

                for (var ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) {
                        visitIngredient(ing);
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                    }
                }

                try {
                    apiResult = r.getResultItem(level.registryAccess());
                } catch (Throwable ignored) {
                }

                if (apiResult.isEmpty()) {
                    var meta = RecipeMetadata.getMeta(recipe.getClass());
                    for (var f : meta.allFields()) {
                        if (f.getType() == ItemStack.class) try {
                            var fieldValue = (ItemStack) f.get(recipe);
                            if (fieldValue != null && !fieldValue.isEmpty() && fieldValue.getItem() != AIR) {
                                apiResult = fieldValue;
                                break;
                            }
                        } catch (Throwable ignored2) {
                        }
                    }
                }

                if (isVanillaRecipe && !apiResult.isEmpty() && apiResult.getItem() != AIR) {
                    outputItems.add(apiResult.copy());
                }
            }

            var apiResultItem = apiResult.isEmpty() ? null : apiResult.getItem();
            if (!isVanillaRecipe) {
                var accessors = RecipeMetadata.getFastAccessors(recipe.getClass());
                var containerOutputs = new ObjectArrayList<ItemStack>();

                for (var acc : accessors.itemAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw != null && extRaw.add(raw)) {
                            var tempItems = new ObjectArrayList<ItemStack>();
                            DeepCollector.collectItems(raw, tempItems, 0, visited);
                            for (var stack : tempItems) {
                                if (stack.isEmpty() || stack.getItem() == AIR) continue;
                                if (apiResultItem != null && stack.getItem() == apiResultItem) {
                                    boolean alreadyPresent = false;
                                    for (var existing : outputItems) {
                                        if (existing.getItem() == apiResultItem) {
                                            alreadyPresent = true;
                                            if (stack.getCount() > existing.getCount()) {
                                                existing.setCount(stack.getCount());
                                            }
                                            break;
                                        }
                                    }
                                    if (!alreadyPresent) outputItems.add(stack.copy());
                                } else {
                                    inputItems.add(stack);
                                }
                            }
                        }
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
                        } else if (raw != null && extRaw.add(raw)) {
                            DeepCollector.collectIngredients(raw, inputIngredients, 0, visited);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                for (var acc : accessors.fluidAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw == null) continue;

                        if (acc.name().contains("output")) {
                            if (extRaw.add(raw)) {
                                var visitedSecondary = session.visitedSecondary;
                                visitedSecondary.clear();
                                DeepCollector.collectFluids(raw, outputFluids, 0, visitedSecondary);
                            }
                        } else {
                            if (raw instanceof FluidStack fs && !fs.isEmpty()) {
                                inputFluids.add(fs.copy());
                            } else if (extRaw.add(raw)) {
                                DeepCollector.collectFluids(raw, inputFluids, 0, visited);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                for (var acc : accessors.probeAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw != null && RecipeMetadata.isNotEmptyContainer(raw) && extRaw.add(raw)) {
                            String nameLower = acc.name().toLowerCase(ROOT);
                            if (nameLower.contains("output") || nameLower.contains("result")) {
                                var tempItems = new ObjectArrayList<ItemStack>(8);
                                var visitedSecondary = session.visitedSecondary;
                                visitedSecondary.clear();
                                DeepCollector.collectItems(raw, tempItems, 0, visitedSecondary);
                                containerOutputs.addAll(tempItems);
                                visitedSecondary.clear();
                                DeepCollector.collectFluids(raw, outputFluids, 0, visitedSecondary);
                            } else {
                                DeepCollector.collectUniversal(raw, inputItems, outputItems, inputIngredients, inputFluids, 0, visited, apiResultItem, level, transitional);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (!containerOutputs.isEmpty()) {
                    outputItems.clear();
                    outputItems.addAll(containerOutputs);
                }

                if (inputItems.isEmpty() && outputItems.isEmpty() && inputIngredients.isEmpty() && inputFluids.isEmpty() && outputFluids.isEmpty()) {
                    visited.clear();
                    DeepCollector.collectUniversal(recipe, inputItems, outputItems, inputIngredients, inputFluids, 0, visited, apiResultItem, level, transitional);
                }
            }

            if (inputIngredients.isEmpty() && inputItems.isEmpty() && recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty())
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                }
            }

            if (outputItems.isEmpty()) {
                if (!apiResult.isEmpty() && apiResult.getItem() != AIR) {
                    outputItems.add(apiResult.copy());
                } else if (recipe instanceof Recipe<?> r) {
                    try {
                        var res = r.getResultItem(level.registryAccess());
                        if (!res.isEmpty() && res.getItem() != AIR) outputItems.add(res.copy());
                    } catch (Throwable ignored) {
                    }
                }
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
                    if (!isDup) uniqueInputItems.add(stack);
                }
                inputItems.clear();
                inputItems.addAll(uniqueInputItems);
            }

            if (!inputIngredients.isEmpty()) inputItems.removeIf(stack -> {
                for (var hi : inputIngredients) {
                    for (var ingStack : hi.ingredient().getItems()) {
                        if (ItemStackIdentity.sameItemData(ingStack, stack, registryAccess)) return true;
                    }
                }
                return false;
            });

            if (!outputItems.isEmpty()) inputItems.removeIf(stack -> {
                for (var outStack : outputItems) {
                    if (ItemStackIdentity.sameItemData(stack, outStack, registryAccess)) return true;
                }
                return false;
            });

            return new HarvestedItems(
                    inputItems.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputItems),
                    outputItems.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(outputItems),
                    inputIngredients.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputIngredients),
                    inputFluids.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputFluids),
                    outputFluids.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(outputFluids),
                    recipe, new ReferenceOpenHashSet<>(transitional)
            );
        } finally {
            session.reset();
        }
    }

    public void clearCaches() {
        RecipeMetadata.clearCaches();
        AntivirusStyleDetector.clearCache();
        PatternSignatureEngine.clearCache();
        UniversalTypeResolver.clearCache();
        TerminalTypeRegistry.clearCache();
    }
}