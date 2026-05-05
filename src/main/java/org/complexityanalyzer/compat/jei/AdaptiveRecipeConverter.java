/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.compat.jei;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.GraphBuilder;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("ForLoopReplaceableByForEach")
public final class AdaptiveRecipeConverter {

    private static final int MAX_DEPTH = 5;
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final ConcurrentHashMap<Class<?>, ClassMeta> CLASS_META_CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<Class<?>, AdapterSnapshot> ADAPTER_CACHE = new ConcurrentHashMap<>(256);

    private static final ThreadLocal<ObjectArrayList<ItemStack>> TL_ITEM_LIST =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(32));
    private static final ThreadLocal<ObjectArrayList<FluidStack>> TL_FLUID_LIST =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));
    private static final ThreadLocal<ObjectArrayList<ChemicalOutput>> TL_CHEM_LIST =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(16));

    private static <T> ObjectArrayList<T> borrowList(ThreadLocal<ObjectArrayList<T>> tl) {
        var list = tl.get();
        list.clear();
        return list;
    }

    private static <T> ObjectList<T> snapshotAndReturn(ObjectArrayList<T> borrowed) {
        if (borrowed.isEmpty()) return ObjectLists.emptyList();
        var result = new ObjectArrayList<>(borrowed);
        borrowed.clear();
        return result;
    }

    static final class ClassMeta {
        final Object2ObjectMap<String, Method> methodMap;
        final Object2ObjectMap<String, MethodHandle> handleMap;
        final ObjectList<Field> allFields;
        final Object2ObjectMap<String, Field> fieldMap;
        final RecordComponent[] recordComponents;
        final MethodHandle[] recordHandles;
        final boolean isRecord;
        final Method[] allMethods;

        ClassMeta(Class<?> clazz) {
            this.isRecord = clazz.isRecord();
            var methods = clazz.getMethods();
            this.allMethods = methods;
            var mMap = new Object2ObjectOpenHashMap<String, Method>(methods.length);
            var hMap = new Object2ObjectOpenHashMap<String, MethodHandle>(methods.length);

            for (var m : methods) {
                var existing = mMap.get(m.getName());
                if (existing == null || (existing.getParameterCount() > 0 && m.getParameterCount() == 0)) {
                    mMap.put(m.getName(), m);
                    var h = createHandle(m);
                    if (h != null) hMap.put(m.getName(), h);
                }
            }
            this.methodMap = Object2ObjectMaps.unmodifiable(mMap);
            this.handleMap = Object2ObjectMaps.unmodifiable(hMap);

            var fields = new ObjectArrayList<Field>();
            var fMap = new Object2ObjectOpenHashMap<String, Field>();
            var current = clazz;
            while (current != null && current != Object.class) {
                for (var f : current.getDeclaredFields()) {
                    fields.add(f);
                    fMap.putIfAbsent(f.getName(), f);
                }
                current = current.getSuperclass();
            }
            this.allFields = ObjectLists.unmodifiable(fields);
            this.fieldMap = Object2ObjectMaps.unmodifiable(fMap);

            if (this.isRecord) {
                this.recordComponents = clazz.getRecordComponents();
                this.recordHandles = new MethodHandle[recordComponents.length];
                for (int i = 0; i < recordComponents.length; i++) {
                    this.recordHandles[i] = createHandle(recordComponents[i].getAccessor());
                }
            } else {
                this.recordComponents = new RecordComponent[0];
                this.recordHandles = new MethodHandle[0];
            }
        }

        private static MethodHandle createHandle(Method method) {
            try {
                method.setAccessible(true);
                var priv = MethodHandles.privateLookupIn(method.getDeclaringClass(), LOOKUP);
                var raw = priv.unreflect(method);
                if (method.getParameterCount() == 0)
                    return raw.asType(MethodType.methodType(Object.class, Object.class));
                return raw;
            } catch (Exception e) {
                return null;
            }
        }

        MethodHandle findHandle(String name) {
            return handleMap.get(name);
        }
    }

    private static ClassMeta getMeta(Class<?> clazz) {
        return CLASS_META_CACHE.computeIfAbsent(clazz, ClassMeta::new);
    }

    private record AdapterSnapshot(
            Accessor itemOutput,
            Accessor fluidOutput,
            Accessor chemicalOutput,
            Accessor itemInput,
            Accessor fluidInput,
            Accessor chemicalInput
    ) {
        static final AdapterSnapshot EMPTY = new AdapterSnapshot(null, null, null,
                null, null, null);

        Accessor get(boolean isOutput, ResourceType type) {
            return switch (type) {
                case ITEM -> isOutput ? itemOutput : itemInput;
                case FLUID -> isOutput ? fluidOutput : fluidInput;
                case CHEMICAL -> isOutput ? chemicalOutput : chemicalInput;
            };
        }

        AdapterSnapshot with(boolean isOutput, ResourceType type, Accessor accessor) {
            return switch (type) {
                case ITEM -> isOutput
                        ? new AdapterSnapshot(accessor, fluidOutput, chemicalOutput, itemInput, fluidInput, chemicalInput)
                        : new AdapterSnapshot(itemOutput, fluidOutput, chemicalOutput, accessor, fluidInput, chemicalInput);
                case FLUID -> isOutput
                        ? new AdapterSnapshot(itemOutput, accessor, chemicalOutput, itemInput, fluidInput, chemicalInput)
                        : new AdapterSnapshot(itemOutput, fluidOutput, chemicalOutput, itemInput, accessor, chemicalInput);
                case CHEMICAL -> isOutput
                        ? new AdapterSnapshot(itemOutput, fluidOutput, accessor, itemInput, fluidInput, chemicalInput)
                        : new AdapterSnapshot(itemOutput, fluidOutput, chemicalOutput, itemInput, fluidInput, accessor);
            };
        }
    }

    enum ResourceType {ITEM, FLUID, CHEMICAL}

    interface Accessor {
        Object extract(Object recipe, Level level) throws Throwable;
    }

    static final class FastHandleAccessor implements Accessor {
        private final MethodHandle noArgHandle;
        private final MethodHandle fullHandle;
        private final Method method;

        FastHandleAccessor(MethodHandle handle, Method method) {
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
        public Object extract(Object recipe, Level level) throws Throwable {
            if (noArgHandle != null) return noArgHandle.invokeExact(recipe);
            var args = prepareArguments(method, level);
            if (args == null) return null;
            var all = new Object[1 + args.length];
            all[0] = recipe;
            System.arraycopy(args, 0, all, 1, args.length);
            return fullHandle.invokeWithArguments(all);
        }
    }

    static final class FieldAccessor implements Accessor {
        private final VarHandle varHandle;
        private final Field field;

        FieldAccessor(Field field) {
            this.field = field;
            VarHandle vh = null;
            try {
                field.setAccessible(true);
                var priv = MethodHandles.privateLookupIn(field.getDeclaringClass(), LOOKUP);
                vh = priv.unreflectVarHandle(field);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("Failed to create VarHandle for field {}.{}: {}",
                        field.getDeclaringClass().getName(), field.getName(), e.getMessage());
            }
            this.varHandle = vh;
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            if (varHandle != null) return varHandle.get(recipe);
            return field.get(recipe);
        }
    }

    public static void setMachineRegistry() {
    }

    public record ChemicalOutput(ResourceLocation id, long amount) {
    }

    private static final Set<String> UNSAFE_RECIPE_CLASSES = ConcurrentHashMap.newKeySet();

    public static void warmupClass(Object sampleRecipe) {
        if (sampleRecipe == null) return;
        var actual = unwrap(sampleRecipe);
        var clazz = actual.getClass();
        var existing = ADAPTER_CACHE.get(clazz);
        if (existing != null && existing != AdapterSnapshot.EMPTY) return;
        for (var type : ResourceType.values()) {
            resolveAccessor(actual, true, type);
            resolveAccessor(actual, false, type);
        }
    }

    public static ObjectList<RecipeNode> convertJeiBatch(ObjectList<JeiRecipeConverter.RecipeWithType> recipes, Level level) {
        if (recipes.isEmpty()) return ObjectLists.emptyList();

        var results = new ObjectArrayList<RecipeNode>(recipes.size());
        var unsafeRecipes = new ObjectArrayList<JeiRecipeConverter.RecipeWithType>();

        for (int i = 0, size = recipes.size(); i < size; i++) {
            var recipeWithType = recipes.get(i);
            if (UNSAFE_RECIPE_CLASSES.contains(recipeWithType.recipe().getClass().getName())) {
                unsafeRecipes.add(recipeWithType);
            } else {
                try {
                    var node = JeiRecipeConverter.convert(recipeWithType.recipe(), level, recipeWithType.jeiTypeId());
                    if (node != null) results.add(node);
                } catch (Throwable t) {
                    if (isThreadingError(t)) UNSAFE_RECIPE_CLASSES.add(recipeWithType.recipe().getClass().getName());
                }
            }
        }

        if (!unsafeRecipes.isEmpty()) {
            for (int i = 0, size = unsafeRecipes.size(); i < size; i++) {
                var recipeWithType = unsafeRecipes.get(i);
                try {
                    var node = JeiRecipeConverter.convert(recipeWithType.recipe(), level, recipeWithType.jeiTypeId());
                    if (node != null) results.add(node);
                } catch (Exception ignored) {
                }
            }
        }

        return results;
    }

    public static ObjectList<RecipeNode> convertRecipesBatch(ObjectList<? extends Recipe<?>> recipes, Level level) {
        if (recipes.isEmpty()) return ObjectLists.emptyList();

        var results = new ObjectArrayList<RecipeNode>(recipes.size());
        for (int i = 0, size = recipes.size(); i < size; i++) {
            try {
                var node = convertRecipe(recipes.get(i), level);
                if (node != null) results.add(node);
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    private static boolean isThreadingError(Throwable t) {
        if (t == null) return false;
        var message = t.getMessage();
        var className = t.getClass().getName();
        if (className.contains("ThreadingDetector") || className.contains("ConcurrentModification")) return true;
        if (message != null && (message.contains("thread") || message.contains("concurrent"))) return true;
        var cause = t.getCause();
        if (cause != null && cause != t) return isThreadingError(cause);
        return false;
    }

    public static RecipeNode convertRecipe(Recipe<?> recipe, Level level) {
        var itemOutputs = extractOutputs(recipe, level);
        var fluidOutputs = extractFluidOutputs(recipe, level);

        if (itemOutputs.isEmpty()) itemOutputs = extractMekanismItemOutputs();
        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty()) return null;

        var itemInputs = extractInputs(recipe, level);
        var fluidInputs = extractFluidInputs(recipe, level);
        if (itemInputs.isEmpty() && fluidInputs.isEmpty()) return null;

        var recipeType = recipe.getType();
        RecipeNode.Builder builder;
        Item resultItem;

        if (!itemOutputs.isEmpty()) {
            resultItem = itemOutputs.getFirst().getItem();
            builder = new RecipeNode.Builder(resultItem);
        } else {
            resultItem = Items.BARRIER;
            builder = new RecipeNode.Builder(resultItem).isPlaceholder(true);
            builder.placeholderId(BuiltInRegistries.FLUID.getKey(fluidOutputs.getFirst().getFluid()).toString());
        }

        builder.itemOutputs(itemOutputs).fluidOutputs(fluidOutputs);

        var ingredients = new ObjectArrayList<Ingredient>(itemInputs.size());
        for (int i = 0, size = itemInputs.size(); i < size; i++) {
            var group = itemInputs.get(i);
            if (!group.isEmpty()) ingredients.add(Ingredient.of(group.toArray(new ItemStack[0])));
        }

        var category = GraphBuilder.classifyRecipe(recipe, resultItem, ingredients);
        if (category == RecipeCategory.UNPROCESSABLE) return null;

        builder.recipeType(recipeType).category(category);

        for (int i = 0, size = itemInputs.size(); i < size; i++) {
            var group = itemInputs.get(i);
            if (!group.isEmpty()) {
                var variants = new ObjectArrayList<Item>();
                for (int j = 0, gSize = group.size(); j < gSize; j++) {
                    var item = group.get(j).getItem();
                    if (!variants.contains(item)) variants.add(item);
                }
                builder.addIngredient(variants, group.getFirst().getCount());
            }
        }
        for (int i = 0, size = fluidInputs.size(); i < size; i++) {
            var group = fluidInputs.get(i);
            if (!group.isEmpty()) {
                var variants = new ObjectArrayList<net.minecraft.world.level.material.Fluid>();
                for (int j = 0, gSize = group.size(); j < gSize; j++) {
                    var fluid = group.get(j).getFluid();
                    if (!variants.contains(fluid)) variants.add(fluid);
                }
                builder.addFluidIngredient(variants, group.getFirst().getAmount());
            }
        }

        var node = builder.rawRecipe(recipe).build();
        if (node.getIngredients().isEmpty() && node.getFluidIngredients().isEmpty()) return null;
        return node;
    }

    public static ObjectList<ItemStack> extractOutputs(Object recipe, Level level) {
        var actual = unwrap(recipe);
        var acc = resolveAccessor(actual, true, ResourceType.ITEM);
        if (acc == null) return ObjectLists.emptyList();
        try {
            var raw = acc.extract(actual, level);
            var buf = borrowList(TL_ITEM_LIST);
            collectItemStacks(raw, 0, buf);
            return snapshotAndReturn(buf);
        } catch (Throwable e) {
            return ObjectLists.emptyList();
        }
    }

    public static ObjectList<FluidStack> extractFluidOutputs(Object recipe, Level level) {
        var actual = unwrap(recipe);
        var acc = resolveAccessor(actual, true, ResourceType.FLUID);
        if (acc == null) return ObjectLists.emptyList();
        try {
            var raw = acc.extract(actual, level);
            var buf = borrowList(TL_FLUID_LIST);
            collectFluidStacks(raw, 0, buf);
            return snapshotAndReturn(buf);
        } catch (Throwable e) {
            return ObjectLists.emptyList();
        }
    }

    public static ObjectList<ObjectList<ItemStack>> extractInputs(Object recipe, Level level) {
        var actual = unwrap(recipe);
        var meta = getMeta(actual.getClass());
        if (meta.isRecord) return extractItemInputsFromRecord(actual, meta);
        var acc = resolveAccessor(actual, false, ResourceType.ITEM);
        if (acc == null) return ObjectLists.emptyList();
        try {
            var raw = acc.extract(actual, level);
            return collectItemStackGroups(raw);
        } catch (Throwable e) {
            return ObjectLists.emptyList();
        }
    }

    public static ObjectList<ObjectList<FluidStack>> extractFluidInputs(Object recipe, Level level) {
        var actual = unwrap(recipe);
        var meta = getMeta(actual.getClass());
        if (meta.isRecord) return extractFluidInputsFromRecord(actual, meta);
        var acc = resolveAccessor(actual, false, ResourceType.FLUID);
        if (acc == null) return ObjectLists.emptyList();
        try {
            var raw = acc.extract(actual, level);
            return collectFluidStackGroups(raw);
        } catch (Throwable e) {
            return ObjectLists.emptyList();
        }
    }

    public static ObjectList<ChemicalOutput> extractChemicalInputs(Object recipe) {
        var actual = unwrap(recipe);
        var meta = getMeta(actual.getClass());
        if (meta.isRecord) return extractChemicalFromRecord(actual, meta);

        var methods = new String[]{"getChemicalInput", "getChemicalInputs", "getGasInput", "getGasInputs", "getInput"};
        var buf = borrowList(TL_CHEM_LIST);
        for (var name : methods) {
            var h = meta.findHandle(name);
            if (h == null) continue;
            try {
                var result = h.invokeExact(actual);
                if (result != null) {
                    collectChemicals(result, buf);
                    if (!buf.isEmpty()) return snapshotAndReturn(buf);
                }
            } catch (Throwable ignored) {
            }
        }
        return ObjectLists.emptyList();
    }

    public static ObjectList<ChemicalOutput> extractChemicalOutputs(Object recipe, Level level) {
        var actual = unwrap(recipe);
        var meta = getMeta(actual.getClass());
        if (meta.isRecord) return extractChemicalFromRecord(actual, meta);
        var acc = resolveAccessor(actual, true, ResourceType.CHEMICAL);
        if (acc == null) return ObjectLists.emptyList();
        try {
            var raw = acc.extract(actual, level);
            var buf = borrowList(TL_CHEM_LIST);
            collectChemicals(raw, buf);
            return snapshotAndReturn(buf);
        } catch (Throwable e) {
            return ObjectLists.emptyList();
        }
    }

    private static void collectItemStacks(Object obj, int depth, ObjectList<ItemStack> acc) {
        if (obj == null || depth > MAX_DEPTH) return;
        if (obj instanceof ItemStack stack) {
            if (!stack.isEmpty()) acc.add(stack);
            return;
        }
        if (obj instanceof Ingredient ingredient) {
            var stacks = ingredient.getItems();
            for (int i = 0; i < stacks.length; i++) if (!stacks[i].isEmpty()) acc.add(stacks[i]);
            return;
        }
        if (obj instanceof Collection<?> coll) {
            for (var item : coll) collectItemStacks(item, depth + 1, acc);
        } else if (obj.getClass().isArray()) {
            Object[] arr = (Object[]) obj;
            for (int i = 0; i < arr.length; i++) collectItemStacks(arr[i], depth + 1, acc);
        }
    }

    private static Object unwrap(Object obj) {
        if (obj instanceof RecipeHolder<?> holder) return holder.value();
        return obj;
    }

    private static Object[] prepareArguments(Method method, Level level) {
        var params = method.getParameterTypes();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAssignableFrom(Level.class)) args[i] = level;
            else return null;
        }
        return args;
    }

    private static Accessor resolveAccessor(Object recipe, boolean isOutput, ResourceType type) {
        var clazz = recipe.getClass();
        var snapshot = ADAPTER_CACHE.computeIfAbsent(clazz, k -> AdapterSnapshot.EMPTY);
        var existing = snapshot.get(isOutput, type);
        if (existing != null) return existing;
        var found = findAccessor(recipe, isOutput, type);
        if (found != null) ADAPTER_CACHE.put(clazz, snapshot.with(isOutput, type, found));
        return found;
    }

    private static Accessor findAccessor(Object recipe, boolean isOutput, ResourceType type) {
        var meta = getMeta(recipe.getClass());
        for (int i = 0; i < meta.allMethods.length; i++) {
            Method m = meta.allMethods[i];
            String name = m.getName().toLowerCase();
            if (name.contains(type.name().toLowerCase()) &&
                    (isOutput ? name.contains("output") : name.contains("input"))) {
                var h = meta.findHandle(m.getName());
                if (h != null) return new FastHandleAccessor(h, m);
            }
        }
        for (var entry : Object2ObjectMaps.fastIterable(meta.fieldMap)) {
            String name = entry.getKey().toLowerCase();
            if (name.contains(type.name().toLowerCase()) &&
                    (isOutput ? name.contains("output") : name.contains("input"))) {
                return new FieldAccessor(entry.getValue());
            }
        }
        return null;
    }

    private static void collectFluidStacks(Object obj, int depth, ObjectList<FluidStack> acc) {
        if (obj == null || depth > MAX_DEPTH) return;
        if (obj instanceof FluidStack stack) {
            if (!stack.isEmpty()) acc.add(stack);
        } else if (obj instanceof Collection<?> coll) {
            for (var item : coll) collectFluidStacks(item, depth + 1, acc);
        }
    }

    private static ObjectList<ObjectList<ItemStack>> collectItemStackGroups(Object obj) {
        var groups = new ObjectArrayList<ObjectList<ItemStack>>();
        if (obj instanceof Collection<?> coll) {
            for (var item : coll) {
                var group = new ObjectArrayList<ItemStack>();
                collectItemStacks(item, 1, group);
                if (!group.isEmpty()) groups.add(group);
            }
        }
        return groups;
    }

    private static ObjectList<ObjectList<FluidStack>> collectFluidStackGroups(Object obj) {
        var groups = new ObjectArrayList<ObjectList<FluidStack>>();
        if (obj instanceof Collection<?> coll) {
            for (var item : coll) {
                var group = new ObjectArrayList<FluidStack>();
                collectFluidStacks(item, 1, group);
                if (!group.isEmpty()) groups.add(group);
            }
        }
        return groups;
    }

    private static void collectChemicals(Object obj, ObjectList<ChemicalOutput> acc) {
        if (obj == null) return;
        var meta = getMeta(obj.getClass());
        var idH = meta.findHandle("getType");
        if (idH == null) idH = meta.findHandle("getChemical");
        var amtH = meta.findHandle("getAmount");
        if (idH != null && amtH != null) {
            try {
                var chem = idH.invokeExact(obj);
                var amt = (long) amtH.invokeExact(obj);
                var chemId = (ResourceLocation) getMeta(chem.getClass()).findHandle("getRegistryName").invokeExact(chem);
                acc.add(new ChemicalOutput(chemId, amt));
            } catch (Throwable ignored) {
            }
        }
    }

    private static ObjectList<ObjectList<ItemStack>> extractItemInputsFromRecord(Object actual, ClassMeta meta) {
        var results = new ObjectArrayList<ObjectList<ItemStack>>();
        for (int i = 0; i < meta.recordHandles.length; i++) {
            try {
                var val = meta.recordHandles[i].invokeExact(actual);
                var group = new ObjectArrayList<ItemStack>();
                collectItemStacks(val, 0, group);
                if (!group.isEmpty()) results.add(group);
            } catch (Throwable ignored) {
            }
        }
        return results;
    }

    private static ObjectList<ObjectList<FluidStack>> extractFluidInputsFromRecord(Object actual, ClassMeta meta) {
        var results = new ObjectArrayList<ObjectList<FluidStack>>();
        for (int i = 0; i < meta.recordHandles.length; i++) {
            try {
                var val = meta.recordHandles[i].invokeExact(actual);
                var group = new ObjectArrayList<FluidStack>();
                collectFluidStacks(val, 0, group);
                if (!group.isEmpty()) results.add(group);
            } catch (Throwable ignored) {
            }
        }
        return results;
    }

    private static ObjectList<ChemicalOutput> extractChemicalFromRecord(Object actual, ClassMeta meta) {
        var results = new ObjectArrayList<ChemicalOutput>();
        for (int i = 0; i < meta.recordHandles.length; i++) {
            try {
                collectChemicals(meta.recordHandles[i].invokeExact(actual), results);
            } catch (Throwable ignored) {
            }
        }
        return results;
    }

    public static RecipeType<?> extractRecipeType(Object recipe) {
        var actual = unwrap(recipe);
        var meta = getMeta(actual.getClass());
        var h = meta.findHandle("getType");
        if (h != null) {
            try {
                var res = h.invokeExact(actual);
                if (res instanceof RecipeType<?> type) return type;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static void clearCaches() {
        CLASS_META_CACHE.clear();
        ADAPTER_CACHE.clear();
    }

    private static ObjectList<ItemStack> extractMekanismItemOutputs() {
        return ObjectLists.emptyList();
    }
}