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

package org.complexityanalyzer.harvest.machine;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.complexityanalyzer.harvest.debug.MachineRegistryDebugLogger;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

public final class MachineReflectionScanner {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodInfo[] EMPTY_METHOD_INFOS = new MethodInfo[0];
    private static final FieldInfo[] EMPTY_FIELD_INFOS = new FieldInfo[0];
    private static final ClassInfo EMPTY_INFO = new ClassInfo(EMPTY_METHOD_INFOS, EMPTY_FIELD_INFOS, EMPTY_FIELD_INFOS);
    private final Object2ObjectMap<Class<?>, ClassInfo> classInfoCache = new Object2ObjectOpenHashMap<>();
    private final BiPredicate<RecipeType<?>, Item> registrar;

    public MachineReflectionScanner(BiPredicate<RecipeType<?>, Item> registrar) {
        this.registrar = registrar;
    }

    private static boolean isComplexObject(@Nullable Object obj) {
        if (obj == null) return false;
        var c = obj.getClass();
        return MachineTypeUnwrapper.curClsValid(c) && !c.isEnum();
    }

    private static boolean isCandidateMethodName(String name) {
        if (name.length() < 3) return false;
        return switch (name.charAt(0)) {
            case 'g' -> name.startsWith("get");
            case 'r' -> name.startsWith("recipe");
            case 't' -> name.startsWith("type");
            default -> false;
        };
    }

    public int scanBlockEntityInstance(BlockEntity be, Item machineItem, MachineRegistryDebugLogger logger) {
        int count = 0;
        var beClass = be.getClass();
        var info = classInfo(beClass);

        logger.logBeMethodScanStart(beClass);
        for (var mInfo : info.recipeMethods()) {
            try {
                var raw = mInfo.handle().invoke(be);
                var recipeType = MachineTypeUnwrapper.unwrapRecipeType(raw);
                boolean matched = false;
                if (recipeType != null && registrar.test(recipeType, machineItem)) {
                    matched = true;
                    count++;
                }
                logger.logBeMethod(mInfo.method(), raw, recipeType, matched, null);
            } catch (Throwable t) {
                logger.logBeMethod(mInfo.method(), null, null, false, t);
            }
        }

        logger.logBeHierarchyClass(beClass);
        for (var fInfo : info.instanceFields()) {
            try {
                var val = fInfo.handle().invoke(be);
                var recipeType = MachineTypeUnwrapper.unwrapRecipeType(val);
                boolean matched = false;
                if (recipeType != null && registrar.test(recipeType, machineItem)) {
                    matched = true;
                    count++;
                }
                logger.logBeField(fInfo.field(), val, recipeType, matched, null);
            } catch (Throwable t) {
                logger.logBeField(fInfo.field(), null, null, false, t);
            }
        }

        return count;
    }

    public int scanStaticFieldsOnly(Class<?> clazz, Item machineItem, Object2ObjectMap<Class<?>, ObjectList<RecipeType<?>>> classStaticResults, MachineRegistryDebugLogger logger) {
        var recipeTypes = classStaticResults.get(clazz);
        if (recipeTypes == null) {
            recipeTypes = new ObjectArrayList<>();
            logger.logStaticScanStart(clazz);
            var info = classInfo(clazz);
            for (var fInfo : info.staticFields()) {
                try {
                    var val = fInfo.handle().invoke();
                    var recipeType = MachineTypeUnwrapper.unwrapRecipeType(val);
                    if (recipeType != null && !recipeTypes.contains(recipeType)) recipeTypes.add(recipeType);
                } catch (Throwable ignored) {
                }
            }
            classStaticResults.put(clazz, recipeTypes);
        }

        int count = 0;
        for (var rt : recipeTypes) if (registrar.test(rt, machineItem)) count++;
        return count;
    }

    @Nullable
    public RecipeType<?> findRecipeTypeDeep(Object obj, int depth, ReferenceSet<Object> visited, MachineRegistryDebugLogger logger) {
        if (obj == null || depth > 5 || !visited.add(obj)) return null;

        var directUnwrap = MachineTypeUnwrapper.unwrapRecipeType(obj);
        if (directUnwrap != null) {
            logger.logDeepMatch(obj.getClass(), directUnwrap, depth);
            return directUnwrap;
        }

        if (obj instanceof Iterable<?> coll) {
            int idx = 0;
            for (var item : coll) {
                if (item != null) {
                    logger.logDeepIter(item.getClass(), idx, depth);
                    var res = findRecipeTypeDeep(item, depth + 1, visited, logger);
                    if (res != null) return res;
                }
                idx++;
            }
            return null;
        }

        if (obj instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var val = entry.getValue();
                if (val != null) {
                    logger.logDeepMap(entry.getKey(), val.getClass(), depth);
                    var res = findRecipeTypeDeep(val, depth + 1, visited, logger);
                    if (res != null) return res;
                }
            }
            return null;
        }

        var info = classInfo(obj.getClass());

        for (var mInfo : info.recipeMethods()) {
            try {
                var val = mInfo.handle().invoke(obj);
                if (val == null || val == obj) continue;

                var rt = MachineTypeUnwrapper.unwrapRecipeType(val);
                boolean isComplex = isComplexObject(val);
                logger.logDeepMethod(mInfo.method(), val, rt, null, isComplex, depth);

                if (rt != null) return rt;

                if (isComplex) {
                    var deep = findRecipeTypeDeep(val, depth + 1, visited, logger);
                    if (deep != null) return deep;
                }
            } catch (Throwable t) {
                logger.logDeepMethod(mInfo.method(), null, null, t, false, depth);
            }
        }

        for (var fInfo : info.instanceFields()) {
            try {
                var val = fInfo.handle().invoke(obj);
                if (val == null || val == obj) continue;

                var rt = MachineTypeUnwrapper.unwrapRecipeType(val);
                boolean isComplex = isComplexObject(val);
                logger.logDeepField(fInfo.field(), val, rt, null, isComplex, depth);

                if (rt != null) return rt;

                if (isComplex) {
                    var deep = findRecipeTypeDeep(val, depth + 1, visited, logger);
                    if (deep != null) return deep;
                }
            } catch (Throwable t) {
                logger.logDeepField(fInfo.field(), null, null, t, false, depth);
            }
        }
        return null;
    }

    public ClassInfo classInfo(Class<?> clazz) {
        var info = classInfoCache.get(clazz);
        if (info == null) {
            info = buildClassInfo(clazz);
            classInfoCache.put(clazz, info);
        }
        return info;
    }

    private ClassInfo buildClassInfo(Class<?> clazz) {
        if (!MachineTypeUnwrapper.curClsValid(clazz)) return EMPTY_INFO;

        var methods = new ObjectArrayList<MethodInfo>();
        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() != 0) continue;

            var rt = method.getReturnType();
            if (rt == void.class || rt == Void.class || rt.isPrimitive()) continue;

            boolean isValidType = RecipeType.class.isAssignableFrom(rt) || Supplier.class.isAssignableFrom(rt)
                    || Holder.class.isAssignableFrom(rt) || Optional.class.isAssignableFrom(rt);

            if (isValidType || isCandidateMethodName(method.getName())) try {
                method.setAccessible(true);
                var mh = LOOKUP.unreflect(method);
                methods.add(new MethodInfo(mh, method));
            } catch (Throwable ignored) {
            }
        }

        var instanceFields = new ObjectArrayList<FieldInfo>();
        var staticFields = new ObjectArrayList<FieldInfo>();
        var current = clazz;

        while (MachineTypeUnwrapper.curClsValid(current)) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    var mh = LOOKUP.unreflectGetter(field);
                    if (Modifier.isStatic(field.getModifiers())) {
                        staticFields.add(new FieldInfo(mh, field));
                    } else {
                        instanceFields.add(new FieldInfo(mh, field));
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }

        var methodArray = methods.isEmpty() ? EMPTY_METHOD_INFOS : methods.toArray(new MethodInfo[0]);
        var instFieldArray = instanceFields.isEmpty() ? EMPTY_FIELD_INFOS : instanceFields.toArray(new FieldInfo[0]);
        var staticFieldArray = staticFields.isEmpty() ? EMPTY_FIELD_INFOS : staticFields.toArray(new FieldInfo[0]);

        return new ClassInfo(methodArray, instFieldArray, staticFieldArray);
    }

    public record MethodInfo(MethodHandle handle, Method method) {
    }

    public record FieldInfo(MethodHandle handle, Field field) {
    }

    public record ClassInfo(MethodInfo[] recipeMethods, FieldInfo[] instanceFields, FieldInfo[] staticFields) {
    }
}