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
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

public final class MachineReflectionScanner {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MemberInfo[] EMPTY_MEMBERS = new MemberInfo[0];
    private static final ClassInfo EMPTY_INFO = new ClassInfo(EMPTY_MEMBERS, EMPTY_MEMBERS, EMPTY_MEMBERS, EMPTY_MEMBERS);

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
        if (name == null || name.length() < 3) return false;
        return name.startsWith("get") || name.startsWith("recipe") || name.startsWith("type");
    }

    private static void logScan(MachineRegistryDebugLogger logger, Member member, @Nullable Object raw, @Nullable RecipeType<?> rt, boolean matched, @Nullable Throwable t) {
        if (member instanceof Method m) logger.logBeMethod(m, raw, rt, matched, t);
        else if (member instanceof Field f) logger.logBeField(f, raw, rt, matched, t);
    }

    private static void logDeep(MachineRegistryDebugLogger logger, Member member, @Nullable Object val, @Nullable RecipeType<?> rt, boolean isComplex, int depth, @Nullable Throwable t) {
        if (member instanceof Method m) logger.logDeepMethod(m, val, rt, t, isComplex, depth);
        else if (member instanceof Field f) logger.logDeepField(f, val, rt, t, isComplex, depth);
    }

    private int scanBeMember(MemberInfo info, BlockEntity be, Item machineItem, MachineRegistryDebugLogger logger) {
        try {
            var raw = info.extract(be);
            var recipeType = MachineTypeUnwrapper.unwrapRecipeType(raw);
            boolean matched = recipeType != null && registrar.test(recipeType, machineItem);
            logScan(logger, info.member(), raw, recipeType, matched, null);
            return matched ? 1 : 0;
        } catch (Throwable t) {
            logScan(logger, info.member(), null, null, false, t);
            return 0;
        }
    }

    public int scanBlockEntityInstance(BlockEntity be, Item machineItem, MachineRegistryDebugLogger logger) {
        int count = 0;
        var beClass = be.getClass();
        var info = classInfo(beClass);

        logger.logBeMethodScanStart(beClass);
        for (var m : info.recipeMethods()) count += scanBeMember(m, be, machineItem, logger);

        logger.logBeHierarchyClass(beClass);
        for (var f : info.instanceFields()) count += scanBeMember(f, be, machineItem, logger);

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
                    var val = fInfo.extractStatic();
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

        for (var accessor : info.allDeepAccessors()) {
            try {
                var val = accessor.extract(obj);
                if (val == null || val == obj) continue;

                var rt = MachineTypeUnwrapper.unwrapRecipeType(val);
                boolean isComplex = isComplexObject(val);
                logDeep(logger, accessor.member(), val, rt, isComplex, depth, null);
                if (rt != null) return rt;

                if (isComplex) {
                    var deep = findRecipeTypeDeep(val, depth + 1, visited, logger);
                    if (deep != null) return deep;
                }
            } catch (Throwable t) {
                logDeep(logger, accessor.member(), null, null, false, depth, t);
            }
        }
        return null;
    }

    private ClassInfo classInfo(Class<?> clazz) {
        var info = classInfoCache.get(clazz);
        if (info == null) {
            info = buildClassInfo(clazz);
            classInfoCache.put(clazz, info);
        }
        return info;
    }

    private ClassInfo buildClassInfo(Class<?> clazz) {
        if (!MachineTypeUnwrapper.curClsValid(clazz)) return EMPTY_INFO;

        var methods = new ObjectArrayList<MemberInfo>();
        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() != 0) continue;

            var rt = method.getReturnType();
            if (rt == void.class || rt == Void.class || rt.isPrimitive()) continue;

            boolean isValidType = RecipeType.class.isAssignableFrom(rt) || Supplier.class.isAssignableFrom(rt)
                    || Holder.class.isAssignableFrom(rt) || Optional.class.isAssignableFrom(rt);

            if (isValidType || isCandidateMethodName(method.getName())) try {
                method.setAccessible(true);
                var mh = LOOKUP.unreflect(method);
                methods.add(new MemberInfo(mh, method));
            } catch (Throwable ignored) {
            }
        }

        var instanceFields = new ObjectArrayList<MemberInfo>();
        var staticFields = new ObjectArrayList<MemberInfo>();
        var current = clazz;

        while (MachineTypeUnwrapper.curClsValid(current)) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    var mh = LOOKUP.unreflectGetter(field);
                    if (Modifier.isStatic(field.getModifiers())) {
                        staticFields.add(new MemberInfo(mh, field));
                    } else {
                        instanceFields.add(new MemberInfo(mh, field));
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }

        var methodArray = methods.isEmpty() ? EMPTY_MEMBERS : methods.toArray(new MemberInfo[0]);
        var instFieldArray = instanceFields.isEmpty() ? EMPTY_MEMBERS : instanceFields.toArray(new MemberInfo[0]);
        var staticFieldArray = staticFields.isEmpty() ? EMPTY_MEMBERS : staticFields.toArray(new MemberInfo[0]);

        var allDeep = new MemberInfo[methodArray.length + instFieldArray.length];
        System.arraycopy(methodArray, 0, allDeep, 0, methodArray.length);
        System.arraycopy(instFieldArray, 0, allDeep, methodArray.length, instFieldArray.length);

        return new ClassInfo(methodArray, instFieldArray, staticFieldArray, allDeep);
    }

    private record MemberInfo(MethodHandle handle, Member member) {
        @Nullable Object extract(Object target) throws Throwable {
            return handle.invoke(target);
        }

        @Nullable Object extractStatic() throws Throwable {
            return handle.invoke();
        }
    }

    private record ClassInfo(MemberInfo[] recipeMethods, MemberInfo[] instanceFields,
                             MemberInfo[] staticFields, MemberInfo[] allDeepAccessors) {
    }
}