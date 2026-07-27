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

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.ItemStackCanonicalizer;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.regex.Pattern;

import static net.minecraft.core.component.DataComponents.CUSTOM_NAME;
import static net.minecraft.world.item.Items.AIR;
import static net.minecraft.world.level.material.Fluids.EMPTY;
import static org.complexityanalyzer.util.FluidNormalizer.normalize;

public final class HarvestedRecipeConverter {

    private static final Pattern UNBOUND_KEY = Pattern.compile("cannot be bound[^']*key='([^']+)'");

    private HarvestedRecipeConverter() {
    }

    private static boolean isValid(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() != AIR;
    }

    private static ItemStack recoverUnbound(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return stack;
        var name = stack.get(CUSTOM_NAME);
        if (name == null) return stack;
        var matcher = UNBOUND_KEY.matcher(name.getString());
        if (!matcher.find()) return stack;
        var id = descriptionIdToItemId(matcher.group(1));
        if (id == null) return stack;
        var item = GameRegistryManager.getItem(id);
        return (item != null && item != AIR) ? new ItemStack(item) : stack;
    }

    private static ResourceLocation descriptionIdToItemId(String descriptionId) {
        int firstDot = descriptionId.indexOf('.');
        if (firstDot < 0) return null;
        String rest = descriptionId.substring(firstDot + 1);
        int nsDot = rest.indexOf('.');
        if (nsDot < 0) return null;
        String namespace = rest.substring(0, nsDot);
        String path = rest.substring(nsDot + 1).replace('.', '/');
        try {
            return ResourceLocation.fromNamespaceAndPath(namespace, path);
        } catch (Throwable t) {
            return null;
        }
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
            var fluid = outputFluids.getFirst().getFluid();
            var bucketStack = ItemStack.EMPTY;
            try {
                bucketStack = fluid.getFluidType().getBucket(outputFluids.getFirst());
            } catch (Throwable ignored) {
            }

            if (!bucketStack.isEmpty() && bucketStack.getItem() != AIR) {
                output = bucketStack.copy();
            } else {
                return null;
            }
            isPlaceholder = true;
            placeholderId = GameRegistryManager.getFluidId(fluid).toString();
        } else {
            output = selectOutput(declaredResult, outputStacks.isEmpty() ? inputStacks : outputStacks, registryAccess);
        }

        if ((output.isEmpty() || output.getItem() == AIR) && !isPlaceholder && !inputIngredients.isEmpty()) {
            ItemStack[] items = inputIngredients.getFirst().ingredient().getItems();
            if (items.length > 0 && items[0].getItem() != AIR) output = new ItemStack(items[0].getItem(), 1);
        }

        if ((output.isEmpty() || output.getItem() == AIR) && !isPlaceholder) return null;

        var builder = new RecipeNode.Builder(output.getItem()).resultCount(output.getCount()).rawRecipe();

        if (isPlaceholder) {
            builder.isPlaceholder(true);
            builder.placeholderId(placeholderId);
        }

        if (harvested.root() instanceof Recipe<?> recipe) builder.recipeType(recipe.getType());

        var transitionalItems = harvested.transitionalItems();
        boolean isSeqAss = !transitionalItems.isEmpty();

        final var mergedIngredients = getMergedIngredients(registryAccess);
        final var itemComparator = new ItemStackComparator(registryAccess);

        for (var hi : inputIngredients) {
            var ingredient = hi.ingredient();
            int ingredientCount = hi.count();
            var variants = new ObjectArrayList<ItemStack>();
            int limit = ComplexityConfig.MAX_INGREDIENT_VARIANTS.get();
            for (var raw : ingredient.getItems()) {
                var stack = recoverUnbound(raw);
                if (!isValid(stack)) continue;
                var item = stack.getItem();
                if (isSeqAss && transitionalItems.contains(item)) continue;
                if (isUniqueStackData(variants, stack)) variants.add(ItemStackCanonicalizer.canonicalize(stack));
            }

            if (!variants.isEmpty()) {
                if (variants.size() > 1) variants.sort(itemComparator);
                if (variants.size() > limit) variants.removeElements(limit, variants.size());
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
                if (isUniqueStackData(firstKey, transStack))
                    firstKey.add(ItemStackCanonicalizer.canonicalize(transStack));
            }
            if (firstKey.size() > 1) firstKey.sort(itemComparator);
        }

        for (var raw : inputStacks) {
            var stack = recoverUnbound(raw);
            if (!isValid(stack)) continue;
            var item = stack.getItem();
            if (!transitionalItems.isEmpty() && transitionalItems.contains(item)) continue;
            if (!sameStackIdentity(stack, output, registryAccess)) {
                var variants = new ObjectArrayList<ItemStack>();
                variants.add(ItemStackCanonicalizer.canonicalize(stack));
                mergedIngredients.addTo(variants, Math.max(1, stack.getCount()));
            }
        }

        for (var entry : mergedIngredients.object2IntEntrySet()) {
            builder.addIngredient(entry.getKey(), entry.getIntValue());
        }

        if (!inputFluids.isEmpty()) {
            var seenFluids = mergeFluids(inputFluids);
            for (var entry : seenFluids.reference2IntEntrySet()) {
                var variants = new ObjectArrayList<Fluid>();
                variants.add(entry.getKey());
                builder.addFluidIngredient(variants, entry.getIntValue());
            }
        }

        var mutableOutputStacks = new ObjectArrayList<>(outputStacks);

        for (var hi : inputIngredients) {
            ItemStack[] items = hi.ingredient().getItems();
            if (items.length > 0) addRemainingItem(mutableOutputStacks, items[0], hi.count());
        }

        for (var raw : inputStacks) {
            var stack = recoverUnbound(raw);
            addRemainingItem(mutableOutputStacks, stack, Math.max(1, stack.getCount()));
        }

        if (!mutableOutputStacks.isEmpty()) {
            var deduplicatedOutputs = new ObjectArrayList<ItemStack>();
            for (var stack : mutableOutputStacks) {
                if (!isValid(stack)) continue;
                boolean alreadyAdded = false;
                for (var existing : deduplicatedOutputs) {
                    if (ItemStackIdentity.sameItemData(existing, stack, registryAccess)) {
                        if (existing.getCount() + stack.getCount() <= 64) {
                            existing.setCount(existing.getCount() + stack.getCount());
                            alreadyAdded = true;
                            break;
                        }
                    }
                }

                if (!alreadyAdded) {
                    int remaining = stack.getCount();
                    while (remaining > 0) {
                        int chunk = Math.min(64, remaining);
                        remaining -= chunk;
                        deduplicatedOutputs.add(ItemStackCanonicalizer.canonicalize(stack.copyWithCount(chunk)));
                    }
                }
            }
            builder.itemOutputs(deduplicatedOutputs);
        }

        if (!outputFluids.isEmpty()) {
            var mergedOutputs = mergeFluids(outputFluids);
            var deduplicatedOutputs = new ObjectArrayList<FluidStack>();
            for (var entry : mergedOutputs.reference2IntEntrySet()) {
                deduplicatedOutputs.add(new FluidStack(entry.getKey(), entry.getIntValue()));
            }
            builder.fluidOutputs(deduplicatedOutputs);
        }

        return builder.build();
    }

    private static @NotNull Object2IntLinkedOpenCustomHashMap<ObjectList<ItemStack>> getMergedIngredients(RegistryAccess registryAccess) {
        var strategy = new Hash.Strategy<ObjectList<ItemStack>>() {
            @Override
            public int hashCode(ObjectList<ItemStack> o) {
                if (o == null) return 0;
                int h = 1;
                for (var stack : o) {
                    h = 31 * h + (isValid(stack) ? ItemStackIdentity.hashItemData(stack, registryAccess) : 0);
                }
                return h;
            }

            @Override
            public boolean equals(ObjectList<ItemStack> a, ObjectList<ItemStack> b) {
                if (a == b) return true;
                if (a == null || b == null) return false;
                if (a.size() != b.size()) return false;
                for (int i = 0; i < a.size(); i++) {
                    if (!ItemStackIdentity.sameItemData(a.get(i), b.get(i), registryAccess)) return false;
                }
                return true;
            }
        };

        return new Object2IntLinkedOpenCustomHashMap<>(strategy);
    }

    private static Reference2IntOpenHashMap<Fluid> mergeFluids(ObjectList<FluidStack> fluids) {
        var merged = new Reference2IntOpenHashMap<Fluid>();
        for (var fluid : fluids) {
            var f = normalize(fluid.getFluid());
            if (f == EMPTY) continue;
            int amt = fluid.getAmount();
            if (amt > merged.getInt(f)) merged.put(f, amt);
        }
        return merged;
    }

    private static void addRemainingItem(ObjectArrayList<ItemStack> target, ItemStack source, int multiplier) {
        if (source != null && !source.isEmpty() && source.hasCraftingRemainingItem()) {
            var remaining = source.getCraftingRemainingItem();
            if (!remaining.isEmpty())
                target.add(ItemStackCanonicalizer.canonicalize(remaining.copyWithCount(remaining.getCount() * multiplier)));
        }
    }

    private static ItemStack declaredRecipeResult(Object root, Level level) {
        if (root instanceof Recipe<?> recipe && level != null) {
            try {
                var result = recipe.getResultItem(level.registryAccess());
                if (!result.isEmpty() && result.getItem() != AIR) return result.copy();
            } catch (Throwable ignored) {
            }

            var meta = RecipeMetadata.getMeta(recipe.getClass());
            for (var f : meta.allFields()) {
                if (f.getType() == ItemStack.class) try {
                    var fieldValue = (ItemStack) f.get(recipe);
                    if (fieldValue != null && !fieldValue.isEmpty() && fieldValue.getItem() != AIR) {
                        return fieldValue.copy();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack selectOutput(ItemStack declaredResult, ObjectList<ItemStack> stacks,
                                          HolderLookup.Provider provider) {
        if (!declaredResult.isEmpty()) {
            var best = declaredResult;
            for (var stack : stacks) {
                if (stack.isEmpty() || stack.getItem() == AIR || stack.getItem() != declaredResult.getItem()) continue;
                if (stack.getCount() > best.getCount()) {
                    best = stack;
                } else if (!ItemStackIdentity.hasStackData(best, provider) && ItemStackIdentity.hasStackData(stack, provider)) {
                    best = stack;
                }
            }
            return ItemStackCanonicalizer.canonicalize(best);
        }
        var best = ItemStack.EMPTY;
        int bestScore = Integer.MAX_VALUE;
        for (var stack : stacks) {
            if (stack.isEmpty() || stack.getItem() == AIR) continue;
            int score = stack.getCount() <= 0 ? Integer.MAX_VALUE : stack.getCount();
            if (best.isEmpty() || score < bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        return ItemStackCanonicalizer.canonicalize(best);
    }

    private static boolean sameStackIdentity(ItemStack a, ItemStack b, HolderLookup.Provider provider) {
        return ItemStackIdentity.sameItemDataAndCount(a, b, provider);
    }

    private static boolean isUniqueStackData(ObjectList<ItemStack> stacks, ItemStack candidate) {
        for (var stack : stacks) if (ItemStackIdentity.sameItemData(stack, candidate)) return false;
        return true;
    }

    private record ItemStackComparator(HolderLookup.Provider provider) implements Comparator<ItemStack> {

        @Override
        public int compare(ItemStack a, ItemStack b) {
            var idA = GameRegistryManager.getItemId(a.getItem());
            var idB = GameRegistryManager.getItemId(b.getItem());
            int byId = idA.compareTo(idB);
            if (byId != 0) return byId;
            return ItemStackIdentity.dataKey(a, provider).compareTo(ItemStackIdentity.dataKey(b, provider));
        }
    }
}