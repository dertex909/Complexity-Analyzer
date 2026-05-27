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
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public final class UniversalAccessorResolver {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ConcurrentHashMap<Class<?>, ResolvedAccessors> ACCESSOR_CACHE = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<Class<?>, ClassMeta> META_CACHE = new ConcurrentHashMap<>(256);

    public interface Accessor {

        String type();

        String name();

        Object extract(Object recipe, Level level) throws Throwable;

        @Override
        String toString();
    }

    public record ResolvedAccessors(
            ObjectList<Accessor> inputAccessors,
            ObjectList<Accessor> outputAccessors,
            ObjectList<Accessor> unknownAccessors,
            ObjectList<Accessor> allAccessors
    ) {
        public boolean isEmpty() {
            return allAccessors.isEmpty();
        }
    }

    public record ClassMeta(
            Field[] allFields,
            Field[] scanFields,
            Method[] allMethods,
            MethodHandle[] allHandles
    ) {
    }

    public static ResolvedAccessors resolve(Object recipe, Level level) {
        if (recipe == null) return empty();
        Class<?> clazz = recipe.getClass();
        ResolvedAccessors existing = ACCESSOR_CACHE.get(clazz);
        if (existing != null) return existing;
        ACCESSOR_CACHE.put(clazz, empty());
        ResolvedAccessors result = resolveUncached(clazz, recipe, level);
        ACCESSOR_CACHE.put(clazz, result);
        return result;
    }

    public static ClassMeta getMeta(Class<?> clazz) {
        ClassMeta existing = META_CACHE.get(clazz);
        if (existing != null) return existing;
        ClassMeta result = ClassMetaBuilder.build(clazz);
        META_CACHE.put(clazz, result);
        return result;
    }

    public static void clearCache() {
        ACCESSOR_CACHE.clear();
        META_CACHE.clear();
    }

    private static ResolvedAccessors empty() {
        return new ResolvedAccessors(
                ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), ObjectLists.emptyList()
        );
    }

    private static ResolvedAccessors resolveUncached(Class<?> clazz, Object recipe, Level level) {
        ClassMeta meta = getMeta(clazz);

        ReferenceSet<Ingredient> standardInputs = ReferenceSets.emptySet();
        Item anchorItem = null;

        if (recipe instanceof Recipe<?> r && level != null) {
            standardInputs = new ReferenceOpenHashSet<>();
            for (var ing : r.getIngredients()) if (ing != null && !ing.isEmpty()) standardInputs.add(ing);
            try {
                var result = r.getResultItem(level.registryAccess());
                if (!result.isEmpty()) anchorItem = result.getItem();
            } catch (Throwable ignored) {
            }
        }

        var inputAcc = new ObjectArrayList<Accessor>(8);
        var outputAcc = new ObjectArrayList<Accessor>(8);
        var unknownAcc = new ObjectArrayList<Accessor>(8);
        var allAcc = new ObjectArrayList<Accessor>(16);

        for (int i = 0; i < meta.allMethods().length; i++) {
            Method m = meta.allMethods()[i];
            MethodHandle h = meta.allHandles()[i];
            if (h == null) continue;

            Class<?> returnType = m.getReturnType();
            if (returnType == void.class || returnType == Void.class) continue;
            if (UniversalTypeResolver.isTerminalType(returnType)) continue;

            MethodAccessor acc = new MethodAccessor(h, m);
            HeuristicRoleClassifier.RoleClassification role;
            if (recipe != null) {
                try {
                    Object raw = acc.extract(recipe, level);
                    role = HeuristicRoleClassifier.classify(recipe, raw, m.getName(), "method", anchorItem, standardInputs);
                } catch (Throwable e) {
                    role = HeuristicRoleClassifier.classifyMethod(m);
                }
            } else {
                role = HeuristicRoleClassifier.classifyMethod(m);
            }

            acc.setRoleClassification(role);
            allAcc.add(acc);
            switch (role.role()) {
                case INPUT -> inputAcc.add(acc);
                case OUTPUT -> outputAcc.add(acc);
                default -> unknownAcc.add(acc);
            }
        }

        for (Field f : meta.allFields()) {
            Class<?> fieldType = f.getType();
            if (fieldType.isPrimitive() || fieldType == String.class || fieldType.isEnum()) continue;
            if (UniversalTypeResolver.isTerminalType(fieldType)) continue;

            FieldAccessor acc = new FieldAccessor(f);
            HeuristicRoleClassifier.RoleClassification role;
            if (recipe != null) {
                try {
                    Object raw = acc.extract(recipe, level);
                    role = HeuristicRoleClassifier.classify(recipe, raw, f.getName(), "field", anchorItem, standardInputs);
                } catch (Throwable e) {
                    role = HeuristicRoleClassifier.classifyField(f);
                }
            } else {
                role = HeuristicRoleClassifier.classifyField(f);
            }

            acc.setRoleClassification(role);
            allAcc.add(acc);
            switch (role.role()) {
                case INPUT -> inputAcc.add(acc);
                case OUTPUT -> outputAcc.add(acc);
                default -> unknownAcc.add(acc);
            }
        }

        return new ResolvedAccessors(inputAcc, outputAcc, unknownAcc, allAcc);
    }

    public static final class MethodAccessor implements Accessor {
        private final MethodHandle noArgHandle;
        private final MethodHandle fullHandle;
        private final Method method;
        private HeuristicRoleClassifier.RoleClassification roleClass;

        MethodAccessor(MethodHandle handle, Method method) {
            this.method = method;
            int paramCount = method.getParameterCount();
            if (paramCount == 0) {
                this.noArgHandle = handle.asType(MethodType.methodType(Object.class, Object.class));
                this.fullHandle = null;
            } else {
                this.noArgHandle = null;
                this.fullHandle = handle;
            }
            this.roleClass = HeuristicRoleClassifier.RoleClassification.UNKNOWN;
        }

        void setRoleClassification(HeuristicRoleClassifier.RoleClassification rc) {
            this.roleClass = rc;
        }

        @Override
        public String type() {
            return "method";
        }

        @Override
        public String name() {
            return method.getName();
        }

        @Override
        public String toString() {
            return method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + "() → " + method.getReturnType().getSimpleName()
                    + " [" + roleClass.role() + " conf=" + roleClass.confidence() + "]";
        }

        public Object extract(Object recipe, Level level) throws Throwable {
            if (noArgHandle != null) return noArgHandle.invokeExact(recipe);
            var params = method.getParameterTypes();
            var args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAssignableFrom(Level.class)) args[i] = level;
                else return null;
            }
            var all = new Object[1 + args.length];
            all[0] = recipe;
            System.arraycopy(args, 0, all, 1, args.length);
            return fullHandle.invokeWithArguments(all);
        }
    }

    public static final class FieldAccessor implements Accessor {
        private final Field field;
        private HeuristicRoleClassifier.RoleClassification roleClass;

        FieldAccessor(Field field) {
            this.field = field;
            this.roleClass = HeuristicRoleClassifier.RoleClassification.UNKNOWN;
        }

        void setRoleClassification(HeuristicRoleClassifier.RoleClassification rc) {
            this.roleClass = rc;
        }

        @Override
        public String type() {
            return "field";
        }

        @Override
        public String name() {
            return field.getName();
        }

        @Override
        public String toString() {
            return field.getDeclaringClass().getSimpleName() + "." + field.getName()
                    + " : " + field.getType().getSimpleName()
                    + " [" + roleClass.role() + " conf=" + roleClass.confidence() + "]";
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            return field.get(recipe);
        }
    }

    private static final class ClassMetaBuilder {
        static ClassMeta build(Class<?> clazz) {
            var fList = new ObjectArrayList<Field>();
            var curCls = clazz;
            while (curCls != null && curCls != Object.class) {
                for (Field f : curCls.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    boolean dup = false;
                    for (int i = 0; i < fList.size(); i++) {
                        if (fList.get(i).getName().equals(f.getName())) {
                            dup = true;
                            break;
                        }
                    }
                    if (dup) continue;
                    try {
                        f.setAccessible(true);
                        fList.add(f);
                    } catch (Exception ignored) {
                    }
                }
                curCls = curCls.getSuperclass();
            }
            Field[] allFields = fList.toArray(new Field[0]);

            var sList = new ObjectArrayList<Field>();
            for (Field f : fList) {
                Class<?> type = f.getType();
                if (type.isPrimitive() || type == String.class || type.isEnum()) continue;
                if (UniversalTypeResolver.isTerminalType(type)) continue;
                sList.add(f);
            }
            Field[] scanFields = sList.toArray(new Field[0]);

            var mList = new ObjectArrayList<Method>();
            var hList = new ObjectArrayList<MethodHandle>();
            var seenMethods = new ObjectOpenHashSet<String>();
            var queue = new ObjectArrayList<Class<?>>();
            queue.add(clazz);
            int idx = 0;
            while (idx < queue.size()) {
                Class<?> current = queue.get(idx++);
                if (current == null || current == Object.class) continue;
                for (Method m : current.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() > 1) continue;

                    Class<?> rt = m.getReturnType();
                    if (rt == void.class || rt == Void.class) continue;
                    if (rt.isPrimitive()) continue;
                    if (rt == String.class || rt == Boolean.class
                            || Number.class.isAssignableFrom(rt) || rt == Character.class) continue;

                    if (!seenMethods.add(m.getName())) continue;

                    MethodHandle handle = createHandle(m);
                    if (handle != null) {
                        mList.add(m);
                        hList.add(handle);
                    }
                }
                Class<?> sup = current.getSuperclass();
                if (sup != null && sup != Object.class) queue.add(sup);
                Collections.addAll(queue, current.getInterfaces());
            }

            return new ClassMeta(allFields, scanFields, mList.toArray(new Method[0]), hList.toArray(new MethodHandle[0]));
        }

        private static MethodHandle createHandle(Method method) {
            try {
                method.setAccessible(true);
                if (method.getDeclaringClass().isInterface()) {
                    var pub = MethodHandles.publicLookup();
                    var mt = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
                    return pub.findVirtual(method.getDeclaringClass(), method.getName(), mt);
                }
                var priv = MethodHandles.privateLookupIn(method.getDeclaringClass(), LOOKUP);
                return priv.unreflect(method);
            } catch (Exception e) {
                return null;
            }
        }
    }
}