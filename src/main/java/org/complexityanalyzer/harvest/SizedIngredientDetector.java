package org.complexityanalyzer.harvest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SizedIngredientDetector {

    private static final ConcurrentHashMap<Class<?>, SizedDetector> STRUCTURAL_SIZED_CACHE = new ConcurrentHashMap<>(256);

    @FunctionalInterface
    public interface Extractor {
        Object extract(Object obj) throws Throwable;
    }

    public record SizedDetector(
            boolean isSizedWrapper,
            Extractor ingredientExtractor,
            Extractor countExtractor,
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

            Extractor ingredientExtractor = null;
            Extractor countExtractor = null;
            boolean returnsArray = false;

            var meta = RecipeReflection.getMeta(c);

            for (int i = 0; i < meta.allMethods.length; i++) {
                var m = meta.allMethods[i];
                if (m.getParameterCount() != 0) continue;
                var rt = m.getReturnType();
                if (rt == Ingredient.class) {
                    ingredientExtractor = m::invoke;
                    break;
                } else if (rt == ItemStack[].class) {
                    ingredientExtractor = m::invoke;
                    returnsArray = true;
                    break;
                }
            }

            if (ingredientExtractor == null) for (int i = 0; i < meta.fields.length; i++) {
                var f = meta.fields[i];
                var type = f.getType();
                if (type == Ingredient.class) {
                    ingredientExtractor = f::get;
                    break;
                } else if (type == ItemStack[].class) {
                    ingredientExtractor = f::get;
                    returnsArray = true;
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
                    countExtractor = m::invoke;
                    break;
                }
            }

            if (countExtractor == null) for (int i = 0; i < meta.fields.length; i++) {
                var f = meta.fields[i];
                var type = f.getType();
                if (type == int.class || type == Integer.class) if (countNames.contains(f.getName().toLowerCase())) {
                    countExtractor = f::get;
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
                if (intFieldCount == 1) {
                    countExtractor = singleIntField::get;
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