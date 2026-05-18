package org.complexityanalyzer.harvest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полностью динамический определитель типов без хардкода имён пакетов.
 * Замена {@link StructuralTypeClassifier}.
 * Принцип работы как у антивируса:
 * 1. Interface-based: implements Recipe, extends ItemStack, etc.
 * 2. Field composition: поля типа ItemStack, Ingredient, FluidStack
 * 3. Method return type: возвращает ItemStack, Ingredient, FluidStack
 * 4. Inheritance chain: цепочка extends/implements
 * 5. Generic type resolution: List<ItemStack>, Map<String, Ingredient>
 * Не использует строковые имена пакетов — детектит через
 * Class.isAssignableFrom(), instanceof-подобные проверки.
 */
public final class UniversalTypeResolver {

    private static final ConcurrentHashMap<Class<?>, ResolvedType> TYPE_CACHE = new ConcurrentHashMap<>(512);

    /**
     * Полное описание типа с confidence score.
     */
    public enum Kind {
        /**
         * Прямой или косвенный ItemStack
         */
        ITEM_STACK,
        /**
         * Прямой или косвенный Ingredient
         */
        INGREDIENT,
        /**
         * Прямой или косвенный FluidStack
         */
        FLUID_STACK,
        /**
         * ResourceLocation или подобный идентификатор
         */
        RESOURCE_ID,
        /**
         * TagKey
         */
        TAG,
        /**
         * DataComponentType
         */
        DATA_COMPONENT,
        /**
         * Числовой тип (int, Integer, long, etc.)
         */
        NUMBER,
        /**
         * Коллекция (List, Set, Map, array, etc.)
         */
        COLLECTION,
        /**
         * Неизвестный/неклассифицированный
         */
        UNKNOWN
    }

    public record ResolvedType(
            Kind kind,
            boolean isCollection,
            boolean isWrapper,
            Class<?> innerType,
            int confidence,
            List<String> evidence
    ) {
        public static final ResolvedType UNKNOWN_TYPE = new ResolvedType(
                Kind.UNKNOWN, false, false, null, 0, List.of()
        );

        public static final ResolvedType NUMBER_TYPE = new ResolvedType(
                Kind.NUMBER, false, false, null, 90, List.of("Primitive or boxed number")
        );

        @Override
        public @NotNull String toString() {
            return kind + (isCollection ? "_LIST" : "") + (isWrapper ? "_WRAPPER" : "")
                    + (innerType != null ? "<" + innerType.getSimpleName() + ">" : "")
                    + " conf=" + confidence;
        }
    }

    private UniversalTypeResolver() {
    }

    // ======================== Публичный API ========================

    /**
     * Классифицировать класс.
     */
    public static ResolvedType resolve(Class<?> clazz) {
        if (clazz == null) return ResolvedType.UNKNOWN_TYPE;
        ResolvedType existing = TYPE_CACHE.get(clazz);
        if (existing != null) return existing;
        // Placeholder to break recursion in resolveUncached → resolve() cycle
        TYPE_CACHE.put(clazz, ResolvedType.UNKNOWN_TYPE);
        ResolvedType result = resolveUncached(clazz);
        TYPE_CACHE.put(clazz, result);
        return result;
    }


    /**
     * Является ли тип контейнером (List, Set, Map, array, Iterable, record).
     */
    public static boolean isContainerType(Class<?> type) {
        if (type == null) return false;
        return type.isArray()
                || Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || type.isRecord();
    }

    /**
     * Является ли тип терминальным (не нужно сканировать его поля).
     */
    public static boolean isTerminalType(Class<?> type) {
        if (type == null
                || Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class
                || type.isPrimitive() || type.isEnum() || type == String.class) return true;
        return type.getName().startsWith("java.lang.invoke.") || type.getName().startsWith("java.lang.reflect.");
    }

    /**
     * Извлечь внутренний тип коллекции (если List<ItemStack> → ItemStack).
     */
    public static Class<?> extractInnerType(Field field) {
        try {
            return extractInnerTypeFromGeneric(field.getGenericType());
        } catch (TypeNotPresentException | NoClassDefFoundError e) {
            return null;
        }
    }

    /**
     * Извлечь внутренний тип из возвращаемого типа метода.
     */
    public static Class<?> extractInnerType(Method method) {
        try {
            return extractInnerTypeFromGeneric(method.getGenericReturnType());
        } catch (TypeNotPresentException | NoClassDefFoundError e) {
            return null;
        }
    }

    private static Class<?> extractInnerTypeFromGeneric(Type generic) {
        if (generic instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> c) {
                return c;
            }
        }
        if (generic instanceof Class<?> c && c.isArray()) {
            return c.getComponentType();
        }
        return null;
    }

    /**
     * Очистить кэш.
     */
    public static void clearCache() {
        TYPE_CACHE.clear();
    }

    // ======================== Приватные методы ========================

    private static ResolvedType resolveUncached(Class<?> clazz) {
        var evidence = new ArrayList<String>();

        // ---- Шаг 1: Interface-based detection ----
        if (ItemStack.class.isAssignableFrom(clazz)) {
            return new ResolvedType(Kind.ITEM_STACK, false, false, null, 100,
                    List.of("extends/implements ItemStack"));
        }
        if (Ingredient.class.isAssignableFrom(clazz)) {
            return new ResolvedType(Kind.INGREDIENT, false, false, null, 100,
                    List.of("extends/implements Ingredient"));
        }
        if (FluidStack.class.isAssignableFrom(clazz)) {
            return new ResolvedType(Kind.FLUID_STACK, false, false, null, 100,
                    List.of("extends/implements FluidStack"));
        }

        if (Recipe.class.isAssignableFrom(clazz)) {
            evidence.add("implements Recipe<?>");
        }

        // ---- Шаг 2: Composition analysis (сканируем ВСЕ поля включая родительские) ----
        int itemFields = 0, ingrFields = 0, fluidFields = 0;
        int itemMethods = 0, ingrMethods = 0, fluidMethods = 0;
        boolean hasWrapper = false;

        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (Field f : scan.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;

                ResolvedType fieldType = resolve(f.getType());
                String fieldDesc = "field " + f.getName() + ": " + f.getType().getSimpleName();

                switch (fieldType.kind()) {
                    case ITEM_STACK -> {
                        itemFields++;
                        evidence.add(fieldDesc);
                    }
                    case INGREDIENT -> {
                        ingrFields++;
                        evidence.add(fieldDesc);
                    }
                    case FLUID_STACK -> {
                        fluidFields++;
                        evidence.add(fieldDesc);
                    }
                }

                if (fieldType.isCollection()) {
                    Class<?> inner = extractInnerType(f);
                    if (inner != null) {
                        ResolvedType innerType = resolve(inner);
                        switch (innerType.kind()) {
                            case ITEM_STACK -> itemFields += 2;
                            case INGREDIENT -> ingrFields += 2;
                            case FLUID_STACK -> fluidFields += 2;
                        }
                    }
                } else if (fieldType.isWrapper()) {
                    hasWrapper = true;
                }
            }
            scan = scan.getSuperclass();
        }

        for (Method m : clazz.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;

            ResolvedType returnType = resolve(m.getReturnType());

            switch (returnType.kind()) {
                case ITEM_STACK -> itemMethods++;
                case INGREDIENT -> ingrMethods++;
                case FLUID_STACK -> fluidMethods++;
            }

            try {
                Type genericReturn = m.getGenericReturnType();
                if (genericReturn instanceof ParameterizedType pt) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length > 0 && args[0] instanceof Class<?> innerClass) {
                        ResolvedType inner = resolve(innerClass);
                        switch (inner.kind()) {
                            case ITEM_STACK -> itemMethods++;
                            case INGREDIENT -> ingrMethods++;
                            case FLUID_STACK -> fluidMethods++;
                        }
                    }
                }
            } catch (TypeNotPresentException | NoClassDefFoundError ignored) {
            }
        }

        // ---- Шаг 3: Вычисление confidence score ----
        int score = 0;
        if (!evidence.isEmpty() && "implements Recipe<?>".equals(evidence.getFirst())) {
            score += 50;
        }
        score += itemFields * 8;
        score += ingrFields * 10;
        score += fluidFields * 8;
        score += itemMethods * 5;
        score += ingrMethods * 7;
        score += fluidMethods * 5;
        score = Math.min(score, 100);

        if (ingrFields > 0 || ingrMethods > 0) {
            return new ResolvedType(Kind.INGREDIENT, false, hasWrapper, null, score, evidence);
        }
        if (itemFields > 0 || itemMethods > 0) {
            return new ResolvedType(Kind.ITEM_STACK, false, hasWrapper, null, score, evidence);
        }
        if (fluidFields > 0 || fluidMethods > 0) {
            return new ResolvedType(Kind.FLUID_STACK, false, hasWrapper, null, score, evidence);
        }

        if (isContainerType(clazz)) {
            return new ResolvedType(Kind.COLLECTION, true, false, null, 30, evidence);
        }

        if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)) {
            return ResolvedType.NUMBER_TYPE;
        }

        return new ResolvedType(Kind.UNKNOWN, false, false, null, 0, evidence);
    }
}
