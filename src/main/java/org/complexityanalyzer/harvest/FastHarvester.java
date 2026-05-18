package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.*;

/**
 * Универсальный быстрый harvester рецептов — v2.0.
 * Использует новые компоненты без хардкода:
 * - {@link UniversalTypeResolver} вместо StructuralTypeClassifier
 * - {@link UniversalAccessorResolver} вместо RecipeReflection
 * - {@link HeuristicRoleClassifier} вместо встроенного classifyAccessor()
 * <p>
 * Сохраняет все оптимизации: ThreadLocal буферы, IdentityHashMap кэши,
 * fastutil коллекции, SizedIngredientDetector.
 */
public final class FastHarvester {

    // Кэши для scan-результатов (как в оригинале)
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

    // ThreadLocal буферы
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

    // ======================== Публичный API ========================

    /**
     * Извлечь все ItemStack, Ingredient, FluidStack из объекта рецепта.
     * Использует UniversalAccessorResolver для автоматического определения
     * accessor'ов и HeuristicRoleClassifier для INPUT/OUTPUT ролей.
     */
    public HarvestedItems harvest(Object recipe, Level level) {
        if (recipe == null) return new HarvestedItems(
                ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), null
        );

        // Стандартный Recipe из Minecraft/NeoForge — обрабатываем быстро через API
        if (recipe instanceof Recipe<?> r) {
            var inputIngredients = new ObjectArrayList<Ingredient>();
            for (var ing : r.getIngredients()) {
                if (ing != null && !ing.isEmpty()) inputIngredients.add(ing);
            }
            var outputItems = new ObjectArrayList<ItemStack>();
            try {
                var res = r.getResultItem(level.registryAccess());
                if (!res.isEmpty()) outputItems.add(res.copy());
            } catch (Throwable ignored) {
            }

            // Если это ванильный рецепт без дополнительных данных — возвращаем сразу
            Class<?> clazz = recipe.getClass();
            if (clazz.getName().startsWith("net.minecraft.") || clazz.getName().startsWith("net.neoforged.")) {
                return new HarvestedItems(
                        ObjectLists.emptyList(), outputItems,
                        inputIngredients, ObjectLists.emptyList(),
                        ObjectLists.emptyList(), recipe
                );
            }

            // Даже для не-ванильных Recipe — используем стандартные inputs/outputs как базу
            // но также сканируем поля/методы для дополнительных данных
        }

        // Универсальный путь: используем UniversalAccessorResolver
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
            // Получаем стандартные inputs для сравнения
            var standardInputs = new HashSet<Ingredient>();
            if (recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) standardInputs.add(ing);
                }
            }

            // Используем UniversalAccessorResolver
            UniversalAccessorResolver.ResolvedAccessors accessors =
                    UniversalAccessorResolver.resolve(recipe, level);

            // Обрабатываем все accessors
            for (var acc : accessors.allAccessors()) {
                try {
                    Object raw = acc.extract(recipe, level);
                    if (raw == null) continue;

                    HeuristicRoleClassifier.Role role = acc.role();

                    // Определяем тип значения и направляем в соответствующий коллектор
                    if (raw instanceof ItemStack stack && !stack.isEmpty()) {
                        if (role == HeuristicRoleClassifier.Role.OUTPUT) {
                            outputItems.add(stack);
                        } else {
                            inputItems.add(stack);
                        }
                    } else if (raw instanceof Ingredient ing && !ing.isEmpty()) {
                        inputIngredients.add(ing);
                    } else if (raw instanceof FluidStack fs && !fs.isEmpty()) {
                        if (role == HeuristicRoleClassifier.Role.OUTPUT) {
                            outputFluids.add(fs);
                        } else {
                            inputFluids.add(fs);
                        }
                    } else if (raw instanceof Iterable<?> coll && !isTooLarge(coll)) {
                        // Рекурсивно обрабатываем коллекцию
                        for (var item : coll) {
                            routeValue(item, inputItems, outputItems, inputIngredients,
                                    inputFluids, outputFluids, role, 1, visitedAll, standardInputs);
                        }
                    } else if (raw instanceof Map<?, ?> map && !isTooLarge(map)) {
                        for (var entry : map.entrySet()) {
                            routeValue(entry.getValue(), inputItems, outputItems, inputIngredients,
                                    inputFluids, outputFluids, role, 1, visitedAll, standardInputs);
                        }
                    } else if (raw instanceof Object[] arr && arr.length <= 50) {
                        for (var item : arr) {
                            routeValue(item, inputItems, outputItems, inputIngredients,
                                    inputFluids, outputFluids, role, 1, visitedAll, standardInputs);
                        }
                    } else if (!isTerminalValue(raw)) {
                        // Неизвестный тип — сканируем глубоко
                        collectAllDeep(raw, inputItems, outputItems, inputIngredients,
                                inputFluids, outputFluids, role, 1, visitedAll, standardInputs);
                    }
                } catch (Throwable ignored) {
                }
            }

            // Fallback: если ничего не нашли, используем стандартные inputs/outputs
            if (inputIngredients.isEmpty() && inputItems.isEmpty() && recipe instanceof Recipe<?> r) {
                for (var ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) inputIngredients.add(ing);
                }
            }
            if (outputItems.isEmpty() && recipe instanceof Recipe<?> r) {
                try {
                    var res = r.getResultItem(level.registryAccess());
                    if (!res.isEmpty()) outputItems.add(res.copy());
                } catch (Throwable ignored) {
                }
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

    // ======================== Приватные методы ========================

    private static void routeValue(Object val,
                                   ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                   ObjectList<Ingredient> inputIngredients,
                                   ObjectList<FluidStack> inputFluids, ObjectList<FluidStack> outputFluids,
                                   HeuristicRoleClassifier.Role role, int depth,
                                   IdentityHashMap<Object, Boolean> visited, Set<Ingredient> standardInputs) {
        if (val == null || depth > 5) return;

        if (val instanceof ItemStack stack && !stack.isEmpty()) {
            if (role == HeuristicRoleClassifier.Role.OUTPUT) outputItems.add(stack);
            else inputItems.add(stack);
        } else if (val instanceof Ingredient ing && !ing.isEmpty()) {
            inputIngredients.add(ing);
        } else if (val instanceof FluidStack fs && !fs.isEmpty()) {
            if (role == HeuristicRoleClassifier.Role.OUTPUT) outputFluids.add(fs);
            else inputFluids.add(fs);
        } else if (val instanceof Iterable<?> coll && !isTooLarge(coll)) {
            for (var item : coll) {
                routeValue(item, inputItems, outputItems, inputIngredients,
                        inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
            }
        } else if (val instanceof Map<?, ?> map && !isTooLarge(map)) {
            for (var entry : map.entrySet()) {
                routeValue(entry.getValue(), inputItems, outputItems, inputIngredients,
                        inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
            }
        } else if (val instanceof Object[] arr && arr.length <= 50) {
            for (var item : arr) {
                routeValue(item, inputItems, outputItems, inputIngredients,
                        inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
            }
        } else if (!isTerminalValue(val)) {
            collectAllDeep(val, inputItems, outputItems, inputIngredients,
                    inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
        }
    }

    private static void collectAllDeep(Object obj,
                                       ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                       ObjectList<Ingredient> inputIngredients,
                                       ObjectList<FluidStack> inputFluids, ObjectList<FluidStack> outputFluids,
                                       HeuristicRoleClassifier.Role role, int depth,
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
                    if (innerIng != null) {
                        if (standardInputs.contains(innerIng)) {
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
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        switch (obj) {
            case ItemStack stack when !stack.isEmpty() -> {
                if (role == HeuristicRoleClassifier.Role.OUTPUT) outputItems.add(stack);
                else inputItems.add(stack);
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (standardInputs.contains(ing)) {
                    inputIngredients.add(ing);
                } else {
                    for (var stack : ing.getItems()) {
                        if (stack != null && !stack.isEmpty()) inputItems.add(stack);
                    }
                }
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                if (role == HeuristicRoleClassifier.Role.OUTPUT) outputFluids.add(fs);
                else inputFluids.add(fs);
                return;
            }
            case Iterable<?> coll -> {
                if (isTooLarge(coll)) return;
                for (var item : coll) {
                    collectAllDeep(item, inputItems, outputItems, inputIngredients,
                            inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                }
                return;
            }
            case Map<?, ?> map -> {
                if (isTooLarge(map)) return;
                for (var entry : map.entrySet()) {
                    var key = entry.getKey();
                    if (key != null && !UniversalTypeResolver.isTerminalType(key.getClass())) {
                        collectAllDeep(key, inputItems, outputItems, inputIngredients,
                                inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                    }
                    collectAllDeep(entry.getValue(), inputItems, outputItems, inputIngredients,
                            inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                }
                return;
            }
            case Object[] arr -> {
                if (arr.length > 50) return;
                for (var item : arr) {
                    collectAllDeep(item, inputItems, outputItems, inputIngredients,
                            inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                }
                return;
            }
            default -> {
            }
        }

        if (UniversalTypeResolver.isTerminalType(obj.getClass())) return;

        var cache = (role == HeuristicRoleClassifier.Role.OUTPUT) ? UNIFIED_OUTPUT_SCAN_CACHE : UNIFIED_INPUT_SCAN_CACHE;
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

        var meta = UniversalAccessorResolver.getMeta(obj.getClass());
        var localInputItems = new ObjectArrayList<ItemStack>();
        var localOutputItems = new ObjectArrayList<ItemStack>();
        var localInputIngs = new ObjectArrayList<Ingredient>();
        var localInputFluids = new ObjectArrayList<FluidStack>();
        var localOutputFluids = new ObjectArrayList<FluidStack>();

        for (int i = 0; i < meta.scanFields().length; i++) {
            try {
                var val = meta.scanFields()[i].get(obj);
                switch (val) {
                    case null -> {
                    }
                    case ItemStack s when !s.isEmpty() -> {
                        if (role == HeuristicRoleClassifier.Role.OUTPUT) localOutputItems.add(s);
                        else localInputItems.add(s);
                    }
                    case Ingredient ing when !ing.isEmpty() -> {
                        if (standardInputs.contains(ing)) {
                            localInputIngs.add(ing);
                        } else {
                            for (var stack : ing.getItems()) {
                                if (stack != null && !stack.isEmpty()) localInputItems.add(stack);
                            }
                        }
                    }
                    case FluidStack fs when !fs.isEmpty() -> {
                        if (role == HeuristicRoleClassifier.Role.OUTPUT) localOutputFluids.add(fs);
                        else localInputFluids.add(fs);
                    }
                    default -> collectAllDeep(val, localInputItems, localOutputItems, localInputIngs,
                            localInputFluids, localOutputFluids, role, depth + 1, visited, standardInputs);
                }
            } catch (Throwable ignored) {
            }
        }

        if (localInputItems.isEmpty() && localOutputItems.isEmpty() && localInputIngs.isEmpty()
                && localInputFluids.isEmpty() && localOutputFluids.isEmpty()) {
            probeTerminalMethodsAll(obj, localInputItems, localOutputItems, localInputIngs,
                    localInputFluids, localOutputFluids, role, depth, visited, standardInputs);
        }

        var result = new UnifiedResult(localInputItems, localOutputItems, localInputIngs, localInputFluids, localOutputFluids);
        cache.put(obj, result);
        inputItems.addAll(localInputItems);
        outputItems.addAll(localOutputItems);
        inputIngredients.addAll(localInputIngs);
        inputFluids.addAll(localInputFluids);
        outputFluids.addAll(localOutputFluids);
    }

    private static void probeTerminalMethodsAll(Object obj,
                                                ObjectList<ItemStack> inputItems, ObjectList<ItemStack> outputItems,
                                                ObjectList<Ingredient> inputIngredients,
                                                ObjectList<FluidStack> inputFluids, ObjectList<FluidStack> outputFluids,
                                                HeuristicRoleClassifier.Role role, int depth,
                                                IdentityHashMap<Object, Boolean> visited, Set<Ingredient> standardInputs) {
        if (depth > 4) return;
        if (UniversalTypeResolver.isTerminalType(obj.getClass())) return;

        var meta = UniversalAccessorResolver.getMeta(obj.getClass());
        for (int i = 0; i < meta.allMethods().length; i++) {
            var m = meta.allMethods()[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == void.class || rt == Void.class) continue;
            if (UniversalTypeResolver.isTerminalType(rt)) continue;

            var h = meta.allHandles()[i];
            if (h == null) continue;
            try {
                var result = h.invoke(obj);
                if (result != null && !isTooLarge(result)) {
                    collectAllDeep(result, inputItems, outputItems, inputIngredients,
                            inputFluids, outputFluids, role, depth + 1, visited, standardInputs);
                }
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

    private static boolean isTerminalValue(Object obj) {
        if (obj == null) return true;
        Class<?> c = obj.getClass();
        return c.isPrimitive() || c == String.class || c.isEnum() || Number.class.isAssignableFrom(c)
                || c == Boolean.class || c == Character.class;
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
        UniversalAccessorResolver.clearCache();
        UniversalTypeResolver.clearCache();
        HeuristicRoleClassifier.clearCache();
        PatternSignatureEngine.clearCache();
        AntivirusStyleDetector.clearCache();
        SizedIngredientDetector.clearCache();
    }
}
