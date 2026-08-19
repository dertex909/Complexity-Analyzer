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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest.inspector;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

public final class PatternSignatureEngine {
    private static final ConcurrentHashMap<Class<?>, ClassProfile> PROFILE_CACHE = new ConcurrentHashMap<>(512);
    private static final int RECIPE_THRESHOLD = 25;
    private static final int MACHINE_THRESHOLD = 30;
    private static final int CODEC_THRESHOLD = 10;

    private PatternSignatureEngine() {
    }

    public static ClassProfile profile(Class<?> clazz) {
        if (clazz == null) throw new IllegalArgumentException("class is null");
        var existing = PROFILE_CACHE.get(clazz);
        if (existing != null) return existing;
        return PROFILE_CACHE.computeIfAbsent(clazz, PatternSignatureEngine::buildProfile);
    }

    public static void clearCache() {
        PROFILE_CACHE.clear();
    }

    private static boolean isCodecType(Class<?> type) {
        if (type == null) return false;
        return Codec.class.isAssignableFrom(type) || MapCodec.class.isAssignableFrom(type) || StreamCodec.class.isAssignableFrom(type);
    }

    private static ClassProfile buildProfile(Class<?> clazz) {
        String className = clazz.getName().replace('.', '/');

        if (TerminalTypeRegistry.isTerminalType(clazz)) return new ClassProfile(
                className, clazz, DetectionLevel.UNKNOWN,
                0, 0, 0,
                false, false, false,
                0, 0, 0, 0,
                0, 0, 0,
                0, 0, 0,
                ObjectLists.emptyList(), ObjectLists.emptyList()
        );

        var evidence = new ObjectArrayList<String>();
        var interfaces = new ObjectArrayList<String>();
        int signatureScore = 0;

        if (Recipe.class.isAssignableFrom(clazz)) {
            signatureScore = 100;
            evidence.add("SIGNATURE: implements Recipe<?>");
            interfaces.add("Recipe<?>");
        }

        var ifaces = clazz.getInterfaces();
        Arrays.sort(ifaces, Comparator.comparing(Class::getName));
        for (var iface : ifaces) {
            String name = iface.getName();
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")) continue;
            if (iface == Recipe.class) continue;
            interfaces.add(name);
        }

        int itemStackFields = 0, ingredientFields = 0, fluidStackFields = 0;
        int collectionFields = 0, resourceIdFields = 0, tagFields = 0;
        int itemStackMethods = 0, ingredientMethods = 0, fluidStackMethods = 0;
        int codecRefs = 0;
        var scan = clazz;

        while (scan != null && scan != Object.class) {
            var fields = scan.getDeclaredFields();
            Arrays.sort(fields, Comparator.comparing(Field::getName));

            for (var f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                var fieldType = UniversalTypeResolver.resolve(f.getType());
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
                    var inner = UniversalTypeResolver.extractInnerType(f);
                    if (inner != null) {
                        var innerType = UniversalTypeResolver.resolve(inner);
                        switch (innerType.kind()) {
                            case ITEM_STACK -> itemStackFields += 2;
                            case INGREDIENT -> ingredientFields += 2;
                            case FLUID_STACK -> fluidStackFields += 2;
                            default -> {
                            }
                        }
                    }
                }
                if (isCodecType(f.getType())) codecRefs++;
            }
            scan = scan.getSuperclass();
        }

        var methods = clazz.getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName)
                .thenComparingInt(Method::getParameterCount)
                .thenComparing(m -> Arrays.toString(m.getParameterTypes()))
                .thenComparing(m -> m.getReturnType().getName()));

        for (var m : methods) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 1) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String methodName = m.getName();
            var returnType = m.getReturnType();
            var resolvedReturn = UniversalTypeResolver.resolve(returnType);
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
            var inner = UniversalTypeResolver.extractInnerType(m);
            if (inner != null) {
                var innerType = UniversalTypeResolver.resolve(inner);
                switch (innerType.kind()) {
                    case ITEM_STACK -> itemStackMethods++;
                    case INGREDIENT -> ingredientMethods++;
                    case FLUID_STACK -> fluidStackMethods++;
                    default -> {
                    }
                }
            }
            if (isCodecType(returnType)) codecRefs++;
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

        DetectionLevel level;
        if (signatureScore >= 100) {
            level = DetectionLevel.SIGNATURE;
        } else if (heuristicScore >= RECIPE_THRESHOLD) {
            level = DetectionLevel.HEURISTIC;
        } else {
            level = DetectionLevel.UNKNOWN;
        }

        boolean isRecipe = (signatureScore >= 100) || (ingredientFields + ingredientMethods > 0 && (itemStackFields + itemStackMethods + fluidStackFields + fluidStackMethods) > 0);
        boolean isMachine = !isRecipe && (itemStackFields + fluidStackFields > 0) && collectionFields > 2 && heuristicScore >= MACHINE_THRESHOLD;
        boolean isCodec = codecRefs > 0 && heuristicScore >= CODEC_THRESHOLD;

        return new ClassProfile(
                className, clazz, level,
                signatureScore, heuristicScore, signatureScore + heuristicScore,
                isRecipe, isMachine, isCodec,
                itemStackFields, ingredientFields, fluidStackFields, collectionFields,
                itemStackMethods, ingredientMethods, fluidStackMethods,
                codecRefs, resourceIdFields, tagFields,
                interfaces, evidence
        );
    }

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
            return className + " score=" + totalScore + " recipe=" + isRecipe + " machine=" + isMachine + " codec=" + isCodec + " level=" + level +
                    " itemF=" + itemStackFields + " ingrF=" + ingredientFields + " fluidF=" + fluidStackFields +
                    " itemM=" + itemStackMethods + " ingrM=" + ingredientMethods + " fluidM=" + fluidStackMethods + " codec=" + codecRefs;
        }
    }
}