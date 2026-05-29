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

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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

    public HarvestedItems harvest(Object recipe, Level level) {
        if (recipe == null) return HarvestedItems.EMPTY;

        var inputItems = borrowList(TL_INPUT_ITEMS);
        var outputItems = borrowList(TL_OUTPUT_ITEMS);
        var inputIngredients = borrowList(TL_INPUT_INGREDIENTS);
        var inputFluids = borrowList(TL_INPUT_FLUIDS);
        var outputFluids = borrowList(TL_OUTPUT_FLUIDS);
        var visited = borrowMap();
        var transitional = TL_TRANSITIONAL_ITEMS.get();
        transitional.clear();

        try {
            ItemStack apiResult = ItemStack.EMPTY;
            boolean isVanillaRecipe = false;

            if (recipe instanceof Recipe<?> r) {
                var type = r.getType();
                if (type == RecipeType.CRAFTING || type == RecipeType.SMELTING || type == RecipeType.BLASTING
                        || type == RecipeType.SMOKING || type == RecipeType.CAMPFIRE_COOKING
                        || type == RecipeType.STONECUTTING || type == RecipeType.SMITHING) isVanillaRecipe = true;

                for (var ing : r.getIngredients())
                    if (ing != null && !ing.isEmpty())
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));

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
                        if (raw != null) collectItemsDeep(raw, inputItems, 0, visited);
                    } catch (Throwable ignored) {
                    }
                }
                for (var acc : accessors.ingredientAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw instanceof Ingredient ing && !ing.isEmpty())
                            inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                        else if (raw != null) collectIngredientsDeep(raw, inputIngredients, 0, visited);
                    } catch (Throwable ignored) {
                    }
                }
                for (var acc : accessors.fluidAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw == null) continue;

                        if (acc.name().contains("output")) {
                            collectFluidsDeep(raw, outputFluids, 0, new ReferenceOpenHashSet<>(64));
                        } else {
                            if (raw instanceof FluidStack fs && !fs.isEmpty()) inputFluids.add(fs.copy());
                            else collectFluidsDeep(raw, inputFluids, 0, visited);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                for (var acc : accessors.probeAccessors()) {
                    try {
                        var raw = acc.extract(recipe, level);
                        if (raw != null && !isEmptyContainer(raw)) {
                            String nameLower = acc.name().toLowerCase(java.util.Locale.ROOT);
                            if (nameLower.contains("output") || nameLower.contains("result")) {
                                var tempItems = new ObjectArrayList<ItemStack>(8);
                                collectItemsDeep(raw, tempItems, 0, new ReferenceOpenHashSet<>(64));
                                outputItems.addAll(tempItems);
                                collectFluidsDeep(raw, outputFluids, 0, new ReferenceOpenHashSet<>(64));
                            } else {
                                collectAllDeep(raw, inputItems, outputItems, inputIngredients, inputFluids, 0, visited, apiResult, level);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (inputItems.isEmpty() && outputItems.isEmpty() && inputIngredients.isEmpty() && inputFluids.isEmpty() && outputFluids.isEmpty()) {
                    visited.clear();
                    collectAllDeep(recipe, inputItems, outputItems, inputIngredients, inputFluids, 0, visited, apiResult, level);
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

            if (outputItems.isEmpty() && recipe instanceof Recipe<?> r) try {
                probeRecipeOutputs(r, level, outputItems);
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

            if (!inputIngredients.isEmpty()) inputItems.removeIf(stack -> {
                for (var hi : inputIngredients) {
                    for (ItemStack ingStack : hi.ingredient().getItems()) {
                        if (ingStack.getItem() == stack.getItem()) return true;
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

    private static void collectItemsDeep(Object obj, ObjectList<ItemStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                opt.ifPresent(o -> collectItemsDeep(o, acc, depth + 1, visited));
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                ItemStack[] stacks = si.ingredient().getItems();
                if (stacks.length > 0) {
                    ItemStack stack = stacks[0].copy();
                    stack.setCount(si.count());
                    acc.add(stack);
                }
                return;
            }
            case ItemStack stack when !stack.isEmpty() -> {
                acc.add(stack.copy());
                return;
            }
            case Iterable<?> coll when isTooSmall(coll) -> {
                for (var item : coll) collectItemsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when isTooSmall(map) -> {
                for (var e : map.entrySet()) {
                    collectItemsDeep(e.getKey(), acc, depth + 1, visited);
                    collectItemsDeep(e.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                for (var item : arr) collectItemsDeep(item, acc, depth + 1, visited);
                return;
            }
            default -> {
            }
        }
        if (obj instanceof FluidStack || obj instanceof Ingredient) return;
        if (isTerminal(obj)) return;
        if (!visited.add(obj)) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields) {
            try {
                collectItemsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectIngredientsDeep(Object obj, ObjectList<HarvestedItems.HarvestedIngredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                opt.ifPresent(o -> collectIngredientsDeep(o, acc, depth + 1, visited));
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                var ing = si.ingredient();
                if (!ing.isEmpty()) acc.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                return;
            }
            case Iterable<?> coll when isTooSmall(coll) -> {
                for (var item : coll) collectIngredientsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when isTooSmall(map) -> {
                for (var e : map.entrySet()) {
                    collectIngredientsDeep(e.getKey(), acc, depth + 1, visited);
                    collectIngredientsDeep(e.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                for (var item : arr) collectIngredientsDeep(item, acc, depth + 1, visited);
                return;
            }
            default -> {
            }
        }
        if (obj instanceof ItemStack || obj instanceof FluidStack) return;
        if (isTerminal(obj)) return;
        if (!visited.add(obj)) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields) {
            try {
                collectIngredientsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectFluidsDeep(Object obj, ObjectList<FluidStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                opt.ifPresent(o -> collectFluidsDeep(o, acc, depth + 1, visited));
                return;
            }
            case SizedFluidIngredient sfi -> {
                for (var fs : sfi.getFluids()) if (!fs.isEmpty()) acc.add(fs.copy());
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                acc.add(fs.copy());
                return;
            }
            case Iterable<?> coll when isTooSmall(coll) -> {
                for (var item : coll) collectFluidsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when isTooSmall(map) -> {
                for (var e : map.entrySet()) {
                    collectFluidsDeep(e.getKey(), acc, depth + 1, visited);
                    collectFluidsDeep(e.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                for (var item : arr) collectFluidsDeep(item, acc, depth + 1, visited);
                return;
            }
            default -> {
            }
        }
        if (obj instanceof ItemStack || obj instanceof Ingredient) return;
        if (isTerminal(obj)) return;
        if (!visited.add(obj)) return;

        var meta = RecipeReflection.getMeta(obj.getClass());
        if (depth <= 5) {
            for (int i = 0; i < meta.allMethods.length; i++) {
                var m = meta.allMethods[i];
                var h = meta.allHandles[i];
                if (h == null) continue;
                try {
                    Class<?> rt = m.getReturnType();
                    String rtName = rt.getName();

                    if (FluidStack.class.isAssignableFrom(rt) || rt.isArray() || Iterable.class.isAssignableFrom(rt)
                            || Stream.class.isAssignableFrom(rt) || rtName.contains("Fluid")) {

                        String mName = m.getName();
                        if (mName.equals("toString") || mName.equals("hashCode") || mName.equals("getClass")
                                || mName.equals("getFluid")) continue;

                        var val = h.invoke(obj);
                        if (val != null && val != obj) if (val instanceof Stream<?> stream) {
                            stream.limit(100).forEach(element -> collectFluidsDeep(element, acc, depth + 1, visited));
                        } else {
                            collectFluidsDeep(val, acc, depth + 1, visited);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        for (var f : meta.scanFields) {
            try {
                collectFluidsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectAllDeep(Object obj, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                       ObjectList<HarvestedItems.HarvestedIngredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                                       int depth, ReferenceOpenHashSet<Object> visited, ItemStack apiResult, Level level) {
        if (obj == null || depth > 8) return;

        if (depth > 0 && obj instanceof Recipe<?> subRecipe && level != null) {
            try {
                var subOutput = subRecipe.getResultItem(level.registryAccess());
                if (!subOutput.isEmpty() && !apiResult.isEmpty() && subOutput.getItem() != apiResult.getItem()) {
                    TL_TRANSITIONAL_ITEMS.get().add(subOutput.getItem());
                }

                var subIngs = subRecipe.getIngredients();
                if (subIngs.size() > 1) {
                    var toolIng = subIngs.get(1);
                    if (!toolIng.isEmpty()) if (visited.add(toolIng))
                        inputIngredients.add(new HarvestedItems.HarvestedIngredient(toolIng, 1));
                }

                collectFluidsDeep(subRecipe, inputFluids, 0, visited);
            } catch (Throwable ignored) {
            }
            return;
        }

        switch (obj) {
            case Optional<?> opt -> {
                opt.ifPresent(o -> collectAllDeep(o, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level));
                return;
            }
            case ItemStack stack when !stack.isEmpty() -> {
                if (!apiResult.isEmpty() && stack.getItem() == apiResult.getItem()) outputItems.add(stack.copy());
                else inputItems.add(stack.copy());
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                var ing = si.ingredient();
                if (!ing.isEmpty()) if (visited.add(ing))
                    inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                return;
            }
            case SizedFluidIngredient sfi -> {
                for (FluidStack fs : sfi.getFluids()) if (!fs.isEmpty()) inputFluids.add(fs);
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (visited.add(ing)) {
                    boolean alreadyExists = false;
                    ItemStack[] ingItems = ing.getItems();
                    for (var existingHi : inputIngredients) {
                        var existing = existingHi.ingredient();
                        ItemStack[] existingItems = existing.getItems();
                        if (ingItems.length == existingItems.length) {
                            boolean allMatch = true;
                            for (var stackA : ingItems) {
                                boolean found = false;
                                for (var stackB : existingItems) {
                                    if (stackA.getItem() == stackB.getItem()) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    allMatch = false;
                                    break;
                                }
                            }
                            if (allMatch) {
                                alreadyExists = true;
                                break;
                            }
                        }
                    }
                    if (!alreadyExists) inputIngredients.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                }
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                inputFluids.add(fs.copy());
                return;
            }
            case Iterable<?> coll when isTooSmall(coll) -> {
                for (var item : coll)
                    collectAllDeep(item, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level);
                return;
            }
            case Map<?, ?> map when isTooSmall(map) -> {
                for (var e : map.entrySet()) {
                    var key = e.getKey();
                    if (key != null && !isTerminal(key)) collectAllDeep(key, inputItems, outputItems, inputIngredients,
                            inputFluids, depth + 1, visited, apiResult, level);
                    collectAllDeep(e.getValue(), inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                for (var item : arr)
                    collectAllDeep(item, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level);
                return;
            }
            default -> {
            }
        }
        if (isTerminal(obj)) return;
        if (!visited.add(obj)) return;

        var meta = RecipeReflection.getMeta(obj.getClass());
        if (depth <= 5) {
            for (int i = 0; i < meta.allMethods.length; i++) {
                var m = meta.allMethods[i];
                var h = meta.allHandles[i];
                if (h == null) continue;
                try {
                    Class<?> rt = m.getReturnType();
                    String rtName = rt.getName();

                    if (ItemStack.class.isAssignableFrom(rt)
                            || Ingredient.class.isAssignableFrom(rt)
                            || FluidStack.class.isAssignableFrom(rt)
                            || rt.isArray()
                            || Iterable.class.isAssignableFrom(rt)
                            || Stream.class.isAssignableFrom(rt)
                            || rtName.contains("Fluid")
                            || rtName.contains("Ingredient")
                            || rtName.contains("Item")
                            || rtName.contains("Stack")) {

                        String mName = m.getName();
                        if (mName.equals("toString") || mName.equals("hashCode") || mName.equals("getClass")
                                || mName.equals("getFluid") || mName.equals("getItem")) continue;

                        var val = h.invoke(obj);
                        if (val != null && val != obj) if (val instanceof Stream<?> stream) {
                            stream.limit(100).forEach(element -> collectAllDeep(element, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level));
                        } else {
                            collectAllDeep(val, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        for (var f : meta.scanFields) {
            try {
                var val = f.get(obj);
                if (val != null) collectAllDeep(val, inputItems, outputItems, inputIngredients, inputFluids,
                        depth + 1, visited, apiResult, level);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isTooSmall(Object obj) {
        if (obj instanceof Collection<?> c) return c.size() <= 50;
        if (obj instanceof Map<?, ?> m) return m.size() <= 50;
        if (obj instanceof Object[] arr) return arr.length <= 50;
        return true;
    }

    private static boolean isEmptyContainer(Object obj) {
        if (obj instanceof Collection<?> c) return c.isEmpty();
        if (obj instanceof Map<?, ?> m) return m.isEmpty();
        if (obj instanceof Object[] arr) return arr.length == 0;
        return false;
    }

    private static boolean isTerminal(Object obj) {
        if (obj == null) return true;
        Class<?> c = obj.getClass();
        String name = c.getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("jdk.")) return true;
        return TerminalTypeRegistry.isTerminalType(c);
    }

    private static void clearThreadLocals() {
        TL_INPUT_ITEMS.get().clear();
        TL_OUTPUT_ITEMS.get().clear();
        TL_INPUT_FLUIDS.get().clear();
        TL_OUTPUT_FLUIDS.get().clear();
        TL_INPUT_INGREDIENTS.get().clear();
        TL_VISITED.get().clear();
        TL_TRANSITIONAL_ITEMS.get().clear();
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

    private void probeRecipeOutputs(Recipe<?> recipe, Level level, ObjectList<ItemStack> outputItems) {
        Method assembleMethod = null;
        for (Method m : recipe.getClass().getMethods()) {
            if (m.getName().equals("assemble") && m.getParameterCount() == 2) {
                assembleMethod = m;
                break;
            }
        }
        if (assembleMethod == null) return;

        Class<?> inputClass = assembleMethod.getParameterTypes()[0];
        List<List<ItemStack>> ingredientOptions = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == null || ing.isEmpty()) continue;
            List<ItemStack> options = new ArrayList<>();
            for (ItemStack stack : ing.getItems()) {
                if (stack != null && !stack.isEmpty()) {
                    options.add(stack);
                }
            }
            if (!options.isEmpty()) {
                ingredientOptions.add(options);
            }
        }

        if (ingredientOptions.isEmpty()) return;

        List<List<ItemStack>> combos = new ArrayList<>();
        try {
            generateCombinations(ingredientOptions, 0, new ArrayList<>(), combos, 256);
        } catch (Throwable ignored) {
            return;
        }

        for (List<ItemStack> combo : combos) {
            try {
                Object inputObj = createRecipeInput(inputClass, combo);
                if (inputObj != null) {
                    ItemStack output = (ItemStack) assembleMethod.invoke(recipe, inputObj, level.registryAccess());
                    if (output != null && !output.isEmpty()) {
                        boolean alreadyHas = false;
                        for (ItemStack existing : outputItems) {
                            if (existing.getItem() == output.getItem()) {
                                alreadyHas = true;
                                break;
                            }
                        }
                        if (!alreadyHas) {
                            outputItems.add(output.copy());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void generateCombinations(List<List<ItemStack>> options, int index, List<ItemStack> current, List<List<ItemStack>> result, int maxLimit) {
        if (result.size() >= maxLimit) return;
        if (index == options.size()) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (ItemStack stack : options.get(index)) {
            current.add(stack);
            generateCombinations(options, index + 1, current, result, maxLimit);
            current.removeLast();
            if (result.size() >= maxLimit) break;
        }
    }

    private Object createRecipeInput(Class<?> inputClass, List<ItemStack> items) {
        for (Method m : inputClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("of") && m.getReturnType() == inputClass) {
                try {
                    int paramCount = m.getParameterCount();
                    if (paramCount == 1 && m.getParameterTypes()[0].isAssignableFrom(ItemStack.class)) {
                        if (!items.isEmpty()) {
                            return m.invoke(null, items.getFirst());
                        }
                    } else if (paramCount == 3 && m.getParameterTypes()[0] == int.class && m.getParameterTypes()[1] == int.class && List.class.isAssignableFrom(m.getParameterTypes()[2])) {
                        int size = items.size();
                        int width = (int) Math.ceil(Math.sqrt(size));
                        if (width == 0) width = 1;
                        int height = width;
                        List<ItemStack> padded = new ArrayList<>(width * height);
                        for (int i = 0; i < width * height; i++) {
                            padded.add(i < items.size() ? items.get(i) : ItemStack.EMPTY);
                        }
                        return m.invoke(null, width, height, padded);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        for (var constr : inputClass.getDeclaredConstructors()) {
            try {
                constr.setAccessible(true);
                int paramCount = constr.getParameterCount();
                if (paramCount == 1 && constr.getParameterTypes()[0].isAssignableFrom(ItemStack.class)) {
                    if (!items.isEmpty()) {
                        return constr.newInstance(items.getFirst());
                    }
                } else if (paramCount == 3 && constr.getParameterTypes()[0] == int.class && constr.getParameterTypes()[1] == int.class && List.class.isAssignableFrom(constr.getParameterTypes()[2])) {
                    int size = items.size();
                    int width = (int) Math.ceil(Math.sqrt(size));
                    if (width == 0) width = 1;
                    int height = width;
                    List<ItemStack> padded = new ArrayList<>(width * height);
                    for (int i = 0; i < width * height; i++) {
                        padded.add(i < items.size() ? items.get(i) : ItemStack.EMPTY);
                    }
                    return constr.newInstance(width, height, padded);
                } else if (paramCount == 3 && constr.getParameterTypes()[0].isAssignableFrom(ItemStack.class) && constr.getParameterTypes()[1].isAssignableFrom(ItemStack.class) && constr.getParameterTypes()[2].isAssignableFrom(ItemStack.class)) {
                    ItemStack p0 = !items.isEmpty() ? items.get(0) : ItemStack.EMPTY;
                    ItemStack p1 = items.size() > 1 ? items.get(1) : ItemStack.EMPTY;
                    ItemStack p2 = items.size() > 2 ? items.get(2) : ItemStack.EMPTY;
                    return constr.newInstance(p0, p1, p2);
                } else if (paramCount == 1 && List.class.isAssignableFrom(constr.getParameterTypes()[0])) {
                    return constr.newInstance(items);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
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