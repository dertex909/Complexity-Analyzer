package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.Collection;
import java.util.Map;

public final class FastHarvester {

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
                            collectItemsDeep(raw, tempItems, 0, new ReferenceOpenHashSet<>(64));
                            outputItems.addAll(tempItems);
                            collectFluidsDeep(raw, outputFluids, 0, new ReferenceOpenHashSet<>(64));
                        } else {
                            collectAllDeep(raw, inputItems, outputItems, inputIngredients,
                                    inputFluids, 0, visited, apiResult, level);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (inputItems.isEmpty() && outputItems.isEmpty()) {
                visited.clear();
                collectAllDeep(recipe, inputItems, outputItems, inputIngredients,
                        inputFluids, 0, visited, apiResult, level);
            }

            if (inputIngredients.isEmpty() && inputItems.isEmpty() && recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients()) if (ing != null && !ing.isEmpty()) inputIngredients.add(ing);
            }
            if (outputItems.isEmpty() && recipe instanceof Recipe<?> r) try {
                var res = r.getResultItem(level.registryAccess());
                if (!res.isEmpty()) outputItems.add(res.copy());
            } catch (Throwable ignored) {
            }

            // 3. Filter out transitional/incomplete items collected from nested recipes
            if (!transitional.isEmpty()) {
                inputItems.removeIf(stack -> transitional.contains(stack.getItem()));
                inputIngredients.removeIf(ing -> {
                    ItemStack[] items = ing.getItems();
                    if (items.length == 0) return false;
                    for (ItemStack s : items) {
                        if (!transitional.contains(s.getItem())) return false;
                    }
                    return true;
                });
            }

            // 4. Deduplicate inputItems against inputIngredients
            if (!inputIngredients.isEmpty()) {
                inputItems.removeIf(stack -> {
                    for (Ingredient ing : inputIngredients) {
                        for (ItemStack ingStack : ing.getItems()) {
                            if (ingStack.getItem() == stack.getItem()) return true;
                        }
                    }
                    return false;
                });
            }

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
        for (var f : meta.scanFields)
            try {
                collectItemsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
    }

    private static void collectIngredientsDeep(Object obj, ObjectList<Ingredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Ingredient ing when !ing.isEmpty() -> {
                acc.add(ing);
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
        for (var f : meta.scanFields)
            try {
                collectIngredientsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
    }

    private static void collectFluidsDeep(Object obj, ObjectList<FluidStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case SizedFluidIngredient sfi -> {
                for (FluidStack fs : sfi.getFluids()) if (!fs.isEmpty()) acc.add(fs);
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                acc.add(fs);
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
        for (var f : meta.scanFields)
            try {
                collectFluidsDeep(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
    }

    private static void collectAllDeep(Object obj, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                       ObjectList<Ingredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                                       int depth, ReferenceOpenHashSet<Object> visited, ItemStack apiResult, Level level) {
        if (obj == null || depth > 8) return;

        if (depth > 0 && obj instanceof Recipe<?> subRecipe && level != null) {
            try {
                ItemStack subOutput = subRecipe.getResultItem(level.registryAccess());
                if (!subOutput.isEmpty() && !apiResult.isEmpty() && subOutput.getItem() != apiResult.getItem()) {
                    TL_TRANSITIONAL_ITEMS.get().add(subOutput.getItem());
                }

                var subIngs = subRecipe.getIngredients();
                if (subIngs.size() > 1) {
                    Ingredient toolIng = subIngs.get(1);
                    if (!toolIng.isEmpty()) if (visited.add(toolIng)) {
                        inputIngredients.add(toolIng);
                    }
                }

                collectFluidsDeep(subRecipe, inputFluids, 0, visited);
            } catch (Throwable ignored) {
            }
            return;
        }

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
                for (FluidStack fs : sfi.getFluids()) if (!fs.isEmpty()) inputFluids.add(fs);
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (visited.add(ing)) {
                    boolean alreadyExists = false;
                    ItemStack[] ingItems = ing.getItems();
                    for (Ingredient existing : inputIngredients) {
                        ItemStack[] existingItems = existing.getItems();
                        if (ingItems.length == existingItems.length) {
                            boolean allMatch = true;
                            for (ItemStack stackA : ingItems) {
                                boolean found = false;
                                for (ItemStack stackB : existingItems) {
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
                    if (!alreadyExists) {
                        inputIngredients.add(ing);
                    }
                }
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                inputFluids.add(fs);
                return;
            }
            case Iterable<?> coll when isTooSmall(coll) -> {
                for (var item : coll)
                    collectAllDeep(item, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level);
                return;
            }
            case Map<?, ?> map when isTooSmall(map) -> {
                for (var e : map.entrySet()) {
                    Object key = e.getKey();
                    if (key != null && !isTerminal(key))
                        collectAllDeep(key, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level);
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
        for (var f : meta.scanFields) {
            try {
                Object val = f.get(obj);
                if (val != null)
                    collectAllDeep(val, inputItems, outputItems, inputIngredients, inputFluids, depth + 1, visited, apiResult, level);
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
        return c.isPrimitive() || c == String.class || c.isEnum() || Number.class.isAssignableFrom(c) || c == Boolean.class || c == Character.class;
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

    public void clearCaches() {
        RecipeReflection.clearCaches();
        SizedIngredientDetector.clearCache();
        AntivirusStyleDetector.clearCache();
        PatternSignatureEngine.clearCache();
        UniversalTypeResolver.clearCache();
        UniversalAccessorResolver.clearCache();
    }
}