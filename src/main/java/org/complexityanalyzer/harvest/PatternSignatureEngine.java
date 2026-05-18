package org.complexityanalyzer.harvest;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Антивирусный движок сигнатурного анализа классов.
 * Три уровня детекта:
 * 1. SIGNATURE — точное совпадение по интерфейсам/наследованию
 * 2. HEURISTIC — scoring по composition (поля, методы, паттерны)
 * 3. BEHAVIORAL — анализ call-graph (см. {@link AntivirusStyleDetector})

 * Не содержит хардкода имён модов, пакетов или классов.
 * Вся классификация — через Class.isAssignableFrom() и composition analysis.
 */
public final class PatternSignatureEngine {
    private static final ConcurrentHashMap<Class<?>, ClassProfile> PROFILE_CACHE = new ConcurrentHashMap<>(512);
    /**
     * Уровень детекта.
     */
    public enum DetectionLevel {
        /** implements Recipe<?> или известный интерфейс */
        SIGNATURE,
        /** Scoring по composition > threshold */
        HEURISTIC,
        /** Call-graph подтверждает использование как рецепта */
        BEHAVIORAL,
        /** Не удалось определить */
        UNKNOWN
    }
    /**
     * Профиль класса — полная сигнатура для детекта.
     */
    public record ClassProfile(
            String className,
            Class<?> clazz,
            DetectionLevel level,
            int signatureScore,
            int heuristicScore,
            int totalScore,
            boolean isRecipe,
            boolean isMachine,
            boolean isCodec,
            int itemStackFields,
            int ingredientFields,
            int fluidStackFields,
            int collectionFields,
            int itemStackMethods,
            int ingredientMethods,
            int fluidStackMethods,
            int codecRefs,
            int resourceIdFields,
            int tagFields,
            List<String> interfaces,
            List<String> evidence
    ) {
        public boolean isRelevant() {
            return isRecipe || isMachine || isCodec || totalScore > 20;
        }
        @Override
        public @NotNull String toString() {
            return String.format(Locale.ROOT,
                    "%s score=%d recipe=%s machine=%s codec=%s level=%s itemF=%d ingrF=%d fluidF=%d itemM=%d ingrM=%d fluidM=%d codec=%d",
                    className, totalScore, isRecipe, isMachine, isCodec, level,
                    itemStackFields, ingredientFields, fluidStackFields,
                    itemStackMethods, ingredientMethods, fluidStackMethods, codecRefs);
        }
    }
    private static final int RECIPE_THRESHOLD = 25;
    private static final int MACHINE_THRESHOLD = 30;
    private static final int CODEC_THRESHOLD = 10;
    private PatternSignatureEngine() {}
    // ======================== Публичный API ========================
    /**
     * Построить полный профиль класса (тяжёлая операция, кэшируется).
     */
    public static ClassProfile profile(Class<?> clazz) {
        if (clazz == null) throw new IllegalArgumentException("class is null");
        ClassProfile existing = PROFILE_CACHE.get(clazz);
        if (existing != null) return existing;
        ClassProfile result = buildProfile(clazz);
        PROFILE_CACHE.put(clazz, result);
        return result;
    }
    /**
     * Очистить кэш.
     */
    public static void clearCache() {
        PROFILE_CACHE.clear();
    }
    /**
     * Создать ClassProfile для already-analyzed bytecode класса
     * (используется в StructuralBytecodeHarvester).
     */
    public static ClassProfile fromStructuralData(
            String className,
            int stackFields, int ingredientFields, int fluidFields,
            int resourceFields, int tagFields, int collectionFields,
            int stackCreations, int ingredientCreations, int fluidCreations,
            int codecRefs,
            boolean recipeLike, boolean machineLike, boolean codecLike
    ) {
        int heuristicScore = stackFields + ingredientFields * 2 + fluidFields
                + resourceFields + tagFields + collectionFields
                + stackCreations + ingredientCreations * 2 + fluidCreations + codecRefs;
        return new ClassProfile(
                className, null,
                recipeLike ? DetectionLevel.HEURISTIC : (heuristicScore > 20 ? DetectionLevel.HEURISTIC : DetectionLevel.UNKNOWN),
                0, heuristicScore, heuristicScore,
                recipeLike, machineLike, codecLike,
                stackFields, ingredientFields, fluidFields, collectionFields,
                stackCreations, ingredientCreations, fluidCreations,
                codecRefs, resourceFields, tagFields,
                List.of(), List.of()
        );
    }
    // ======================== Приватные методы ========================
    private static ClassProfile buildProfile(Class<?> clazz) {
        String className = clazz.getName().replace('.', '/');
        var evidence = new ArrayList<String>();
        var interfaces = new ArrayList<String>();
        // === УРОВЕНЬ 1: SIGNATURE ===
        int signatureScore = 0;
        if (Recipe.class.isAssignableFrom(clazz)) {
            signatureScore = 100;
            evidence.add("SIGNATURE: implements Recipe<?>");
            interfaces.add("Recipe<?>");
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            String name = iface.getSimpleName();
            if (!name.startsWith("I") && !name.endsWith("able")) continue;
            interfaces.add(iface.getName().replace('/', '.'));
        }
        // === УРОВЕНЬ 2: HEURISTIC (composition scoring) ===
        int itemStackFields = 0, ingredientFields = 0, fluidStackFields = 0;
        int collectionFields = 0, resourceIdFields = 0, tagFields = 0;
        int itemStackMethods = 0, ingredientMethods = 0, fluidStackMethods = 0;
        int codecRefs = 0;
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (Field f : scan.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                UniversalTypeResolver.ResolvedType fieldType = UniversalTypeResolver.resolve(f.getType());
                final String e = "field " + f.getName() + ": " + f.getType().getSimpleName();
                switch (fieldType.kind()) {
                    case ITEM_STACK -> {
                        itemStackFields++;
                        evidence.add(e);
                    }
                    case INGREDIENT -> {
                        ingredientFields++;
                        evidence.add(e);
                    }
                    case FLUID_STACK -> {
                        fluidStackFields++;
                        evidence.add(e);
                    }
                    case RESOURCE_ID -> resourceIdFields++;
                    case TAG -> tagFields++;
                    default -> {}
                }
                if (fieldType.isCollection() || UniversalTypeResolver.isContainerType(f.getType())) {
                    collectionFields++;
                    Class<?> inner = UniversalTypeResolver.extractInnerType(f);
                    if (inner != null) {
                        UniversalTypeResolver.ResolvedType innerType = UniversalTypeResolver.resolve(inner);
                        switch (innerType.kind()) {
                            case ITEM_STACK -> itemStackFields += 2;
                            case INGREDIENT -> ingredientFields += 2;
                            case FLUID_STACK -> fluidStackFields += 2;
                            default -> {}
                        }
                    }
                }
                String fieldTypeName = f.getType().getName();
                if (fieldTypeName.contains("Codec") || fieldTypeName.contains("MapCodec")) {
                    codecRefs++;
                }
            }
            scan = scan.getSuperclass();
        }
        for (Method m : clazz.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 1) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String methodName = m.getName();
            Class<?> returnType = m.getReturnType();
            UniversalTypeResolver.ResolvedType resolvedReturn = UniversalTypeResolver.resolve(returnType);
            switch (resolvedReturn.kind()) {
                case ITEM_STACK -> {
                    itemStackMethods++;
                    evidence.add("method " + methodName + "() → ItemStack");
                }
                case INGREDIENT -> {
                    ingredientMethods++;
                    evidence.add("method " + methodName + "() → Ingredient");
                }
                case FLUID_STACK -> {
                    fluidStackMethods++;
                    evidence.add("method " + methodName + "() → FluidStack");
                }
                default -> {}
            }
            Class<?> inner = UniversalTypeResolver.extractInnerType(m);
            if (inner != null) {
                UniversalTypeResolver.ResolvedType innerType = UniversalTypeResolver.resolve(inner);
                switch (innerType.kind()) {
                    case ITEM_STACK -> itemStackMethods++;
                    case INGREDIENT -> ingredientMethods++;
                    case FLUID_STACK -> fluidStackMethods++;
                    default -> {}
                }
            }
            if (returnType.getName().contains("Codec") || returnType.getName().contains("MapCodec")) {
                codecRefs++;
            }
        }
        // === SCORING ===
        int heuristicScore = 0;
        heuristicScore += ingredientFields * 10;
        heuristicScore += ingredientMethods * 8;
        heuristicScore += itemStackFields * 5;
        heuristicScore += itemStackMethods * 4;
        heuristicScore += fluidStackFields * 5;
        heuristicScore += fluidStackMethods * 4;
        heuristicScore += collectionFields * 2;
        heuristicScore += codecRefs * 3;
        heuristicScore += resourceIdFields * 2;
        heuristicScore += tagFields * 2;
        int totalScore = signatureScore + heuristicScore;
        DetectionLevel level;
        if (signatureScore >= 100) {
            level = DetectionLevel.SIGNATURE;
        } else if (heuristicScore >= RECIPE_THRESHOLD) {
            level = DetectionLevel.HEURISTIC;
        } else {
            level = DetectionLevel.UNKNOWN;
        }

        boolean isRecipe = (signatureScore >= 100)
                || (ingredientFields + ingredientMethods > 0
                && (itemStackFields + itemStackMethods + fluidStackFields + fluidStackMethods) > 0);
        boolean isMachine = !isRecipe
                && (itemStackFields + fluidStackFields > 0)
                && collectionFields > 2
                && heuristicScore >= MACHINE_THRESHOLD;
        boolean isCodec = codecRefs > 0 && heuristicScore >= CODEC_THRESHOLD;
        return new ClassProfile(
                className, clazz, level,
                signatureScore, heuristicScore, totalScore,
                isRecipe, isMachine, isCodec,
                itemStackFields, ingredientFields, fluidStackFields, collectionFields,
                itemStackMethods, ingredientMethods, fluidStackMethods,
                codecRefs, resourceIdFields, tagFields,
                interfaces, evidence
        );
    }
}