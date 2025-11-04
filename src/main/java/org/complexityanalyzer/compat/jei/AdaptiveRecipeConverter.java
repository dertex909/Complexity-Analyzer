/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.compat.jei;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeNode;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Адаптивный конвертер - ГЛУБОКО ищет ItemStack внутри любых объектов.
 */
public class AdaptiveRecipeConverter {

    private static final Map<Class<?>, RecipeAdapter> LEARNED_ADAPTERS = new ConcurrentHashMap<>();
    private static final int MAX_RECURSION_DEPTH = 3;
    private static final boolean VERBOSE_DEBUG = false;

    public static List<ItemStack> extractOutputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        Class<?> recipeClass = actualRecipe.getClass();

        RecipeAdapter adapter = LEARNED_ADAPTERS.computeIfAbsent(recipeClass, clazz ->
                new RecipeAdapter(null, null)
        );

        // ========== ИСПРАВЛЕНИЕ: Доучиваем OUTPUT если нужно ==========
        if (adapter.outputMethod == null) {
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[OUTPUT] Learning output method for {}", recipeClass.getSimpleName());
            }
            Method outputMethod = learnMethod(actualRecipe, true, level);
            adapter = new RecipeAdapter(outputMethod, adapter.inputMethod);
            LEARNED_ADAPTERS.put(recipeClass, adapter);
        }
        // ==============================================================

        if (adapter.outputMethod == null) {
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[OUTPUT] No output method found for {}",
                        recipeClass.getSimpleName());
            }
            return new ArrayList<>();
        }

        try {
            Object result = invokeMethod(adapter.outputMethod, actualRecipe, level);

            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[OUTPUT] Method {}() returned: {} (type: {})",
                        adapter.outputMethod.getName(),
                        result,
                        result != null ? result.getClass().getSimpleName() : "null");
            }

            List<ItemStack> stacks = deepFindItemStacks(result, 0);

            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[OUTPUT] Extracted {} ItemStacks", stacks.size());
            }

            return stacks;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("[OUTPUT] Failed to extract outputs: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public static RecipeNode convertRecipe(net.minecraft.world.item.crafting.Recipe<?> recipe, Level level) {
        List<ItemStack> outputs = extractOutputs(recipe, level);

        if (outputs.isEmpty()) {
            return null;
        }

        ItemStack primaryOutput = outputs.getFirst();
        Item resultItem = primaryOutput.getItem();
        int resultCount = primaryOutput.getCount();

        // Извлекаем ингредиенты
        List<List<ItemStack>> inputGroups = extractInputs(recipe, level);

        if (inputGroups.isEmpty()) {
            return null;
        }

        // ========== КЛАССИФИКАЦИЯ через GraphBuilder ==========
        // Преобразуем List<List<ItemStack>> обратно в List<Ingredient>
        List<net.minecraft.world.item.crafting.Ingredient> ingredients = new java.util.ArrayList<>();
        for (List<ItemStack> group : inputGroups) {
            if (!group.isEmpty()) {
                net.minecraft.world.item.crafting.Ingredient ingredient =
                        net.minecraft.world.item.crafting.Ingredient.of(
                                group.toArray(new ItemStack[0])
                        );
                ingredients.add(ingredient);
            }
        }

        // Вызываем классификацию из GraphBuilder
        org.complexityanalyzer.graph.RecipeCategory category =
                org.complexityanalyzer.graph.GraphBuilder.classifyRecipe(recipe, resultItem, ingredients);

        // Пропускаем UNPROCESSABLE рецепты
        if (category == org.complexityanalyzer.graph.RecipeCategory.UNPROCESSABLE) {
            return null;
        }
        // =====================================================

        org.complexityanalyzer.graph.RecipeNode.Builder builder =
                new org.complexityanalyzer.graph.RecipeNode.Builder(resultItem)
                        .resultCount(resultCount)
                        .recipeType(recipe.getType())
                        .category(category); // Используем классифицированную категорию

        // Устанавливаем приоритеты
        if (category == org.complexityanalyzer.graph.RecipeCategory.PRIMARY) {
            var recipeType = recipe.getType();
            if (recipeType == net.minecraft.world.item.crafting.RecipeType.SMELTING ||
                    recipeType == net.minecraft.world.item.crafting.RecipeType.BLASTING) {
                builder.priority(2000);
            } else {
                builder.priority(900); // JEI рецепты чуть ниже vanilla crafting
            }
        } else if (category == org.complexityanalyzer.graph.RecipeCategory.STORAGE_COMPRESSION ||
                category == org.complexityanalyzer.graph.RecipeCategory.STORAGE_DECOMPRESSION) {
            builder.priority(100); // Низкий приоритет для compression/decompression
        }

        // Добавляем ингредиенты
        for (List<ItemStack> inputVariants : inputGroups) {
            if (inputVariants.isEmpty()) continue;

            List<Item> items = inputVariants.stream()
                    .map(ItemStack::getItem)
                    .distinct()
                    .toList();

            int count = inputVariants.getFirst().getCount();
            builder.addIngredient(items, count);
        }

        org.complexityanalyzer.graph.RecipeNode node = builder.build();

        if (node.getIngredients().isEmpty()) {
            return null;
        }

        return node;
    }
    public static List<List<ItemStack>> extractInputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        Class<?> recipeClass = actualRecipe.getClass();

        RecipeAdapter adapter = LEARNED_ADAPTERS.computeIfAbsent(recipeClass, clazz ->
                new RecipeAdapter(null, null)
        );

        // ========== ИСПРАВЛЕНИЕ: Доучиваем INPUT если нужно ==========
        if (adapter.inputMethod == null) {
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[INPUT] Learning input method for {}", recipeClass.getSimpleName());
            }
            Method inputMethod = learnMethod(actualRecipe, false, level);
            adapter = new RecipeAdapter(adapter.outputMethod, inputMethod);
            LEARNED_ADAPTERS.put(recipeClass, adapter);
        }
        // =============================================================

        if (adapter.inputMethod == null) {
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[INPUT] No input method found for {}",
                        recipeClass.getSimpleName());
                ComplexityAnalyzer.LOGGER.debug("[INPUT] Available methods:");
                for (Method m : recipeClass.getMethods()) {
                    if (m.getName().toLowerCase().contains("ingredient") ||
                            m.getName().toLowerCase().contains("input")) {
                        ComplexityAnalyzer.LOGGER.debug("[INPUT]   - {} (params: {}, returns: {})",
                                m.getName(),
                                m.getParameterCount(),
                                m.getReturnType().getSimpleName());
                    }
                }
            }
            return new ArrayList<>();
        }

        try {
            Object result = invokeMethod(adapter.inputMethod, actualRecipe, level);

            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[INPUT] Method {}() returned: {} (type: {})",
                        adapter.inputMethod.getName(),
                        result != null ? result.getClass().getSimpleName() : "null",
                        result != null ? result.getClass().getName() : "null");

                if (result instanceof Collection<?> coll) {
                    ComplexityAnalyzer.LOGGER.debug("[INPUT] Collection size: {}", coll.size());
                    if (!coll.isEmpty()) {
                        Object first = coll.iterator().next();
                        ComplexityAnalyzer.LOGGER.debug("[INPUT] First element type: {} ({})",
                                first.getClass().getSimpleName(),
                                first.getClass().getName());

                        if (first instanceof Ingredient ing) {
                            ItemStack[] items = ing.getItems();
                            ComplexityAnalyzer.LOGGER.debug("[INPUT] Ingredient contains {} items", items.length);
                            if (items.length > 0) {
                                ComplexityAnalyzer.LOGGER.debug("[INPUT] First item: {} x{}",
                                        items[0].getItem(),
                                        items[0].getCount());
                            }
                        }
                    }
                }
            }

            List<List<ItemStack>> inputs = deepFindItemStackLists(result, 0);

            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[INPUT] Extracted {} ingredient groups", inputs.size());
                for (int i = 0; i < inputs.size(); i++) {
                    List<ItemStack> group = inputs.get(i);
                    ComplexityAnalyzer.LOGGER.debug("[INPUT]   Group {}: {} variants", i, group.size());
                }
            }

            return inputs;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("[INPUT] Failed to extract inputs: {}", e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Универсальный вызов методов с поддержкой RegistryAccess
     */
    private static Object invokeMethod(Method method, Object target, Level level) throws Exception {
        if (method.getParameterCount() == 0) {
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[INVOKE] Calling {}() with no params", method.getName());
            }
            return method.invoke(target);
        } else if (method.getParameterCount() == 1) {
            Class<?> paramType = method.getParameterTypes()[0];

            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[INVOKE] Method {}() requires param: {}",
                        method.getName(), paramType.getSimpleName());
            }

            // Если требуется RegistryAccess, передаем его
            if (paramType.getSimpleName().contains("RegistryAccess") ||
                    paramType.getName().contains("RegistryAccess")) {
                if (VERBOSE_DEBUG) {
                    ComplexityAnalyzer.LOGGER.debug("[INVOKE] Passing RegistryAccess");
                }
                return method.invoke(target, level.registryAccess());
            }

            // Для других типов пробуем null
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[INVOKE] Passing null");
            }
            return method.invoke(target, (Object) null);
        }

        return null;
    }

    /**
     * Распаковывает RecipeHolder и возвращает настоящий рецепт.
     */
    private static Object unwrapRecipeHolder(Object obj) {
        if (obj == null) {
            return null;
        }

        String className = obj.getClass().getSimpleName();

        if (className.equals("RecipeHolder")) {
            try {
                Method valueMethod = obj.getClass().getMethod("value");
                Object innerRecipe = valueMethod.invoke(obj);

                if (innerRecipe != null) {
                    return innerRecipe;
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("Failed to unwrap RecipeHolder: {}", e.getMessage());
            }
        }

        return obj;
    }

    /**
     * ========== НОВЫЙ МЕТОД: Обучение одного метода (output ИЛИ input) ==========
     */
    private static Method learnMethod(Object recipe, boolean isOutput, Level level) {
        String[] candidateMethods = isOutput
                ? new String[]{"getOutputs", "getOutput", "getResults", "getResult", "getResultItem", "output", "getOutputRaw", "getOutputDefinition"}
                : new String[]{"getInputs", "getInput", "getIngredients", "getIngredient", "inputs", "getInputRaw"};

        if (VERBOSE_DEBUG) {
            ComplexityAnalyzer.LOGGER.debug("[LEARN] Trying to learn {} extraction for {}",
                    isOutput ? "OUTPUT" : "INPUT",
                    recipe.getClass().getSimpleName());
        }

        for (String methodName : candidateMethods) {
            try {
                Method method = findAnyMethod(recipe.getClass(), methodName);
                if (method == null) continue;

                if (VERBOSE_DEBUG) {
                    ComplexityAnalyzer.LOGGER.debug("[LEARN] Testing method: {}()", methodName);
                }

                method.setAccessible(true);
                Object result;

                try {
                    result = invokeMethod(method, recipe, level);
                } catch (Exception e) {
                    if (VERBOSE_DEBUG) {
                        ComplexityAnalyzer.LOGGER.debug("[LEARN] Method {}() threw exception: {}",
                                methodName, e.getMessage());
                    }
                    continue;
                }

                if (result == null) {
                    if (VERBOSE_DEBUG) {
                        ComplexityAnalyzer.LOGGER.debug("[LEARN] Method {}() returned null", methodName);
                    }
                    continue;
                }

                if (VERBOSE_DEBUG) {
                    ComplexityAnalyzer.LOGGER.debug("[LEARN] Method {}() returned: {} (type: {})",
                            methodName,
                            result,
                            result.getClass().getSimpleName());
                }

                if (isOutput) {
                    List<ItemStack> stacks = deepFindItemStacks(result, 0);
                    if (!stacks.isEmpty()) {
                        return method;
                    } else if (VERBOSE_DEBUG) {
                        ComplexityAnalyzer.LOGGER.debug("[LEARN] Method {}() produced no ItemStacks", methodName);
                    }
                } else {
                    List<List<ItemStack>> inputs = deepFindItemStackLists(result, 0);
                    if (!inputs.isEmpty()) {
                        return method;
                    } else if (VERBOSE_DEBUG) {
                        ComplexityAnalyzer.LOGGER.debug("[LEARN] Method {}() produced no ItemStack lists", methodName);
                    }
                }

            } catch (Exception e) {
                if (VERBOSE_DEBUG) {
                    ComplexityAnalyzer.LOGGER.debug("[LEARN] Exception testing {}: {}", methodName, e.getMessage());
                }
            }
        }

        if (VERBOSE_DEBUG) {
            ComplexityAnalyzer.LOGGER.debug("[LEARN] Failed to learn {} extraction for {}",
                    isOutput ? "OUTPUT" : "INPUT",
                    recipe.getClass().getSimpleName());
        }

        return null;
    }

    private static List<ItemStack> deepFindItemStacks(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) {
            return new ArrayList<>();
        }

        switch (obj) {
            case ItemStack stack -> {
                return stack.isEmpty() ? new ArrayList<>() : List.of(stack);
            }
            case Ingredient ingredient -> {
                if (VERBOSE_DEBUG && depth == 0) {
                    ComplexityAnalyzer.LOGGER.debug("[DEEP-STACK] Found Ingredient at depth {}", depth);
                }
                ItemStack[] items = ingredient.getItems();
                List<ItemStack> result = new ArrayList<>();
                for (ItemStack stack : items) {
                    if (!stack.isEmpty()) {
                        result.add(stack);
                    }
                }
                if (VERBOSE_DEBUG && depth == 0) {
                    ComplexityAnalyzer.LOGGER.debug("[DEEP-STACK] Ingredient yielded {} items", result.size());
                }
                return result;
            }
            case Collection<?> coll -> {
                List<ItemStack> result = new ArrayList<>();
                for (Object item : coll) {
                    result.addAll(deepFindItemStacks(item, depth + 1));
                }
                return result;
            }
            default -> {
            }
        }

        if (obj.getClass().isArray()) {
            List<ItemStack> result = new ArrayList<>();
            for (Object item : (Object[]) obj) {
                result.addAll(deepFindItemStacks(item, depth + 1));
            }
            return result;
        }

        for (Method method : obj.getClass().getMethods()) {
            String name = method.getName();

            if (method.getParameterCount() != 0) continue;
            if (!name.startsWith("get") && !name.startsWith("as") && !name.startsWith("to")
                    && !name.equals("stack") && !name.equals("item")) {
                continue;
            }
            if (name.equals("getClass") || name.equals("toString") || name.equals("getBytes")
                    || name.startsWith("toLowerCase") || name.startsWith("toUpperCase")
                    || name.startsWith("toCharArray")) {
                continue;
            }

            try {
                method.setAccessible(true);
                Object inner = method.invoke(obj);
                List<ItemStack> found = deepFindItemStacks(inner, depth + 1);
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (Exception ignored) {}
        }

        return new ArrayList<>();
    }

    /**
     * ГЛУБОКО ищет List<ItemStack> или List<List<ItemStack>>.
     */
    private static List<List<ItemStack>> deepFindItemStackLists(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) {
            if (VERBOSE_DEBUG && depth == 0) {
                ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] Null or max depth reached");
            }
            return new ArrayList<>();
        }

        if (VERBOSE_DEBUG && depth == 0) {
            ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] Analyzing object: {} (type: {})",
                    obj.getClass().getSimpleName(),
                    obj.getClass().getName());
        }

        if (obj instanceof Ingredient ingredient) {
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] Found single Ingredient at depth {}", depth);
            }
            ItemStack[] items = ingredient.getItems();
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    stacks.add(stack);
                }
            }
            if (VERBOSE_DEBUG) {
                ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] Ingredient -> {} items", stacks.size());
            }
            return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
        }

        if (obj instanceof Collection<?> coll) {
            if (coll.isEmpty()) {
                if (VERBOSE_DEBUG && depth == 0) {
                    ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] Empty collection");
                }
                return new ArrayList<>();
            }

            Object first = coll.iterator().next();

            if (VERBOSE_DEBUG && depth == 0) {
                ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] Collection of {} elements, first type: {}",
                        coll.size(),
                        first.getClass().getSimpleName());
            }

            if (first instanceof Ingredient) {
                if (VERBOSE_DEBUG) {
                    ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] Processing List<Ingredient>");
                }
                List<List<ItemStack>> result = new ArrayList<>();
                for (Object item : coll) {
                    if (item instanceof Ingredient ingredient) {
                        ItemStack[] items = ingredient.getItems();
                        List<ItemStack> stacks = new ArrayList<>();
                        for (ItemStack stack : items) {
                            if (!stack.isEmpty()) {
                                stacks.add(stack);
                            }
                        }
                        if (!stacks.isEmpty()) {
                            result.add(stacks);
                        }
                    }
                }
                if (VERBOSE_DEBUG) {
                    ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] List<Ingredient> -> {} groups", result.size());
                }
                return result;
            }

            if (first instanceof ItemStack) {
                List<ItemStack> stacks = new ArrayList<>();
                for (Object item : coll) {
                    if (item instanceof ItemStack stack && !stack.isEmpty()) {
                        stacks.add(stack);
                    }
                }
                return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
            }

            if (first instanceof Collection) {
                List<List<ItemStack>> result = new ArrayList<>();
                for (Object inner : coll) {
                    List<List<ItemStack>> innerLists = deepFindItemStackLists(inner, depth + 1);
                    result.addAll(innerLists);
                }
                return result;
            }

            List<ItemStack> extracted = new ArrayList<>();
            for (Object item : coll) {
                List<ItemStack> stacks = deepFindItemStacks(item, depth + 1);
                extracted.addAll(stacks);
            }
            if (!extracted.isEmpty()) {
                return List.of(extracted);
            }
        }

        for (Method method : obj.getClass().getMethods()) {
            String name = method.getName();

            if (method.getParameterCount() != 0) continue;
            if (!name.startsWith("get") && !name.startsWith("as")) continue;
            if (name.equals("getClass")) continue;

            try {
                method.setAccessible(true);
                Object inner = method.invoke(obj);
                List<List<ItemStack>> found = deepFindItemStackLists(inner, depth + 1);
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (Exception ignored) {}
        }

        if (VERBOSE_DEBUG && depth == 0) {
            ComplexityAnalyzer.LOGGER.debug("[DEEP-LIST] No ItemStack lists found");
        }

        return new ArrayList<>();
    }

    private static Method findAnyMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            return null;
        }
    }

    static class RecipeAdapter {
        final Method outputMethod;
        final Method inputMethod;

        RecipeAdapter(Method outputMethod, Method inputMethod) {
            this.outputMethod = outputMethod;
            this.inputMethod = inputMethod;
        }
    }
}