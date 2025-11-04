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

import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Адаптивный конвертер - ГЛУБОКО ищет ItemStack внутри любых объектов.
 */
public class AdaptiveRecipeConverter {

    private static final Map<Class<?>, RecipeAdapter> LEARNED_ADAPTERS = new ConcurrentHashMap<>();
    private static final int MAX_RECURSION_DEPTH = 3;

    public static List<ItemStack> extractOutputs(Object recipe) {
        Class<?> recipeClass = recipe.getClass();

        RecipeAdapter adapter = LEARNED_ADAPTERS.computeIfAbsent(recipeClass, clazz ->
                learnExtraction(recipe, true)
        );

        if (adapter.outputMethod == null) {
            return new ArrayList<>();
        }

        try {
            Object result = adapter.outputMethod.invoke(recipe);
            return deepFindItemStacks(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<List<ItemStack>> extractInputs(Object recipe) {
        Class<?> recipeClass = recipe.getClass();

        RecipeAdapter adapter = LEARNED_ADAPTERS.get(recipeClass);
        if (adapter == null) {
            adapter = LEARNED_ADAPTERS.computeIfAbsent(recipeClass, clazz ->
                    learnExtraction(recipe, false)
            );
        }

        if (adapter.inputMethod == null) {
            return new ArrayList<>();
        }

        try {
            Object result = adapter.inputMethod.invoke(recipe);
            return deepFindItemStackLists(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Обучение: находим метод который возвращает ItemStack (прямо или косвенно).
     */
    private static RecipeAdapter learnExtraction(Object recipe, boolean isOutput) {
        String[] candidateMethods = isOutput
                ? new String[]{"getOutputs", "getOutput", "getResults", "getResult", "getResultItem", "output", "getOutputRaw", "getOutputDefinition"}
                : new String[]{"getInputs", "getInput", "getIngredients", "getIngredient", "inputs", "getInputRaw"};

        RecipeAdapter existing = LEARNED_ADAPTERS.get(recipe.getClass());

        for (String methodName : candidateMethods) {
            try {
                Method method = findAnyMethod(recipe.getClass(), methodName);
                if (method == null) continue;

                method.setAccessible(true);
                Object result = null;

                try {
                    if (method.getParameterCount() == 0) {
                        result = method.invoke(recipe);
                    } else if (method.getParameterCount() == 1) {
                        result = method.invoke(recipe, (Object) null);
                    }
                } catch (Exception e) {
                    continue;
                }

                if (result == null) continue;

                if (isOutput) {
                    List<ItemStack> stacks = deepFindItemStacks(result, 0);
                    if (!stacks.isEmpty()) {
                        ComplexityAnalyzer.LOGGER.debug("Learned OUTPUT extraction for {}: {}()",
                                recipe.getClass().getSimpleName(), methodName);

                        if (existing != null) {
                            return new RecipeAdapter(method, existing.inputMethod);
                        }
                        return new RecipeAdapter(method, null);
                    }
                } else {
                    List<List<ItemStack>> inputs = deepFindItemStackLists(result, 0);
                    if (!inputs.isEmpty()) {
                        ComplexityAnalyzer.LOGGER.debug("Learned INPUT extraction for {}: {}()",
                                recipe.getClass().getSimpleName(), methodName);

                        if (existing != null) {
                            return new RecipeAdapter(existing.outputMethod, method);
                        }
                        return new RecipeAdapter(null, method);
                    }
                }

            } catch (Exception ignored) {}
        }

        return new RecipeAdapter(
                existing != null ? existing.outputMethod : null,
                existing != null ? existing.inputMethod : null
        );
    }

    private static List<ItemStack> deepFindItemStacks(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) {
            return new ArrayList<>();
        }

        if (obj instanceof ItemStack stack) {
            return stack.isEmpty() ? new ArrayList<>() : List.of(stack);
        }

        if (obj instanceof Collection<?> coll) {
            List<ItemStack> result = new ArrayList<>();
            for (Object item : coll) {
                result.addAll(deepFindItemStacks(item, depth + 1));
            }
            return result;
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
            return new ArrayList<>();
        }

        if (obj instanceof Collection<?> coll) {
            if (coll.isEmpty()) return new ArrayList<>();

            Object first = coll.iterator().next();

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