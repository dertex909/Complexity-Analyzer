package org.complexityanalyzer.harvest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SizedIngredientDetector {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ConcurrentHashMap<Class<?>, SizedDetector> STRUCTURAL_SIZED_CACHE = new ConcurrentHashMap<>(256);

    public record SizedDetector(
            boolean isSizedWrapper,
            MethodHandle ingredientExtractor,
            MethodHandle countExtractor,
            boolean returnsArray
    ) {
    }

    public static SizedDetector getSizedDetector(Class<?> clazz) {
        return STRUCTURAL_SIZED_CACHE.computeIfAbsent(clazz, c -> {
            String className = c.getName();
            if (className.startsWith("java.") || className.startsWith("net.minecraft.")
                    || className.startsWith("it.unimi.") || className.startsWith("com.mojang.")) {
                return new SizedDetector(false, null, null, false);
            }

            MethodHandle ingredientExtractor = null;
            MethodHandle countExtractor = null;
            boolean returnsArray = false;

            var meta = RecipeReflection.getMeta(c);

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

            if (ingredientExtractor == null) for (int i = 0; i < meta.fields.length; i++) {
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

            if (ingredientExtractor == null) {
                return new SizedDetector(false, null, null, false);
            }

            Set<String> countNames = Set.of("count", "amount", "size", "quantity", "qty");

            for (int i = 0; i < meta.allMethods.length; i++) {
                var m = meta.allMethods[i];
                if (m.getParameterCount() != 0) continue;
                var rt = m.getReturnType();
                if (rt == int.class || rt == Integer.class) if (countNames.contains(m.getName().toLowerCase())) {
                    countExtractor = meta.allHandles[i];
                    break;
                }
            }

            if (countExtractor == null) for (int i = 0; i < meta.fields.length; i++) {
                var f = meta.fields[i];
                var type = f.getType();
                if (type == int.class || type == Integer.class) if (countNames.contains(f.getName().toLowerCase())) {
                    try {
                        countExtractor = LOOKUP.unreflectGetter(f);
                    } catch (Exception ignored) {
                    }
                    break;
                }
            }

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
                if (intFieldCount == 1) try {
                    countExtractor = LOOKUP.unreflectGetter(singleIntField);
                } catch (Exception ignored) {
                }
            }

            if (countExtractor != null) {
                return new SizedDetector(true, ingredientExtractor, countExtractor, returnsArray);
            }

            return new SizedDetector(false, null, null, false);
        });
    }

    public static void clearCache() {
        STRUCTURAL_SIZED_CACHE.clear();
    }
}