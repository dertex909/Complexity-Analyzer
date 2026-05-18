package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.*;

public final class FastHarvester {

    private static final Map<Object, ObjectList<ItemStack>> ITEM_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ObjectList<Ingredient>> INGREDIENT_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ObjectList<FluidStack>> FLUID_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final ThreadLocal<ObjectArrayList<ItemStack>> TL_INPUT_ITEMS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(32));
    private static final ThreadLocal<ObjectArrayList<ItemStack>> TL_OUTPUT_ITEMS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<FluidStack>> TL_INPUT_FLUIDS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<FluidStack>> TL_OUTPUT_FLUIDS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<Ingredient>> TL_INPUT_INGREDIENTS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));

    public HarvestedItems harvest(Object recipe, Level level) {
        if (recipe == null) return new HarvestedItems(
                ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), null
        );

        var inputItems = borrowList(TL_INPUT_ITEMS);
        var outputItems = borrowList(TL_OUTPUT_ITEMS);
        var inputIngredients = borrowList(TL_INPUT_INGREDIENTS);
        var inputFluids = borrowList(TL_INPUT_FLUIDS);
        var outputFluids = borrowList(TL_OUTPUT_FLUIDS);
        var visited = borrowMap();

        try {
            // 1. Стандартный Recipe API
            ItemStack apiResult = ItemStack.EMPTY;
            if (recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients())
                    if (ing != null && !ing.isEmpty()) {
                        inputIngredients.add(ing);
                    }
                try {
                    apiResult = r.getResultItem(level.registryAccess());
                    if (!apiResult.isEmpty()) outputItems.add(apiResult.copy());
                } catch (Throwable ignored) {
                }
            }

            // 2. RecipeReflection accessors
            var accessors = RecipeReflection.getAccessors(recipe.getClass());
            for (var acc : accessors.itemAccessors()) {
                try {
                    Object raw = acc.extract(recipe, level);
                    if (raw != null) collectItemsDeep(raw, inputItems, 0, visited);
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.ingredientAccessors()) {
                try {
                    Object raw = acc.extract(recipe, level);
                    if (raw instanceof Ingredient ing && !ing.isEmpty()) inputIngredients.add(ing);
                    else if (raw != null) collectIngredientsDeep(raw, inputIngredients, 0, visited);
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.fluidAccessors()) {
                try {
                    Object raw = acc.extract(recipe, level);
                    if (raw instanceof FluidStack fs && !fs.isEmpty()) inputFluids.add(fs);
                    else if (raw != null) collectFluidsDeep(raw, inputFluids, 0, visited);
                } catch (Throwable ignored) {
                }
            }
            // Route items from "output" accessors (field:outputs, method:getOutputContents, etc.)
            // directly to outputItems. All others → inputItems/inputIngredients as before.
            // "input" and "output" are universal recipe concepts, not mod-specific names.
            for (var acc : accessors.probeAccessors()) {
                try {
                    Object raw = acc.extract(recipe, level);
                    if (raw != null && !isEmptyContainer(raw)) {
                        if (acc.name().contains("output")) {
                            var tempItems = new ObjectArrayList<ItemStack>(8);
                            collectItemsDeep(raw, tempItems, 0, visited);
                            outputItems.addAll(tempItems);
                            collectIngredientsDeep(raw, inputIngredients, 0, visited);
                            collectFluidsDeep(raw, outputFluids, 0, visited);
                        } else {
                            collectAllDeep(raw, inputItems, outputItems, inputIngredients,
                                    inputFluids, 0, visited, apiResult);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (inputItems.isEmpty() && outputItems.isEmpty()) {
                visited.clear();
                collectAllDeep(recipe, inputItems, outputItems, inputIngredients,
                        inputFluids, 0, visited, apiResult);
            }

            if (inputIngredients.isEmpty() && inputItems.isEmpty() && recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients()) if (ing != null && !ing.isEmpty()) inputIngredients.add(ing);
            }
            if (outputItems.isEmpty() && recipe instanceof Recipe<?> r) try {
                var res = r.getResultItem(level.registryAccess());
                if (!res.isEmpty()) outputItems.add(res.copy());
            } catch (Throwable ignored) {
            }

            return new HarvestedItems(
                    inputItems.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputItems),
                    outputItems.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(outputItems),
                    inputIngredients.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputIngredients),
                    inputFluids.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(inputFluids),
                    outputFluids.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(outputFluids),
                    recipe
            );
        } finally {
            clearThreadLocals();
        }
    }

    private static void collectItemsDeep(Object obj, ObjectList<ItemStack> acc, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return;
        switch (obj) {
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
                acc.add(stack);
                return;
            }
            case Iterable<?> coll when !isTooLarge(coll) -> {
                for (var item : coll) collectItemsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when !isTooLarge(map) -> {
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
        if (visited.put(obj, Boolean.TRUE) != null) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields)
            try {
                collectItemsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
    }

    private static void collectIngredientsDeep(Object obj, ObjectList<Ingredient> acc, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return;
        switch (obj) {
            case Ingredient ing when !ing.isEmpty() -> {
                acc.add(ing);
                return;
            }
            case Iterable<?> coll when !isTooLarge(coll) -> {
                for (var item : coll) collectIngredientsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when !isTooLarge(map) -> {
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
        if (visited.put(obj, Boolean.TRUE) != null) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields)
            try {
                collectIngredientsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
    }

    private static void collectFluidsDeep(Object obj, ObjectList<FluidStack> acc, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return;
        switch (obj) {
            case SizedFluidIngredient sfi -> {
                for (FluidStack fs : sfi.getFluids()) {
                    if (!fs.isEmpty()) acc.add(fs);
                }
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                acc.add(fs);
                return;
            }
            case Iterable<?> coll when !isTooLarge(coll) -> {
                for (var item : coll) collectFluidsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when !isTooLarge(map) -> {
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
        if (visited.put(obj, Boolean.TRUE) != null) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields)
            try {
                collectFluidsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
    }

    private static void collectAllDeep(Object obj, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                       ObjectList<Ingredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                                       int depth,
                                       IdentityHashMap<Object, Boolean> visited,
                                       ItemStack apiResult) {
        if (obj == null || depth > 5) return;
        switch (obj) {
            case ItemStack stack when !stack.isEmpty() -> {
                if (!apiResult.isEmpty() && stack.getItem() == apiResult.getItem()) outputItems.add(stack);
                else inputItems.add(stack);
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                ItemStack[] stacks = si.ingredient().getItems();
                if (stacks.length > 0) {
                    ItemStack stack = stacks[0].copy();
                    stack.setCount(si.count());
                    if (!apiResult.isEmpty() && stack.getItem() == apiResult.getItem()) outputItems.add(stack);
                    else inputItems.add(stack);
                }
                return;
            }
            case SizedFluidIngredient sfi -> {
                for (FluidStack fs : sfi.getFluids()) {
                    if (!fs.isEmpty()) inputFluids.add(fs);
                }
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                inputIngredients.add(ing);
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                inputFluids.add(fs);
                return;
            }
            case Iterable<?> coll when !isTooLarge(coll) -> {
                for (var item : coll)
                    collectAllDeep(item, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult);
                return;
            }
            case Map<?, ?> map when !isTooLarge(map) -> {
                for (var e : map.entrySet()) {
                    Object key = e.getKey();
                    if (key != null && !isTerminal(key))
                        collectAllDeep(key, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult);
                    collectAllDeep(e.getValue(), inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                for (var item : arr)
                    collectAllDeep(item, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult);
                return;
            }
            default -> {
            }
        }
        if (isTerminal(obj)) return;
        if (visited.put(obj, Boolean.TRUE) != null) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (var f : meta.scanFields) {
            try {
                Object val = f.get(obj);
                if (val != null)
                    collectAllDeep(val, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isTooLarge(Object obj) {
        if (obj instanceof Collection<?> c) return c.size() > 50;
        if (obj instanceof Map<?, ?> m) return m.size() > 50;
        if (obj instanceof Object[] arr) return arr.length > 50;
        return false;
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
        return c.isPrimitive() || c == String.class || c.isEnum() || Number.class.isAssignableFrom(c) || c == Boolean.class || c == Character.class;
    }

    private static void clearThreadLocals() {
        TL_INPUT_ITEMS.get().clear();
        TL_OUTPUT_ITEMS.get().clear();
        TL_INPUT_FLUIDS.get().clear();
        TL_OUTPUT_FLUIDS.get().clear();
        TL_INPUT_INGREDIENTS.get().clear();
        TL_VISITED.get().clear();
    }

    private static <T> ObjectArrayList<T> borrowList(ThreadLocal<ObjectArrayList<T>> tl) {
        var l = tl.get();
        l.clear();
        return l;
    }

    private static IdentityHashMap<Object, Boolean> borrowMap() {
        var m = FastHarvester.TL_VISITED.get();
        m.clear();
        return m;
    }

    public void clearCaches() {
        ITEM_SCAN_CACHE.clear();
        INGREDIENT_SCAN_CACHE.clear();
        FLUID_SCAN_CACHE.clear();
        RecipeReflection.clearCaches();
        SizedIngredientDetector.clearCache();
    }
}