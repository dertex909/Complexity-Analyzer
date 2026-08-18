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

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeMetadata {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ConcurrentHashMap<Class<?>, ClassMeta> META_CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<Class<?>, FastAccessors> FAST_ACCESSORS_CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<Class<?>, UniversalAccessors> UNIVERSAL_ACCESSORS_CACHE = new ConcurrentHashMap<>(256);
    private static final String METHOD_GET_TOAST_SYMBOL = MethodRefUtils.getMethodName(Recipe.class, Recipe::getToastSymbol);

    private RecipeMetadata() {
    }

    public static ClassMeta getMeta(Class<?> clazz) {
        return META_CACHE.computeIfAbsent(clazz, ClassMeta::new);
    }

    public static void clearCaches() {
        META_CACHE.clear();
        FAST_ACCESSORS_CACHE.clear();
        UNIVERSAL_ACCESSORS_CACHE.clear();
    }

    public static FastAccessors getFastAccessors(Class<?> clazz) {
        return FAST_ACCESSORS_CACHE.computeIfAbsent(clazz, RecipeMetadata::resolveFastAccessors);
    }

    private static FastAccessors resolveFastAccessors(Class<?> clazz) {
        var meta = getMeta(clazz);
        var itemAcc = new ObjectArrayList<Accessor>(4);
        var ingredientAcc = new ObjectArrayList<Accessor>(4);
        var fluidAcc = new ObjectArrayList<Accessor>(4);
        var probeAcc = new ObjectArrayList<Accessor>(4);
        var seenNames = new ObjectOpenHashSet<String>();

        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            var h = meta.allHandles[i];
            if (m.getDeclaringClass() == Recipe.class) continue;
            var kind = StructuralTypeClassifier.classifyDescriptor(descriptorOf(m));
            if (kind != null) {
                var acc = new MethodAccessor(h, m);
                switch (kind) {
                    case ITEM_STACK -> {
                        itemAcc.add(acc);
                        seenNames.add(acc.name());
                    }
                    case INGREDIENT -> {
                        ingredientAcc.add(acc);
                        seenNames.add(acc.name());
                    }
                    case FLUID_STACK -> {
                        fluidAcc.add(acc);
                        seenNames.add(acc.name());
                    }
                    default -> {
                    }
                }
            } else if (isContainerReturnType(m.getReturnType())) {
                var acc = new MethodAccessor(h, m);
                probeAcc.add(acc);
                seenNames.add(acc.name());
            }
        }

        for (int i = 0; i < meta.fields.length; i++) {
            var f = meta.fields[i];
            if (seenNames.contains(f.getName())) continue;
            var type = f.getType();
            var kind = StructuralTypeClassifier.classifyDescriptor(type.getName().replace('.', '/'));
            if (kind != null) {
                var acc = new FieldAccessor(f);
                switch (kind) {
                    case ITEM_STACK -> itemAcc.add(acc);
                    case INGREDIENT -> ingredientAcc.add(acc);
                    case FLUID_STACK -> fluidAcc.add(acc);
                    default -> {
                    }
                }
            } else if (isContainerReturnType(type)) {
                probeAcc.add(new FieldAccessor(f));
            }
        }
        return new FastAccessors(itemAcc, ingredientAcc, fluidAcc, probeAcc);
    }

    public static UniversalAccessors getUniversalAccessors(Object recipe, Level level) {
        if (recipe == null) return UniversalAccessors.EMPTY;
        return UNIVERSAL_ACCESSORS_CACHE.computeIfAbsent(recipe.getClass(), c -> resolveUniversalAccessors(c, recipe, level));
    }

    private static UniversalAccessors resolveUniversalAccessors(Class<?> clazz, Object recipe, Level level) {
        var meta = getMeta(clazz);

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

        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            var h = meta.allHandles[i];

            var returnType = m.getReturnType();
            if (returnType == void.class || returnType == Void.class) continue;
            if (UniversalTypeResolver.isTerminalType(returnType)) continue;

            var acc = new MethodAccessor(h, m);
            HeuristicRoleClassifier.RoleClassification role;
            if (recipe != null) {
                try {
                    var raw = acc.extract(recipe, level);
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

        for (var f : meta.fields) {
            var fieldType = f.getType();
            if (fieldType.isPrimitive() || fieldType == String.class || fieldType.isEnum()) continue;
            if (UniversalTypeResolver.isTerminalType(fieldType)) continue;

            var acc = new FieldAccessor(f);
            HeuristicRoleClassifier.RoleClassification role;
            if (recipe != null) {
                try {
                    var raw = acc.extract(recipe, level);
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

        return new UniversalAccessors(inputAcc, outputAcc, unknownAcc, allAcc);
    }

    private static String descriptorOf(Method m) {
        var rt = m.getReturnType();
        if (rt == void.class || rt == Void.class) return null;
        return rt.getName().replace('.', '/');
    }

    private static boolean isContainerReturnType(Class<?> type) {
        if (type == null || type == void.class) return false;
        return type.isArray() || Iterable.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
    }

    public interface Accessor {
        Object extract(Object recipe, Level level) throws Throwable;

        default String type() {
            return "unknown";
        }

        default String name() {
            return "unknown";
        }
    }

    public record FastAccessors(
            ObjectList<Accessor> itemAccessors,
            ObjectList<Accessor> ingredientAccessors,
            ObjectList<Accessor> fluidAccessors,
            ObjectList<Accessor> probeAccessors
    ) {
    }

    public record UniversalAccessors(
            ObjectList<Accessor> inputAccessors,
            ObjectList<Accessor> outputAccessors,
            ObjectList<Accessor> unknownAccessors,
            ObjectList<Accessor> allAccessors
    ) {
        public static final UniversalAccessors EMPTY = new UniversalAccessors(
                ObjectLists.emptyList(), ObjectLists.emptyList(), ObjectLists.emptyList(), ObjectLists.emptyList()
        );

        public boolean isEmpty() {
            return allAccessors.isEmpty();
        }
    }

    public static final class MethodAccessor implements Accessor {
        private final MethodHandle noArgHandle;
        private final MethodHandle fullHandle;
        private final Method method;
        private HeuristicRoleClassifier.RoleClassification roleClass = HeuristicRoleClassifier.RoleClassification.UNKNOWN;

        public MethodAccessor(MethodHandle handle, Method method) {
            this.method = method;
            if (handle != null) {
                int paramCount = method.getParameterCount();
                if (paramCount == 0) {
                    this.noArgHandle = handle.asType(MethodType.methodType(Object.class, Object.class));
                    this.fullHandle = null;
                } else {
                    this.noArgHandle = null;
                    this.fullHandle = handle;
                }
            } else {
                this.noArgHandle = null;
                this.fullHandle = null;
            }
        }

        public void setRoleClassification(HeuristicRoleClassifier.RoleClassification rc) {
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
            return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "() → " + method.getReturnType().getSimpleName() + " [" + roleClass.role() + " conf=" + roleClass.confidence() + "]";
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            if (noArgHandle != null) return noArgHandle.invokeExact(recipe);
            if (fullHandle != null) {
                var params = method.getParameterTypes();
                var args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    if (params[i].isAssignableFrom(Level.class)) {
                        args[i] = level;
                    } else if (level != null && (params[i] == HolderLookup.Provider.class || params[i] == RegistryAccess.class
                            || params[i].isAssignableFrom(level.registryAccess().getClass()))) {
                        args[i] = level.registryAccess();
                    } else {
                        return null;
                    }
                }
                var all = new Object[1 + args.length];
                all[0] = recipe;
                System.arraycopy(args, 0, all, 1, args.length);
                return fullHandle.invokeWithArguments(all);
            }

            int paramCount = method.getParameterCount();
            if (paramCount == 0) {
                return method.invoke(recipe);
            } else {
                var params = method.getParameterTypes();
                var args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    if (params[i].isAssignableFrom(Level.class)) {
                        args[i] = level;
                    } else if (level != null && (params[i] == HolderLookup.Provider.class || params[i] == RegistryAccess.class
                            || params[i].isAssignableFrom(level.registryAccess().getClass()))) {
                        args[i] = level.registryAccess();
                    } else {
                        return null;
                    }
                }
                return method.invoke(recipe, args);
            }
        }
    }

    public static final class FieldAccessor implements Accessor {
        private final Field field;
        private HeuristicRoleClassifier.RoleClassification roleClass = HeuristicRoleClassifier.RoleClassification.UNKNOWN;

        public FieldAccessor(Field field) {
            this.field = field;
        }

        public void setRoleClassification(HeuristicRoleClassifier.RoleClassification rc) {
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
            return field.getDeclaringClass().getSimpleName() + "." + field.getName() + " : " + field.getType().getSimpleName() + " [" + roleClass.role() + " conf=" + roleClass.confidence() + "]";
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            return field.get(recipe);
        }
    }

    public static final class ClassMeta {
        public final Method[] allMethods;
        public final MethodHandle[] allHandles;
        public final Field[] fields;
        public final Field[] scanFields;

        public ClassMeta(Class<?> clazz) {
            var mList = new ObjectArrayList<Method>();
            var hList = new ObjectArrayList<MethodHandle>();
            var queue = new ObjectArrayList<Class<?>>();
            var seenMethods = new ObjectOpenHashSet<String>();
            queue.add(clazz);
            int idx = 0;
            while (idx < queue.size()) {
                var current = queue.get(idx++);
                if (current == null || current == Object.class) continue;
                if (StructuralTypeClassifier.isTerminalType(current)) continue;
                var methods = current.getDeclaredMethods();
                Arrays.sort(methods, Comparator.comparing(Method::getName)
                        .thenComparingInt(Method::getParameterCount)
                        .thenComparing(m -> Arrays.toString(m.getParameterTypes()))
                        .thenComparing(m -> m.getReturnType().getName()));

                for (var m : methods) {
                    if (m.getParameterCount() > 1) continue;
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getName().equals(METHOD_GET_TOAST_SYMBOL)) continue;
                    var rt = m.getReturnType();
                    if (rt == void.class || rt == Void.class) continue;
                    if (rt.isPrimitive()) continue;
                    if (rt == String.class || rt == Boolean.class || Number.class.isAssignableFrom(rt) || rt == Character.class)
                        continue;
                    if (StructuralTypeClassifier.isTerminalType(rt)) continue;
                    if (!seenMethods.add(m.getName())) continue;

                    mList.add(m);
                    hList.add(createHandle(m));
                }
                var sup = current.getSuperclass();
                if (sup != null && sup != Object.class) queue.add(sup);
                var ifaces = current.getInterfaces();
                Arrays.sort(ifaces, Comparator.comparing(Class::getName));
                Collections.addAll(queue, ifaces);
            }
            this.allMethods = mList.toArray(new Method[0]);
            this.allHandles = hList.toArray(new MethodHandle[0]);

            var fList = new ObjectArrayList<Field>();
            var curCls = clazz;
            while (curCls != null && curCls != Object.class) {
                var curFields = curCls.getDeclaredFields();
                Arrays.sort(curFields, Comparator.comparing(Field::getName));
                for (var f : curFields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    boolean duplicate = false;
                    for (var field : fList) {
                        if (field.getName().equals(f.getName())) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) continue;
                    try {
                        f.setAccessible(true);
                        fList.add(f);
                    } catch (Exception ignored) {
                    }
                }
                curCls = curCls.getSuperclass();
            }
            this.fields = fList.toArray(new Field[0]);

            var sList = new ObjectArrayList<Field>();
            for (var f : fList) {
                var type = f.getType();
                if (type.isPrimitive() || type == String.class || type.isEnum()) continue;
                if (StructuralTypeClassifier.isTerminalType(type)) continue;
                sList.add(f);
            }
            this.scanFields = sList.toArray(new Field[0]);
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

        public Field[] scanFields() {
            return scanFields;
        }

        public Field[] allFields() {
            return fields;
        }

        public Method[] allMethods() {
            return allMethods;
        }

        public MethodHandle[] allHandles() {
            return allHandles;
        }
    }
}