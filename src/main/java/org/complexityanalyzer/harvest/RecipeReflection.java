package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeReflection {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ConcurrentHashMap<Class<?>, ClassMeta> META_CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<Class<?>, ResolvedAccessors> ACCESSOR_CACHE = new ConcurrentHashMap<>(256);

    public record ResolvedAccessors(
            ObjectList<Accessor> itemAccessors,
            ObjectList<Accessor> ingredientAccessors,
            ObjectList<Accessor> fluidAccessors,
            ObjectList<Accessor> probeAccessors
    ) {
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

    public static final class MethodAccessor implements Accessor {
        private final MethodHandle noArgHandle;
        private final MethodHandle fullHandle;
        private final Method method;

        public MethodAccessor(MethodHandle handle, Method method) {
            this.method = method;
            int paramCount = method.getParameterCount();
            if (paramCount == 0) {
                this.noArgHandle = handle.asType(MethodType.methodType(Object.class, Object.class));
                this.fullHandle = null;
            } else {
                this.noArgHandle = null;
                this.fullHandle = handle;
            }
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
            return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "()";
        }

        @Override
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
        private final VarHandle varHandle;
        private final Field field;

        public FieldAccessor(VarHandle varHandle, Field field) {
            this.varHandle = varHandle;
            this.field = field;
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
            return field.getDeclaringClass().getSimpleName() + "." + field.getName();
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            if (varHandle != null) return varHandle.get(recipe);
            return field.get(recipe);
        }
    }

    public static final class ClassMeta {
        public final Method[] allMethods;
        public final MethodHandle[] allHandles;
        public final Field[] fields;
        public final VarHandle[] fieldVarHandles;
        public final VarHandle[] scanVarHandles;

        ClassMeta(Class<?> clazz) {
            var mList = new ObjectArrayList<Method>();
            var hList = new ObjectArrayList<MethodHandle>();
            var queue = new ObjectArrayList<Class<?>>();
            var seenMethods = new HashSet<String>();
            queue.add(clazz);
            int idx = 0;
            while (idx < queue.size()) {
                var current = queue.get(idx++);
                if (current == null || current == Object.class) continue;
                for (var m : current.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    if (Modifier.isStatic(m.getModifiers())) continue;

                    var rt = m.getReturnType();
                    if (rt == void.class || rt == Void.class) continue;
                    if (rt.isPrimitive()) continue;
                    if (rt == String.class || rt == Boolean.class || Number.class.isAssignableFrom(rt) || rt == Character.class)
                        continue;
                    if (StructuralTypeClassifier.isTerminalType(rt)) continue;

                    if (!seenMethods.add(m.getName())) continue;

                    var handle = createHandle(m);
                    if (handle != null) {
                        mList.add(m);
                        hList.add(handle);
                    }
                }
                var sup = current.getSuperclass();
                if (sup != null && sup != Object.class) queue.add(sup);
                Collections.addAll(queue, current.getInterfaces());
            }
            this.allMethods = mList.toArray(new Method[0]);
            this.allHandles = hList.toArray(new MethodHandle[0]);

            var fList = new ObjectArrayList<Field>();
            var vhList = new ObjectArrayList<VarHandle>();
            var curCls = clazz;
            while (curCls != null && curCls != Object.class) {
                for (var f : curCls.getDeclaredFields()) {
                    boolean duplicate = false;
                    for (int i = 0; i < fList.size(); i++) {
                        if (fList.get(i).getName().equals(f.getName())) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) continue;
                    try {
                        f.setAccessible(true);
                        var priv = MethodHandles.privateLookupIn(curCls, LOOKUP);
                        var vh = priv.unreflectVarHandle(f);
                        fList.add(f);
                        vhList.add(vh);
                    } catch (Exception ignored) {
                    }
                }
                curCls = curCls.getSuperclass();
            }
            this.fields = fList.toArray(new Field[0]);
            this.fieldVarHandles = vhList.toArray(new VarHandle[0]);

            var sList = new ObjectArrayList<VarHandle>();
            for (int i = 0; i < fList.size(); i++) {
                var type = fList.get(i).getType();
                if (type.isPrimitive() || type == String.class || type.isEnum()) continue;
                if (StructuralTypeClassifier.isTerminalType(type)) continue;
                sList.add(vhList.get(i));
            }
            this.scanVarHandles = sList.toArray(new VarHandle[0]);
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

    public static ClassMeta getMeta(Class<?> clazz) {
        return META_CACHE.computeIfAbsent(clazz, ClassMeta::new);
    }

    public static ResolvedAccessors getAccessors(Class<?> clazz) {
        return ACCESSOR_CACHE.computeIfAbsent(clazz, RecipeReflection::resolveAccessors);
    }

    private static ResolvedAccessors resolveAccessors(Class<?> clazz) {
        var meta = getMeta(clazz);
        var itemAcc = new ObjectArrayList<Accessor>(4);
        var ingredientAcc = new ObjectArrayList<Accessor>(4);
        var fluidAcc = new ObjectArrayList<Accessor>(4);
        var probeAcc = new ObjectArrayList<Accessor>(4);
        for (int i = 0; i < meta.allMethods.length; i++) {
            var m = meta.allMethods[i];
            var h = meta.allHandles[i];
            if (h == null) continue;
            if (m.getDeclaringClass() == Recipe.class) continue;
            var kind = StructuralTypeClassifier.classifyDescriptor(descriptorOf(m));
            if (kind != null) {
                var acc = new MethodAccessor(h, m);
                switch (kind) {
                    case ITEM_STACK -> itemAcc.add(acc);
                    case INGREDIENT -> ingredientAcc.add(acc);
                    case FLUID_STACK -> fluidAcc.add(acc);
                    default -> {
                    }
                }
            } else if (isContainerReturnType(m.getReturnType())) {
                probeAcc.add(new MethodAccessor(h, m));
            }
        }
        for (int i = 0; i < meta.fields.length; i++) {
            var f = meta.fields[i];
            var type = f.getType();
            var kind = StructuralTypeClassifier.classifyDescriptor(type.getName().replace('.', '/'));
            if (kind != null) {
                var acc = new FieldAccessor(meta.fieldVarHandles[i], f);
                switch (kind) {
                    case ITEM_STACK -> itemAcc.add(acc);
                    case INGREDIENT -> ingredientAcc.add(acc);
                    case FLUID_STACK -> fluidAcc.add(acc);
                    default -> {
                    }
                }
            } else if (isContainerReturnType(type)) {
                probeAcc.add(new FieldAccessor(meta.fieldVarHandles[i], f));
            }
        }
        return new ResolvedAccessors(itemAcc, ingredientAcc, fluidAcc, probeAcc);
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

    public static void clearCaches() {
        META_CACHE.clear();
        ACCESSOR_CACHE.clear();
    }
}