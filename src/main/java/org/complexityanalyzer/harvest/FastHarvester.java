package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.*;

public final class FastHarvester {

    private static final Map<Object, ObjectList<ItemStack>> ITEM_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ObjectList<Ingredient>> INGREDIENT_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ObjectList<FluidStack>> FLUID_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final class UnifiedResult {
        final ObjectList<ItemStack> inputItems;
        final ObjectList<ItemStack> outputItems;
        final ObjectList<Ingredient> inputIngredients;
        final ObjectList<FluidStack> inputFluids;
        final ObjectList<FluidStack> outputFluids;

        UnifiedResult(ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                      ObjectList<Ingredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                      ObjectList<FluidStack> outputFluids) {
            this.inputItems = inputItems;
            this.outputItems = outputItems;
            this.inputIngredients = inputIngredients;
            this.inputFluids = inputFluids;
            this.outputFluids = outputFluids;
        }
    }

    private static final Map<Object, UnifiedResult> UNIFIED_INPUT_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, UnifiedResult> UNIFIED_OUTPUT_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());

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

    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_ITEMS =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_INGREDIENTS =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_FLUIDS =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_ALL =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));

    public HarvestedItems harvest(Object recipe, Level level) {
        if (recipe == null) return new HarvestedItems(
                ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), null
        );

        Class<?> clazz = recipe.getClass();
        String className = clazz.getName();
        if (className.startsWith("java.") || className.startsWith("net.minecraft.")
                || className.startsWith("it.unimi.") || className.startsWith("com.mojang.")) {
            var inputIngredients = ObjectLists.<Ingredient>emptyList();
            var outputItems = ObjectLists.<ItemStack>emptyList();
            if (recipe instanceof Recipe<?> r) {
                var ingList = new ObjectArrayList<Ingredient>();
                for (var ing : r.getIngredients()) if (ing != null && !ing.isEmpty()) ingList.add(ing);
                inputIngredients = ingList;
                try {
                    var res = r.getResultItem(level.registryAccess());
                    if (!res.isEmpty()) {
                        outputItems = new ObjectArrayList<>(1);
                        outputItems.add(res.copy());
                    }
                } catch (Throwable ignored) {
                }
            }
            return new HarvestedItems(
                    ObjectLists.emptyList(), outputItems,
                    inputIngredients, ObjectLists.emptyList(),
                    ObjectLists.emptyList(), recipe
            );
        }

        var inputItems = borrowList(TL_INPUT_ITEMS);
        var outputItems = borrowList(TL_OUTPUT_ITEMS);
        var inputIngredients = borrowList(TL_INPUT_INGREDIENTS);
        var inputFluids = borrowList(TL_INPUT_FLUIDS);
        var outputFluids = borrowList(TL_OUTPUT_FLUIDS);

        var visitedItems = borrowMap(TL_VISITED_ITEMS);
        var visitedIngredients = borrowMap(TL_VISITED_INGREDIENTS);
        var visitedFluids = borrowMap(TL_VISITED_FLUIDS);
        var visitedAll = borrowMap(TL_VISITED_ALL);

        try {
            var anchorStack = ItemStack.EMPTY;
            if (recipe instanceof Recipe<?> r) try {
                anchorStack = r.getResultItem(level.registryAccess());
            } catch (Throwable ignored) {
            }
            Item anchorItem = anchorStack.isEmpty() ? null : anchorStack.getItem();

            var accessors = RecipeReflection.getAccessors(clazz);

            var standardInputs = new HashSet<Ingredient>();
            if (recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) standardInputs.add(ing);
                }
            }

            for (var acc : accessors.itemAccessors()) {
                try {
                    var raw = acc.extract(recipe, level);
                    if (raw != null) {
                        SemanticRole role = classifyAccessor(acc, raw, anchorItem, visitedAll, standardInputs);
                        if (role == SemanticRole.OUTPUT) {
                            collectItemsDeep(raw, outputItems, 0, visitedItems);
                        } else {
                            collectItemsDeep(raw, inputItems, 0, visitedItems);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.ingredientAccessors()) {
                try {
                    var raw = acc.extract(recipe, level);
                    if (raw != null) collectIngredientsDeep(raw, inputIngredients, 0, visitedIngredients);
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.fluidAccessors()) {
                try {
                    var raw = acc.extract(recipe, level);
                    if (raw != null) {
                        SemanticRole role = classifyAccessor(acc, raw, anchorItem, visitedAll, standardInputs);
                        if (role == SemanticRole.OUTPUT) {
                            collectFluidsDeep(raw, outputFluids, 0, visitedFluids);
                        } else {
                            collectFluidsDeep(raw, inputFluids, 0, visitedFluids);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.probeAccessors()) {
                try {
                    var raw = acc.extract(recipe, level);
                    if (raw != null && !isEmptyContainer(raw)) {
                        SemanticRole role = classifyAccessor(acc, raw, anchorItem, visitedAll, standardInputs);
                        collectAllDeep(raw, inputItems, outputItems, inputIngredients, inputFluids, outputFluids, role, 0, visitedAll, standardInputs);
                    }
                } catch (Throwable ignored) {
                }
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
            inputItems.clear();
            outputItems.clear();
            inputFluids.clear();
            outputFluids.clear();
            inputIngredients.clear();
            visitedItems.clear();
            visitedIngredients.clear();
            visitedFluids.clear();
            visitedAll.clear();
        }
    }


    private static SemanticRole classifyAccessor(RecipeReflection.Accessor acc, Object raw, Item anchorItem, IdentityHashMap<Object, Boolean> visited, Set<Ingredient> standardInputs) {
        if (raw == null || isEmptyContainer(raw)) return SemanticRole.UNKNOWN;

        if (!standardInputs.isEmpty()) {
            visited.clear();
            if (containsAnyIngredient(raw, standardInputs, 0, visited)) {
                return SemanticRole.INPUT;
            }
        }

        if (anchorItem != null) {
            visited.clear();
            if (containsOutputAnchor(raw, anchorItem, 0, visited)) {
                return SemanticRole.OUTPUT;
            }
        }

        if (!standardInputs.isEmpty()) {
            visited.clear();
            if (containsAnyResource(raw, 0, visited)) {
                return SemanticRole.OUTPUT;
            }
        }

        String name = acc.name();
        if (name != null && !name.equals("unknown")) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("output") || lower.contains("result") || lower.contains("product")
                    || lower.contains("produce") || lower.contains("byproduct")
                    || lower.endsWith("out") || lower.startsWith("out")
                    || lower.contains("_out_") || lower.contains("_out")) {
                return SemanticRole.OUTPUT;
            }
            if (lower.contains("input") || lower.contains("ingr") || lower.contains("source")
                    || lower.contains("consume") || lower.contains("reactant")
                    || lower.endsWith("in") || startWithIn(lower)
                    || lower.contains("_in_") || lower.contains("_in")) {
                return SemanticRole.INPUT;
            }
        }

        return SemanticRole.UNKNOWN;
    }

    private static boolean startWithIn(String s) {
        return s.startsWith("in") && !s.startsWith("info") && !s.startsWith("index") && !s.startsWith("inventory");
    }

    private static boolean containsAnyIngredient(Object obj, Set<Ingredient> standardInputs, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return false;

        var detector = SizedIngredientDetector.getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                if (detector.returnsArray()) {
                    ItemStack[] stacks = (ItemStack[]) detector.ingredientExtractor().extract(obj);
                    if (stacks != null) {
                        for (var s : stacks) {
                            if (s != null && !s.isEmpty()) {
                                for (var ing : standardInputs) {
                                    if (ing.test(s)) return true;
                                }
                            }
                        }
                    }
                } else {
                    Ingredient ing = (Ingredient) detector.ingredientExtractor().extract(obj);
                    if (ing != null && standardInputs.contains(ing)) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        switch (obj) {
            case Ingredient ing -> {
                return standardInputs.contains(ing);
            }
            case ItemStack stack when !stack.isEmpty() -> {
                for (var ing : standardInputs) {
                    if (ing.test(stack)) return true;
                }
                return false;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return false;
                for (var item : coll) {
                    if (containsAnyIngredient(item, standardInputs, depth + 1, visited)) return true;
                }
                return false;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return false;
                for (var entry : map.entrySet()) {
                    if (containsAnyIngredient(entry.getValue(), standardInputs, depth + 1, visited)) return true;
                }
                return false;
            }
            case Object[] arr -> {
                if (arr.length > 50) return false;
                for (var item : arr) {
                    if (containsAnyIngredient(item, standardInputs, depth + 1, visited)) return true;
                }
                return false;
            }
            case FluidStack ignored -> {
                return false;
            }
            default -> {
            }
        }

        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return false;
        if (visited.put(obj, Boolean.TRUE) != null) return false;

        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                if (containsAnyIngredient(val, standardInputs, depth + 1, visited)) return true;
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    private static boolean containsAnyResource(Object obj, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return false;

        var detector = SizedIngredientDetector.getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) return true;

        switch (obj) {
            case ItemStack stack when !stack.isEmpty() -> {
                return true;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                return true;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                return true;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return false;
                for (var item : coll) {
                    if (containsAnyResource(item, depth + 1, visited)) return true;
                }
                return false;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return false;
                for (var entry : map.entrySet()) {
                    if (containsAnyResource(entry.getValue(), depth + 1, visited)) return true;
                }
                return false;
            }
            case Object[] arr -> {
                if (arr.length > 50) return false;
                for (var item : arr) {
                    if (containsAnyResource(item, depth + 1, visited)) return true;
                }
                return false;
            }
            default -> {
            }
        }

        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return false;
        if (visited.put(obj, Boolean.TRUE) != null) return false;

        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                if (containsAnyResource(val, depth + 1, visited)) return true;
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    private static boolean containsOutputAnchor(Object obj, Item anchorItem, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || anchorItem == null || depth > 5) return false;

        var detector = SizedIngredientDetector.getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                if (detector.returnsArray()) {
                    ItemStack[] stacks = (ItemStack[]) detector.ingredientExtractor().extract(obj);
                    if (stacks != null) {
                        for (var s : stacks) if (s != null && s.getItem() == anchorItem) return true;
                    }
                } else {
                    Ingredient ing = (Ingredient) detector.ingredientExtractor().extract(obj);
                    if (ing != null) {
                        for (var s : ing.getItems()) if (s != null && s.getItem() == anchorItem) return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        switch (obj) {
            case ItemStack stack when !stack.isEmpty() -> {
                return stack.getItem() == anchorItem;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return false;
                for (var item : coll) {
                    if (containsOutputAnchor(item, anchorItem, depth + 1, visited)) return true;
                }
                return false;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return false;
                for (var entry : map.entrySet()) {
                    if (containsOutputAnchor(entry.getValue(), anchorItem, depth + 1, visited)) return true;
                }
                return false;
            }
            case Object[] arr -> {
                if (arr.length > 50) return false;
                for (var item : arr) {
                    if (containsOutputAnchor(item, anchorItem, depth + 1, visited)) return true;
                }
                return false;
            }
            case Ingredient ing -> {
                for (var s : ing.getItems()) {
                    if (s != null && s.getItem() == anchorItem) return true;
                }
                return false;
            }
            case FluidStack ignored -> {
                return false;
            }
            default -> {
            }
        }

        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return false;
        if (visited.put(obj, Boolean.TRUE) != null) return false;

        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                if (containsOutputAnchor(val, anchorItem, depth + 1, visited)) return true;
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    private static boolean containsIngredient(Object obj, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return false;

        var detector = SizedIngredientDetector.getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) return true;

        switch (obj) {
            case Ingredient ing when !ing.isEmpty() -> {
                return true;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return false;
                for (var item : coll) {
                    if (containsIngredient(item, depth + 1, visited)) return true;
                }
                return false;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return false;
                for (var entry : map.entrySet()) {
                    if (containsIngredient(entry.getValue(), depth + 1, visited)) return true;
                }
                return false;
            }
            case Object[] arr -> {
                if (arr.length > 50) return false;
                for (var item : arr) {
                    if (containsIngredient(item, depth + 1, visited)) return true;
                }
                return false;
            }
            case ItemStack ignored -> {
                return false;
            }
            case FluidStack ignored -> {
                return false;
            }
            default -> {
            }
        }

        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return false;
        if (visited.put(obj, Boolean.TRUE) != null) return false;

        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                if (containsIngredient(val, depth + 1, visited)) return true;
            } catch (Throwable ignored) {
            }
        }

        return false;
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

    private static void collectItemsDeep(Object obj, ObjectList<ItemStack> acc, int depth,
                                         IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return;

        var detector = SizedIngredientDetector.getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                int count = (int) detector.countExtractor().extract(obj);
                if (detector.returnsArray()) {
                    ItemStack[] stacks = (ItemStack[]) detector.ingredientExtractor().extract(obj);
                    if (stacks != null) {
                        for (var stack : stacks) {
                            if (stack != null && !stack.isEmpty()) {
                                var copy = stack.copy();
                                copy.setCount(count);
                                acc.add(copy);
                            }
                        }
                    }
                } else {
                    Ingredient ing = (Ingredient) detector.ingredientExtractor().extract(obj);
                    if (ing != null) {
                        for (var stack : ing.getItems()) {
                            if (stack != null && !stack.isEmpty()) {
                                var copy = stack.copy();
                                copy.setCount(count);
                                acc.add(copy);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        switch (obj) {
            case ItemStack stack when !stack.isEmpty() -> {
                acc.add(stack);
                return;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return;
                for (var item : coll) collectItemsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return;
                for (var entry : map.entrySet()) {
                    var key = entry.getKey();
                    if (key != null && !StructuralTypeClassifier.isTerminalType(key.getClass())) {
                        collectItemsDeep(key, acc, depth + 1, visited);
                    }
                    collectItemsDeep(entry.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr -> {
                if (arr.length > 50) return;
                for (var item : arr) collectItemsDeep(item, acc, depth + 1, visited);
                return;
            }
            case FluidStack ignored -> {
                return;
            }
            case Ingredient ignored -> {
                return;
            }
            default -> {
            }
        }
        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return;

        var cachedItems = ITEM_SCAN_CACHE.get(obj);
        if (cachedItems != null) {
            acc.addAll(cachedItems);
            return;
        }

        if (visited.put(obj, Boolean.TRUE) != null) return;

        var meta = RecipeReflection.getMeta(obj.getClass());
        var localItems = new ObjectArrayList<ItemStack>();
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                if (val == null) continue;
                if (val instanceof ItemStack s && !s.isEmpty()) {
                    localItems.add(s);
                } else {
                    collectItemsDeep(val, localItems, depth + 1, visited);
                }
            } catch (Throwable ignored) {
            }
        }
        if (localItems.isEmpty()) probeTerminalMethodsForItems(obj, localItems, depth, visited);
        ITEM_SCAN_CACHE.put(obj, localItems);
        acc.addAll(localItems);
    }

    private static void collectIngredientsDeep(Object obj, ObjectList<Ingredient> acc, int depth,
                                               IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return;

        var detector = SizedIngredientDetector.getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                if (!detector.returnsArray()) {
                    Ingredient ing = (Ingredient) detector.ingredientExtractor().extract(obj);
                    if (ing != null && !ing.isEmpty()) acc.add(ing);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        switch (obj) {
            case Ingredient ing when !ing.isEmpty() -> {
                acc.add(ing);
                return;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return;
                for (var item : coll) collectIngredientsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return;
                for (var entry : map.entrySet()) {
                    var key = entry.getKey();
                    if (key != null && !StructuralTypeClassifier.isTerminalType(key.getClass())) {
                        collectIngredientsDeep(key, acc, depth + 1, visited);
                    }
                    collectIngredientsDeep(entry.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr -> {
                if (arr.length > 50) return;
                for (var item : arr) collectIngredientsDeep(item, acc, depth + 1, visited);
                return;
            }
            case ItemStack ignored -> {
                return;
            }
            case FluidStack ignored -> {
                return;
            }
            default -> {
            }
        }
        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return;

        var cachedIngs = INGREDIENT_SCAN_CACHE.get(obj);
        if (cachedIngs != null) {
            acc.addAll(cachedIngs);
            return;
        }

        if (visited.put(obj, Boolean.TRUE) != null) return;

        var meta = RecipeReflection.getMeta(obj.getClass());
        var localIngs = new ObjectArrayList<Ingredient>();
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                if (val == null) continue;
                if (val instanceof Ingredient ing && !ing.isEmpty()) {
                    localIngs.add(ing);
                } else {
                    collectIngredientsDeep(val, localIngs, depth + 1, visited);
                }
            } catch (Throwable ignored) {
            }
        }
        if (localIngs.isEmpty()) probeTerminalMethodsForIngredients(obj, localIngs, depth, visited);
        INGREDIENT_SCAN_CACHE.put(obj, localIngs);
        acc.addAll(localIngs);
    }

    private static void collectFluidsDeep(Object obj, ObjectList<FluidStack> acc, int depth,
                                          IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return;
        switch (obj) {
            case FluidStack fs when !fs.isEmpty() -> {
                acc.add(fs);
                return;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return;
                for (var item : coll) collectFluidsDeep(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return;
                for (var entry : map.entrySet()) {
                    var key = entry.getKey();
                    if (key != null && !StructuralTypeClassifier.isTerminalType(key.getClass())) {
                        collectFluidsDeep(key, acc, depth + 1, visited);
                    }
                    collectFluidsDeep(entry.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr -> {
                if (arr.length > 50) return;
                for (var item : arr) collectFluidsDeep(item, acc, depth + 1, visited);
                return;
            }
            case ItemStack ignored -> {
                return;
            }
            case Ingredient ignored -> {
                return;
            }
            default -> {
            }
        }
        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return;

        var cachedFluids = FLUID_SCAN_CACHE.get(obj);
        if (cachedFluids != null) {
            acc.addAll(cachedFluids);
            return;
        }

        if (visited.put(obj, Boolean.TRUE) != null) return;

        var meta = RecipeReflection.getMeta(obj.getClass());
        var localFluids = new ObjectArrayList<FluidStack>();
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                if (val == null) continue;
                if (val instanceof FluidStack fs && !fs.isEmpty()) {
                    localFluids.add(fs);
                } else {
                    collectFluidsDeep(val, localFluids, depth + 1, visited);
                }
            } catch (Throwable ignored) {
            }
        }
        if (localFluids.isEmpty()) probeTerminalMethodsForFluids(obj, localFluids, depth, visited);
        FLUID_SCAN_CACHE.put(obj, localFluids);
        acc.addAll(localFluids);
    }

    private static void collectAllDeep(Object obj, ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                       ObjectList<Ingredient> inputIngredients, ObjectList<FluidStack> inputFluids,
                                       ObjectList<FluidStack> outputFluids, SemanticRole role, int depth,
                                       IdentityHashMap<Object, Boolean> visited, Set<Ingredient> standardInputs) {
        if (obj == null || depth > 5) return;

        var detector = SizedIngredientDetector.getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                int count = (int) detector.countExtractor().extract(obj);
                if (detector.returnsArray()) {
                    ItemStack[] stacks = (ItemStack[]) detector.ingredientExtractor().extract(obj);
                    if (stacks != null) for (var stack : stacks) {
                        if (stack != null && !stack.isEmpty()) {
                            var copy = stack.copy();
                            copy.setCount(count);
                            inputItems.add(copy);
                        }
                    }
                } else {
                    Ingredient innerIng = (Ingredient) detector.ingredientExtractor().extract(obj);
                    if (innerIng != null) if (standardInputs.contains(innerIng)) {
                        inputIngredients.add(innerIng);
                    } else {
                        for (var stack : innerIng.getItems()) {
                            if (stack != null && !stack.isEmpty()) {
                                var copy = stack.copy();
                                copy.setCount(count);
                                inputItems.add(copy);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        switch (obj) {
            case ItemStack stack when !stack.isEmpty() -> {
                if (role == SemanticRole.OUTPUT) {
                    outputItems.add(stack);
                } else {
                    inputItems.add(stack);
                }
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (standardInputs.contains(ing)) {
                    inputIngredients.add(ing);
                } else {
                    for (var stack : ing.getItems()) if (stack != null && !stack.isEmpty()) inputItems.add(stack);
                }
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                if (role == SemanticRole.OUTPUT) {
                    outputFluids.add(fs);
                } else {
                    inputFluids.add(fs);
                }
                return;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return;
                for (var item : coll)
                    collectAllDeep(item, inputItems, outputItems, inputIngredients, inputFluids,
                            outputFluids, role, depth + 1, visited, standardInputs);
                return;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return;
                for (var entry : map.entrySet()) {
                    var key = entry.getKey();
                    if (key != null && !StructuralTypeClassifier.isTerminalType(key.getClass())) {
                        collectAllDeep(key, inputItems, outputItems, inputIngredients, inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                    }
                    collectAllDeep(entry.getValue(), inputItems, outputItems, inputIngredients, inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                }
                return;
            }
            case Object[] arr -> {
                if (arr.length > 50) return;
                for (var item : arr)
                    collectAllDeep(item, inputItems, outputItems, inputIngredients, inputFluids,
                            outputFluids, role, depth + 1, visited, standardInputs);
                return;
            }
            default -> {
            }
        }
        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return;

        var cache = (role == SemanticRole.OUTPUT) ? UNIFIED_OUTPUT_SCAN_CACHE : UNIFIED_INPUT_SCAN_CACHE;
        var cached = cache.get(obj);
        if (cached != null) {
            inputItems.addAll(cached.inputItems);
            outputItems.addAll(cached.outputItems);
            inputIngredients.addAll(cached.inputIngredients);
            inputFluids.addAll(cached.inputFluids);
            outputFluids.addAll(cached.outputFluids);
            return;
        }

        if (visited.put(obj, Boolean.TRUE) != null) return;

        var meta = RecipeReflection.getMeta(obj.getClass());
        var localInputItems = new ObjectArrayList<ItemStack>();
        var localOutputItems = new ObjectArrayList<ItemStack>();
        var localInputIngs = new ObjectArrayList<Ingredient>();
        var localInputFluids = new ObjectArrayList<FluidStack>();
        var localOutputFluids = new ObjectArrayList<FluidStack>();
        for (int i = 0; i < meta.scanFields.length; i++) {
            try {
                var val = meta.scanFields[i].get(obj);
                switch (val) {
                    case null -> {
                    }
                    case ItemStack s when !s.isEmpty() -> {
                        if (role == SemanticRole.OUTPUT) localOutputItems.add(s);
                        else localInputItems.add(s);
                    }
                    case Ingredient ing when !ing.isEmpty() -> {
                        if (standardInputs.contains(ing)) {
                            localInputIngs.add(ing);
                        } else {
                            for (var stack : ing.getItems())
                                if (stack != null && !stack.isEmpty()) localInputItems.add(stack);
                        }
                    }
                    case FluidStack fs when !fs.isEmpty() -> {
                        if (role == SemanticRole.OUTPUT) localOutputFluids.add(fs);
                        else localInputFluids.add(fs);
                    }
                    default ->
                            collectAllDeep(val, localInputItems, localOutputItems, localInputIngs, localInputFluids, localOutputFluids, role, depth + 1, visited, standardInputs);
                }
            } catch (Throwable ignored) {
            }
        }
        if (localInputItems.isEmpty() && localOutputItems.isEmpty() && localInputIngs.isEmpty() && localInputFluids.isEmpty() && localOutputFluids.isEmpty()) {
            probeTerminalMethodsAll(obj, localInputItems, localOutputItems, localInputIngs, localInputFluids, localOutputFluids, role, depth, visited, standardInputs);
        }
        var result = new UnifiedResult(localInputItems, localOutputItems, localInputIngs, localInputFluids, localOutputFluids);
        cache.put(obj, result);
        inputItems.addAll(localInputItems);
        outputItems.addAll(localOutputItems);
        inputIngredients.addAll(localInputIngs);
        inputFluids.addAll(localInputFluids);
        outputFluids.addAll(localOutputFluids);
    }

    private static void probeTerminalMethodsForItems(Object obj, ObjectList<ItemStack> acc, int depth,
                                                     IdentityHashMap<Object, Boolean> visited) {
        if (depth > 4) return;
        String clsName = obj.getClass().getName();
        if (clsName.startsWith("java.") || clsName.startsWith("net.minecraft.")
                || clsName.startsWith("it.unimi.") || clsName.startsWith("com.mojang.")) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != ItemStack.class && isNotContainerReturnType(rt)) continue;
            var h = meta.allHandles[i];
            if (h == null) continue;
            try {
                var result = h.invoke(obj);
                if (result != null && !isTooLarge(result)) collectItemsDeep(result, acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void probeTerminalMethodsForIngredients(Object obj, ObjectList<Ingredient> acc, int depth,
                                                           IdentityHashMap<Object, Boolean> visited) {
        if (depth > 4) return;
        String clsName = obj.getClass().getName();
        if (clsName.startsWith("java.") || clsName.startsWith("net.minecraft.")
                || clsName.startsWith("it.unimi.") || clsName.startsWith("com.mojang.")) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != Ingredient.class && isNotContainerReturnType(rt)) continue;
            var h = meta.allHandles[i];
            if (h == null) continue;
            try {
                var result = h.invoke(obj);
                if (result != null && !isTooLarge(result)) collectIngredientsDeep(result, acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void probeTerminalMethodsForFluids(Object obj, ObjectList<FluidStack> acc, int depth,
                                                      IdentityHashMap<Object, Boolean> visited) {
        if (depth > 4) return;
        String clsName = obj.getClass().getName();
        if (clsName.startsWith("java.") || clsName.startsWith("net.minecraft.")
                || clsName.startsWith("it.unimi.") || clsName.startsWith("com.mojang.")) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != FluidStack.class && isNotContainerReturnType(rt)) continue;
            var h = meta.allHandles[i];
            if (h == null) continue;
            try {
                var result = h.invoke(obj);
                if (result != null && !isTooLarge(result)) collectFluidsDeep(result, acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void probeTerminalMethodsAll(Object obj,
                                                ObjectList<ItemStack> inputItems,
                                                ObjectList<ItemStack> outputItems,
                                                ObjectList<Ingredient> inputIngredients,
                                                ObjectList<FluidStack> inputFluids,
                                                ObjectList<FluidStack> outputFluids,
                                                SemanticRole role,
                                                int depth,
                                                IdentityHashMap<Object, Boolean> visited,
                                                Set<Ingredient> standardInputs) {
        if (depth > 4) return;
        String clsName = obj.getClass().getName();
        if (clsName.startsWith("java.") || clsName.startsWith("net.minecraft.")
                || clsName.startsWith("it.unimi.") || clsName.startsWith("com.mojang.")) return;
        var meta = RecipeReflection.getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != ItemStack.class && rt != Ingredient.class && rt != FluidStack.class && isNotContainerReturnType(rt)) continue;
            var h = meta.allHandles[i];
            if (h == null) continue;
            try {
                var result = h.invoke(obj);
                if (result != null && !isTooLarge(result)) {
                    collectAllDeep(result, inputItems, outputItems, inputIngredients, inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isNotContainerReturnType(Class<?> type) {
        if (type == null || type == void.class) return true;
        return !type.isArray() && !Iterable.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type);
    }

    private static IdentityHashMap<Object, Boolean> borrowMap(ThreadLocal<IdentityHashMap<Object, Boolean>> tl) {
        var map = tl.get();
        map.clear();
        return map;
    }

    private static <T> ObjectArrayList<T> borrowList(ThreadLocal<ObjectArrayList<T>> tl) {
        var list = tl.get();
        list.clear();
        return list;
    }

    public void clearCaches() {
        ITEM_SCAN_CACHE.clear();
        INGREDIENT_SCAN_CACHE.clear();
        FLUID_SCAN_CACHE.clear();
        UNIFIED_INPUT_SCAN_CACHE.clear();
        UNIFIED_OUTPUT_SCAN_CACHE.clear();
        RecipeReflection.clearCaches();
        SizedIngredientDetector.clearCache();
    }
}