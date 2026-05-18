package org.complexityanalyzer.harvest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Детектор SizedIngredient (Ingredient + count) — v2.0.
 * Использует {@link UniversalTypeResolver} для динамического детекта типов.
 */
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
    ) {}

    private static final Set<String> COUNT_NAMES = Set.of(
            "count", "amount", "size", "quantity", "qty",
            "stackSize", "fluidAmount", "mb", "millibuckets",
            "number", "num", "cnt"
    );

    public static SizedDetector getSizedDetector(Class<?> clazz) {
        SizedDetector existing = STRUCTURAL_SIZED_CACHE.get(clazz);
        if (existing != null) return existing;
        SizedDetector result = computeSizedDetector(clazz);
        STRUCTURAL_SIZED_CACHE.put(clazz, result);
        return result;
    }

    private static SizedDetector computeSizedDetector(Class<?> c) {
        if (UniversalTypeResolver.isTerminalType(c)) {
            return new SizedDetector(false, null, null, false);
        }

        Extractor ingredientExtractor = null;
        Extractor countExtractor = null;
        boolean returnsArray = false;

        var meta = UniversalAccessorResolver.getMeta(c);

        for (int i = 0; i < meta.allMethods().length; i++) {
            var m = meta.allMethods()[i];
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

        if (ingredientExtractor == null) {
            for (int i = 0; i < meta.allFields().length; i++) {
                var f = meta.allFields()[i];
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
        }

        if (ingredientExtractor == null) {
            return new SizedDetector(false, null, null, false);
        }

        for (int i = 0; i < meta.allMethods().length; i++) {
            var m = meta.allMethods()[i];
            if (m.getParameterCount() != 0) continue;
            var rt = m.getReturnType();
            if (rt == int.class || rt == Integer.class || rt == long.class || rt == Long.class) {
                if (COUNT_NAMES.contains(m.getName().toLowerCase())) {
                    countExtractor = m::invoke;
                    break;
                }
            }
        }

        if (countExtractor == null) {
            for (int i = 0; i < meta.allFields().length; i++) {
                var f = meta.allFields()[i];
                var type = f.getType();
                if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
                    if (COUNT_NAMES.contains(f.getName().toLowerCase())) {
                        countExtractor = f::get;
                        break;
                    }
                }
            }
        }

        if (countExtractor == null) {
            Field singleIntField = null;
            int intFieldCount = 0;
            for (int i = 0; i < meta.allFields().length; i++) {
                var f = meta.allFields()[i];
                var type = f.getType();
                if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
                    singleIntField = f;
                    intFieldCount++;
                }
            }
            if (intFieldCount == 1 && singleIntField != null) {
                try {
                    singleIntField.setAccessible(true);
                    countExtractor = singleIntField::get;
                } catch (Exception ignored) {}
            }
        }

        if (countExtractor != null) {
            return new SizedDetector(true, ingredientExtractor, countExtractor, returnsArray);
        }

        return new SizedDetector(false, null, null, false);
    }

    public static void clearCache() {
        STRUCTURAL_SIZED_CACHE.clear();
    }
}
