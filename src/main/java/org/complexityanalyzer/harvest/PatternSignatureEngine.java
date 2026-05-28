/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
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

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class PatternSignatureEngine {
    private static final ConcurrentHashMap<Class<?>, ClassProfile> PROFILE_CACHE = new ConcurrentHashMap<>(512);

    public enum DetectionLevel {
        SIGNATURE,
        HEURISTIC,
        BEHAVIORAL,
        UNKNOWN
    }

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
            ObjectList<String> interfaces,
            ObjectList<String> evidence
    ) {
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

    private PatternSignatureEngine() {
    }

    public static ClassProfile profile(Class<?> clazz) {
        if (clazz == null) throw new IllegalArgumentException("class is null");
        if (TerminalTypeRegistry.isTerminalType(clazz)) return new ClassProfile(
                clazz.getName().replace('.', '/'), clazz, DetectionLevel.UNKNOWN,
                0, 0, 0,
                false, false, false,
                0, 0, 0, 0,
                0, 0, 0,
                0, 0, 0,
                it.unimi.dsi.fastutil.objects.ObjectLists.emptyList(),
                it.unimi.dsi.fastutil.objects.ObjectLists.emptyList()
        );
        ClassProfile existing = PROFILE_CACHE.get(clazz);
        if (existing != null) return existing;
        ClassProfile result = buildProfile(clazz);
        PROFILE_CACHE.put(clazz, result);
        return result;
    }

    public static void clearCache() {
        PROFILE_CACHE.clear();
    }

    private static ClassProfile buildProfile(Class<?> clazz) {
        String className = clazz.getName().replace('.', '/');
        ObjectList<String> evidence = new ObjectArrayList<>();
        ObjectList<String> interfaces = new ObjectArrayList<>();
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
                    default -> {
                    }
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
                            default -> {
                            }
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
                default -> {
                }
            }
            Class<?> inner = UniversalTypeResolver.extractInnerType(m);
            if (inner != null) {
                UniversalTypeResolver.ResolvedType innerType = UniversalTypeResolver.resolve(inner);
                switch (innerType.kind()) {
                    case ITEM_STACK -> itemStackMethods++;
                    case INGREDIENT -> ingredientMethods++;
                    case FLUID_STACK -> fluidStackMethods++;
                    default -> {
                    }
                }
            }
            if (returnType.getName().contains("Codec") || returnType.getName().contains("MapCodec")) codecRefs++;
        }
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