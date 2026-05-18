package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FastHarvester {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ConcurrentHashMap<Class<?>, ClassMeta> META_CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<Class<?>, ResolvedAccessors> ACCESSOR_CACHE = new ConcurrentHashMap<>(256);

    private static final Map<Object, ObjectList<ItemStack>> ITEM_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ObjectList<Ingredient>> INGREDIENT_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, ObjectList<FluidStack>> FLUID_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object, UnifiedResult> UNIFIED_SCAN_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final class UnifiedResult {
        ObjectList<ItemStack> items = new ObjectArrayList<>();
        ObjectList<Ingredient> ingredients = new ObjectArrayList<>();
        ObjectList<FluidStack> fluids = new ObjectArrayList<>();
    }

    private static final ThreadLocal<ObjectArrayList<ItemStack>> TL_ITEMS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(32));
    private static final ThreadLocal<ObjectArrayList<FluidStack>> TL_FLUIDS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<Ingredient>> TL_INGREDIENTS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_ITEMS =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_INGREDIENTS =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_FLUIDS =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> TL_VISITED_ALL =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(128));

    // Dynamic structural sized ingredient wrapper cache
    private static final ConcurrentHashMap<Class<?>, SizedDetector> STRUCTURAL_SIZED_CACHE = new ConcurrentHashMap<>(256);

    private record SizedDetector(
            boolean isSizedWrapper,
            MethodHandle ingredientExtractor,
            MethodHandle countExtractor,
            boolean returnsArray
    ) {
    }

    private static SizedDetector getSizedDetector(Class<?> clazz) {
        return STRUCTURAL_SIZED_CACHE.computeIfAbsent(clazz, c -> {
            String className = c.getName();
            if (className.startsWith("java.") || className.startsWith("net.minecraft.")
                    || className.startsWith("it.unimi.") || className.startsWith("com.mojang.")) {
                return new SizedDetector(false, null, null, false);
            }

            MethodHandle ingredientExtractor = null;
            MethodHandle countExtractor = null;
            boolean returnsArray = false;

            var meta = getMeta(c);

            // 1. Search for ingredient/items provider
            // Check methods first
            for (int i = 0; i < meta.allMethods.length; i++) {
                var m = meta.allMethods[i];
                if (m.getParameterCount() != 0) continue;
                var rt = m.getReturnType();
                if (rt == Ingredient.class) {
                    ingredientExtractor = meta.allHandles[i];
                    break;
                } else if (rt == ItemStack[].class) {
                    ingredientExtractor = meta.allHandles[i];
                    returnsArray = true;
                    break;
                }
            }

            // Check fields if not found in methods
            if (ingredientExtractor == null) {
                for (int i = 0; i < meta.fields.length; i++) {
                    var f = meta.fields[i];
                    var type = f.getType();
                    if (type == Ingredient.class) {
                        try {
                            ingredientExtractor = LOOKUP.unreflectGetter(f);
                        } catch (Exception ignored) {
                        }
                        break;
                    } else if (type == ItemStack[].class) {
                        try {
                            ingredientExtractor = LOOKUP.unreflectGetter(f);
                            returnsArray = true;
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                }
            }

            if (ingredientExtractor == null) {
                return new SizedDetector(false, null, null, false);
            }

            // 2. Search for count multiplier provider
            Set<String> countNames = Set.of("count", "amount", "size", "quantity", "qty");

            // Check methods first
            for (int i = 0; i < meta.allMethods.length; i++) {
                var m = meta.allMethods[i];
                if (m.getParameterCount() != 0) continue;
                var rt = m.getReturnType();
                if (rt == int.class || rt == Integer.class) {
                    if (countNames.contains(m.getName().toLowerCase())) {
                        countExtractor = meta.allHandles[i];
                        break;
                    }
                }
            }

            // Check fields if not found in methods
            if (countExtractor == null) {
                for (int i = 0; i < meta.fields.length; i++) {
                    var f = meta.fields[i];
                    var type = f.getType();
                    if (type == int.class || type == Integer.class) {
                        if (countNames.contains(f.getName().toLowerCase())) {
                            try {
                                countExtractor = LOOKUP.unreflectGetter(f);
                            } catch (Exception ignored) {
                            }
                            break;
                        }
                    }
                }
            }

            // Fallback: if no specific named count field is found, but there is exactly one integer field, use it!
            if (countExtractor == null) {
                Field singleIntField = null;
                int intFieldCount = 0;
                for (int i = 0; i < meta.fields.length; i++) {
                    var f = meta.fields[i];
                    var type = f.getType();
                    if (type == int.class || type == Integer.class) {
                        singleIntField = f;
                        intFieldCount++;
                    }
                }
                if (intFieldCount == 1) {
                    try {
                        countExtractor = LOOKUP.unreflectGetter(singleIntField);
                    } catch (Exception ignored) {
                    }
                }
            }

            if (countExtractor != null) {
                return new SizedDetector(true, ingredientExtractor, countExtractor, returnsArray);
            }

            return new SizedDetector(false, null, null, false);
        });
    }

    public HarvestedItems harvest(Object recipe, Level level) {
        if (recipe == null) {
            return new HarvestedItems(ObjectLists.emptyList(), ObjectLists.emptyList(), ObjectLists.emptyList(), null);
        }
        var clazz = recipe.getClass();
        var accessors = ACCESSOR_CACHE.computeIfAbsent(clazz, FastHarvester::resolveAccessors);
        var items = borrowList(TL_ITEMS);
        var fluids = borrowList(TL_FLUIDS);
        var ingredients = borrowList(TL_INGREDIENTS);
        var visitedItems = borrowMap(TL_VISITED_ITEMS);
        var visitedIngredients = borrowMap(TL_VISITED_INGREDIENTS);
        var visitedFluids = borrowMap(TL_VISITED_FLUIDS);
        var visitedAll = borrowMap(TL_VISITED_ALL);

        Set<Ingredient> standardInputs = Collections.emptySet();
        if (recipe instanceof Recipe<?> r) {
            try {
                var ingList = r.getIngredients();
                if (!ingList.isEmpty()) standardInputs = new HashSet<>(ingList);
            } catch (Throwable ignored) {
            }
        }

        try {
            for (var acc : accessors.itemAccessors) {
                try {
                    var raw = acc.extract(recipe, level);
                    collectItemsDeep(raw, items, 0, visitedItems);
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.ingredientAccessors) {
                try {
                    var raw = acc.extract(recipe, level);
                    collectIngredientsDeep(raw, ingredients, 0, visitedIngredients);
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.fluidAccessors) {
                try {
                    var raw = acc.extract(recipe, level);
                    collectFluidsDeep(raw, fluids, 0, visitedFluids);
                } catch (Throwable ignored) {
                }
            }
            for (var acc : accessors.probeAccessors) {
                try {
                    var raw = acc.extract(recipe, level);
                    if (raw != null && !isEmptyContainer(raw)) {
                        collectAllDeep(raw, items, ingredients, fluids, 0, visitedAll, standardInputs);
                    }
                } catch (Throwable ignored) {
                }
            }

            boolean empty = items.isEmpty() && ingredients.isEmpty() && fluids.isEmpty();
            if (empty) {
                ComplexityAnalyzer.LOGGER.debug("[Harvest:Empty] {} itemAcc={} ingAcc={} fluidAcc={} probeAcc={}",
                        recipe.getClass().getName(), accessors.itemAccessors.size(),
                        accessors.ingredientAccessors.size(), accessors.fluidAccessors.size(),
                        accessors.probeAccessors.size());
            }

            return new HarvestedItems(
                    items.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(items),
                    ingredients.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(ingredients),
                    fluids.isEmpty() ? ObjectLists.emptyList() : new ObjectArrayList<>(fluids),
                    recipe
            );
        } finally {
            items.clear();
            fluids.clear();
            ingredients.clear();
            visitedItems.clear();
            visitedIngredients.clear();
            visitedFluids.clear();
            visitedAll.clear();
        }
    }

    private static ResolvedAccessors resolveAccessors(Class<?> clazz) {
        var meta = getMeta(clazz);
        var itemAcc = new ObjectArrayList<Accessor>(4);
        var ingredientAcc = new ObjectArrayList<Accessor>(4);
        var fluidAcc = new ObjectArrayList<Accessor>(4);
        var probeAcc = new ObjectArrayList<Accessor>(4);
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            var h = meta.allHandles[i];
            if (h == null) continue;
            if (m.getDeclaringClass() == Recipe.class) continue;
            var kind = StructuralTypeClassifier.classifyDescriptor(descriptorOf(m));
            if (kind != null) {
                var acc = new MethodAccessor(h, m);
                switch (kind) {
                    case ITEM_STACK -> itemAcc.add(acc);
                    case INGREDIENT -> ingredientAcc.add(acc);
                    case FLUID_STACK -> fluidAcc.add(acc);
                    default -> {
                    }
                }
            } else if (isContainerReturnType(m.getReturnType())) {
                probeAcc.add(new MethodAccessor(h, m));
            }
        }
        for (int i = 0; i < meta.fields.length; i++) {
            var f = meta.fields[i];
            var type = f.getType();
            var kind = StructuralTypeClassifier.classifyDescriptor(type.getName().replace('.', '/'));
            if (kind != null) {
                var acc = new FieldAccessor(meta.fieldVarHandles[i], f);
                switch (kind) {
                    case ITEM_STACK -> itemAcc.add(acc);
                    case INGREDIENT -> ingredientAcc.add(acc);
                    case FLUID_STACK -> fluidAcc.add(acc);
                    default -> {
                    }
                }
            } else if (isContainerReturnType(type)) {
                probeAcc.add(new FieldAccessor(meta.fieldVarHandles[i], f));
            }
        }
        return new ResolvedAccessors(itemAcc, ingredientAcc, fluidAcc, probeAcc);
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

    private static String descriptorOf(Method m) {
        var rt = m.getReturnType();
        if (rt == void.class || rt == Void.class) return null;
        return rt.getName().replace('.', '/');
    }

    private static ClassMeta getMeta(Class<?> clazz) {
        return META_CACHE.computeIfAbsent(clazz, ClassMeta::new);
    }

    private static void collectItemsDeep(Object obj, ObjectList<ItemStack> acc, int depth,
                                         IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 5) return;

        var detector = getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                int count = (int) detector.countExtractor().invoke(obj);
                if (detector.returnsArray()) {
                    ItemStack[] stacks = (ItemStack[]) detector.ingredientExtractor().invoke(obj);
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
                    Ingredient ing = (Ingredient) detector.ingredientExtractor().invoke(obj);
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

        var meta = getMeta(obj.getClass());
        var localItems = new ObjectArrayList<ItemStack>();
        for (int i = 0; i < meta.scanVarHandles.length; i++) {
            try {
                var val = meta.scanVarHandles[i].get(obj);
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

        var detector = getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                if (!detector.returnsArray()) {
                    Ingredient ing = (Ingredient) detector.ingredientExtractor().invoke(obj);
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

        var meta = getMeta(obj.getClass());
        var localIngs = new ObjectArrayList<Ingredient>();
        for (int i = 0; i < meta.scanVarHandles.length; i++) {
            try {
                var val = meta.scanVarHandles[i].get(obj);
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

        var meta = getMeta(obj.getClass());
        var localFluids = new ObjectArrayList<FluidStack>();
        for (int i = 0; i < meta.scanVarHandles.length; i++) {
            try {
                var val = meta.scanVarHandles[i].get(obj);
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

    private static void collectAllDeep(Object obj,
                                       ObjectList<ItemStack> items,
                                       ObjectList<Ingredient> ingredients,
                                       ObjectList<FluidStack> fluids,
                                       int depth,
                                       IdentityHashMap<Object, Boolean> visited,
                                       Set<Ingredient> standardInputs) {
        if (obj == null || depth > 5) return;

        var detector = getSizedDetector(obj.getClass());
        if (detector.isSizedWrapper()) {
            try {
                int count = (int) detector.countExtractor().invoke(obj);
                if (detector.returnsArray()) {
                    ItemStack[] stacks = (ItemStack[]) detector.ingredientExtractor().invoke(obj);
                    if (stacks != null) {
                        for (var stack : stacks) {
                            if (stack != null && !stack.isEmpty()) {
                                var copy = stack.copy();
                                copy.setCount(count);
                                items.add(copy);
                            }
                        }
                    }
                } else {
                    Ingredient innerIng = (Ingredient) detector.ingredientExtractor().invoke(obj);
                    if (innerIng != null) {
                        if (standardInputs.contains(innerIng)) {
                            ingredients.add(innerIng);
                        } else {
                            for (var stack : innerIng.getItems()) {
                                if (stack != null && !stack.isEmpty()) {
                                    var copy = stack.copy();
                                    copy.setCount(count);
                                    items.add(copy);
                                }
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
                items.add(stack);
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (standardInputs.contains(ing)) {
                    ingredients.add(ing);
                } else {
                    for (var stack : ing.getItems()) {
                        if (stack != null && !stack.isEmpty()) {
                            items.add(stack);
                        }
                    }
                }
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                fluids.add(fs);
                return;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return;
                for (var item : coll)
                    collectAllDeep(item, items, ingredients, fluids, depth + 1, visited, standardInputs);
                return;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return;
                for (var entry : map.entrySet()) {
                    var key = entry.getKey();
                    if (key != null && !StructuralTypeClassifier.isTerminalType(key.getClass())) {
                        collectAllDeep(key, items, ingredients, fluids, depth + 1, visited, standardInputs);
                    }
                    collectAllDeep(entry.getValue(), items, ingredients, fluids, depth + 1, visited, standardInputs);
                }
                return;
            }
            case Object[] arr -> {
                if (arr.length > 50) return;
                for (var item : arr)
                    collectAllDeep(item, items, ingredients, fluids, depth + 1, visited, standardInputs);
                return;
            }
            default -> {
            }
        }
        if (StructuralTypeClassifier.isTerminalType(obj.getClass())) return;

        var cached = UNIFIED_SCAN_CACHE.get(obj);
        if (cached != null) {
            items.addAll(cached.items);
            ingredients.addAll(cached.ingredients);
            fluids.addAll(cached.fluids);
            return;
        }

        if (visited.put(obj, Boolean.TRUE) != null) return;

        var meta = getMeta(obj.getClass());
        var localItems = new ObjectArrayList<ItemStack>();
        var localIngs = new ObjectArrayList<Ingredient>();
        var localFluids = new ObjectArrayList<FluidStack>();
        for (int i = 0; i < meta.scanVarHandles.length; i++) {
            try {
                var val = meta.scanVarHandles[i].get(obj);
                switch (val) {
                    case null -> {
                    }
                    case ItemStack s when !s.isEmpty() -> localItems.add(s);
                    case Ingredient ing when !ing.isEmpty() -> {
                        if (standardInputs.contains(ing)) {
                            localIngs.add(ing);
                        } else {
                            for (var stack : ing.getItems()) {
                                if (stack != null && !stack.isEmpty()) {
                                    localItems.add(stack);
                                }
                            }
                        }
                    }
                    case FluidStack fs when !fs.isEmpty() -> localFluids.add(fs);
                    default ->
                            collectAllDeep(val, localItems, localIngs, localFluids, depth + 1, visited, standardInputs);
                }
            } catch (Throwable ignored) {
            }
        }
        if (localItems.isEmpty() && localIngs.isEmpty() && localFluids.isEmpty()) {
            probeTerminalMethodsAll(obj, localItems, localIngs, localFluids, depth, visited, standardInputs);
        }
        var result = new UnifiedResult();
        result.items = localItems;
        result.ingredients = localIngs;
        result.fluids = localFluids;
        UNIFIED_SCAN_CACHE.put(obj, result);
        items.addAll(localItems);
        ingredients.addAll(localIngs);
        fluids.addAll(localFluids);
    }

    private static void probeTerminalMethodsForItems(Object obj, ObjectList<ItemStack> acc, int depth,
                                                     IdentityHashMap<Object, Boolean> visited) {
        if (depth > 4) return;
        String clsName = obj.getClass().getName();
        if (clsName.startsWith("java.") || clsName.startsWith("net.minecraft.")
                || clsName.startsWith("it.unimi.") || clsName.startsWith("com.mojang.")) return;
        var meta = getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != ItemStack.class && !isContainerReturnType(rt)) continue;
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
        var meta = getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != Ingredient.class && !isContainerReturnType(rt)) continue;
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
        var meta = getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != FluidStack.class && !isContainerReturnType(rt)) continue;
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
                                                ObjectList<ItemStack> items,
                                                ObjectList<Ingredient> ingredients,
                                                ObjectList<FluidStack> fluids,
                                                int depth,
                                                IdentityHashMap<Object, Boolean> visited,
                                                Set<Ingredient> standardInputs) {
        if (depth > 4) return;
        String clsName = obj.getClass().getName();
        if (clsName.startsWith("java.") || clsName.startsWith("net.minecraft.")
                || clsName.startsWith("it.unimi.") || clsName.startsWith("com.mojang.")) return;
        var meta = getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (StructuralTypeClassifier.isTerminalType(rt)) continue;
            if (rt != ItemStack.class && rt != Ingredient.class && rt != FluidStack.class
                    && !isContainerReturnType(rt)) continue;
            var h = meta.allHandles[i];
            if (h == null) continue;
            try {
                var result = h.invoke(obj);
                if (result != null && !isTooLarge(result)) {
                    collectAllDeep(result, items, ingredients, fluids, depth + 1, visited, standardInputs);
                }
            } catch (Throwable ignored) {
            }
        }
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
        META_CACHE.clear();
        ACCESSOR_CACHE.clear();
        ITEM_SCAN_CACHE.clear();
        INGREDIENT_SCAN_CACHE.clear();
        FLUID_SCAN_CACHE.clear();
        UNIFIED_SCAN_CACHE.clear();
        STRUCTURAL_SIZED_CACHE.clear();
    }

    public record HarvestedItems(
            ObjectList<ItemStack> items,
            ObjectList<Ingredient> ingredients,
            ObjectList<FluidStack> fluids,
            Object root
    ) {
        public boolean isEmpty() {
            return items.isEmpty() && ingredients.isEmpty() && fluids.isEmpty();
        }
    }

    private record ResolvedAccessors(
            ObjectList<Accessor> itemAccessors,
            ObjectList<Accessor> ingredientAccessors,
            ObjectList<Accessor> fluidAccessors,
            ObjectList<Accessor> probeAccessors
    ) {
    }

    interface Accessor {
        Object extract(Object recipe, Level level) throws Throwable;

        default String type() {
            return "unknown";
        }

        default String name() {
            return "unknown";
        }
    }

    static final class MethodAccessor implements Accessor {
        private final MethodHandle noArgHandle;
        private final MethodHandle fullHandle;
        private final Method method;

        MethodAccessor(MethodHandle handle, Method method) {
            this.method = method;
            int paramCount = method.getParameterCount();
            if (paramCount == 0) {
                this.noArgHandle = handle.asType(MethodType.methodType(Object.class, Object.class));
                this.fullHandle = null;
            } else {
                this.noArgHandle = null;
                this.fullHandle = handle;
            }
        }

        @Override
        public String type() {
            return "method";
        }

        @Override
        public String name() {
            return method.getName();
        }

        @Override
        public String toString() {
            return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "()";
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            if (noArgHandle != null) return noArgHandle.invokeExact(recipe);
            var params = method.getParameterTypes();
            var args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAssignableFrom(Level.class)) args[i] = level;
                else return null;
            }
            var all = new Object[1 + args.length];
            all[0] = recipe;
            System.arraycopy(args, 0, all, 1, args.length);
            return fullHandle.invokeWithArguments(all);
        }
    }

    static final class FieldAccessor implements Accessor {
        private final VarHandle varHandle;
        private final Field field;

        FieldAccessor(VarHandle varHandle, Field field) {
            this.varHandle = varHandle;
            this.field = field;
        }

        @Override
        public String type() {
            return "field";
        }

        @Override
        public String name() {
            return field.getName();
        }

        @Override
        public String toString() {
            return field.getDeclaringClass().getSimpleName() + "." + field.getName();
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            if (varHandle != null) return varHandle.get(recipe);
            return field.get(recipe);
        }
    }

    static final class ClassMeta {
        final Method[] allMethods;
        final MethodHandle[] allHandles;
        final Field[] fields;
        final VarHandle[] fieldVarHandles;
        final VarHandle[] scanVarHandles;

        ClassMeta(Class<?> clazz) {
            var mList = new ObjectArrayList<Method>();
            var hList = new ObjectArrayList<MethodHandle>();
            var queue = new ObjectArrayList<Class<?>>();
            var seenMethods = new HashSet<String>();
            queue.add(clazz);
            int idx = 0;
            while (idx < queue.size()) {
                var current = queue.get(idx++);
                if (current == null || current == Object.class) continue;
                for (var m : current.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    if (Modifier.isStatic(m.getModifiers())) continue;

                    var rt = m.getReturnType();
                    if (rt == void.class || rt == Void.class) continue;
                    if (rt.isPrimitive()) continue;
                    if (rt == String.class || rt == Boolean.class || Number.class.isAssignableFrom(rt) || rt == Character.class)
                        continue;
                    if (StructuralTypeClassifier.isTerminalType(rt)) continue;

                    if (!seenMethods.add(m.getName())) continue;

                    var handle = createHandle(m);
                    if (handle != null) {
                        mList.add(m);
                        hList.add(handle);
                    }
                }
                var sup = current.getSuperclass();
                if (sup != null && sup != Object.class) queue.add(sup);
                Collections.addAll(queue, current.getInterfaces());
            }
            this.allMethods = mList.toArray(new Method[0]);
            this.allHandles = hList.toArray(new MethodHandle[0]);

            var fList = new ObjectArrayList<Field>();
            var vhList = new ObjectArrayList<VarHandle>();
            var curCls = clazz;
            while (curCls != null && curCls != Object.class) {
                for (var f : curCls.getDeclaredFields()) {
                    boolean duplicate = false;
                    for (int i = 0; i < fList.size(); i++) {
                        if (fList.get(i).getName().equals(f.getName())) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) continue;
                    try {
                        f.setAccessible(true);
                        var priv = MethodHandles.privateLookupIn(curCls, LOOKUP);
                        var vh = priv.unreflectVarHandle(f);
                        fList.add(f);
                        vhList.add(vh);
                    } catch (Exception ignored) {
                    }
                }
                curCls = curCls.getSuperclass();
            }
            this.fields = fList.toArray(new Field[0]);
            this.fieldVarHandles = vhList.toArray(new VarHandle[0]);

            var sList = new ObjectArrayList<VarHandle>();
            for (int i = 0; i < fList.size(); i++) {
                var type = fList.get(i).getType();
                if (type.isPrimitive() || type == String.class || type.isEnum()) continue;
                if (StructuralTypeClassifier.isTerminalType(type)) continue;
                sList.add(vhList.get(i));
            }
            this.scanVarHandles = sList.toArray(new VarHandle[0]);
        }

        private static MethodHandle createHandle(Method method) {
            try {
                method.setAccessible(true);
                if (method.getDeclaringClass().isInterface()) {
                    var pub = MethodHandles.publicLookup();
                    var mt = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
                    return pub.findVirtual(method.getDeclaringClass(), method.getName(), mt);
                }
                var priv = MethodHandles.privateLookupIn(method.getDeclaringClass(), LOOKUP);
                return priv.unreflect(method);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static boolean isContainerReturnType(Class<?> type) {
        if (type == null || type == void.class) return false;
        return type.isArray()
                || Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type);
    }
}
