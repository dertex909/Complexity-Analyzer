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
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UniversalTypeResolver {

    private static final ConcurrentHashMap<Class<?>, ResolvedType> TYPE_CACHE = new ConcurrentHashMap<>(512);

    public enum Kind {
        ITEM_STACK, INGREDIENT, FLUID_STACK, RESOURCE_ID, TAG, DATA_COMPONENT, NUMBER, COLLECTION, UNKNOWN
    }

    public record ResolvedType(
            Kind kind,
            boolean isCollection,
            boolean isWrapper,
            Class<?> innerType,
            int confidence,
            ObjectList<String> evidence
    ) {
        public static final ResolvedType UNKNOWN_TYPE = new ResolvedType(
                Kind.UNKNOWN, false, false, null, 0, ObjectLists.emptyList()
        );

        public static final ResolvedType NUMBER_TYPE = new ResolvedType(
                Kind.NUMBER, false, false, null, 90, ObjectLists.singleton("Primitive or boxed number")
        );

        @Override
        public @NotNull String toString() {
            return kind + (isCollection ? "_LIST" : "") + (isWrapper ? "_WRAPPER" : "")
                    + (innerType != null ? "<" + innerType.getSimpleName() + ">" : "") + " conf=" + confidence;
        }
    }

    private UniversalTypeResolver() {
    }

    public static ResolvedType resolve(Class<?> clazz) {
        if (clazz == null) return ResolvedType.UNKNOWN_TYPE;
        if (isTerminalType(clazz)) {
            if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)) return ResolvedType.NUMBER_TYPE;
            return ResolvedType.UNKNOWN_TYPE;
        }
        ResolvedType existing = TYPE_CACHE.get(clazz);
        if (existing != null) return existing;
        TYPE_CACHE.put(clazz, ResolvedType.UNKNOWN_TYPE);
        ResolvedType result = resolveUncached(clazz);
        TYPE_CACHE.put(clazz, result);
        return result;
    }

    public static boolean isContainerType(Class<?> type) {
        if (type == null) return false;
        return type.isArray() || Iterable.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type) || type.isRecord();
    }

    public static boolean isTerminalType(Class<?> type) {
        return TerminalTypeRegistry.isTerminalType(type);
    }

    public static Class<?> extractInnerType(Field field) {
        try {
            return extractInnerTypeFromGeneric(field.getGenericType());
        } catch (TypeNotPresentException | NoClassDefFoundError e) {
            return null;
        }
    }

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
            if (args.length > 0 && args[0] instanceof Class<?> c) return c;
        }
        if (generic instanceof Class<?> c && c.isArray()) return c.getComponentType();
        return null;
    }

    public static void clearCache() {
        TYPE_CACHE.clear();
    }

    private static ResolvedType resolveUncached(Class<?> clazz) {
        var evidence = new ObjectArrayList<String>();

        if (ItemStack.class.isAssignableFrom(clazz)) {
            return new ResolvedType(Kind.ITEM_STACK, false, false, null, 100,
                    ObjectLists.singleton("extends/implements ItemStack"));
        }
        if (Ingredient.class.isAssignableFrom(clazz)) {
            return new ResolvedType(Kind.INGREDIENT, false, false, null, 100,
                    ObjectLists.singleton("extends/implements Ingredient"));
        }
        if (FluidStack.class.isAssignableFrom(clazz)) {
            return new ResolvedType(Kind.FLUID_STACK, false, false, null, 100,
                    ObjectLists.singleton("extends/implements FluidStack"));
        }

        evidence.add("Dynamic reflection evaluation");

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

        int score = 0;
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