/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.MachineRegistry;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class AdaptiveRecipeConverter {

    private static final int MAX_DEPTH = 5;
    private static final int BATCH_TIMEOUT_SECONDS = 30;
    private static final int SINGLE_LEARN_TIMEOUT_SECONDS = 3;

    private static final MethodHandles.Lookup LOOKUP;

    static {
        MethodHandles.Lookup lk;
        try {
            lk = MethodHandles.privateLookupIn(AdaptiveRecipeConverter.class, MethodHandles.lookup());
        } catch (Exception e) {
            lk = MethodHandles.lookup();
        }
        LOOKUP = lk;
    }

    private static volatile ForkJoinPool recipePool;

    private static ForkJoinPool getPool() {
        ForkJoinPool pool = recipePool;
        if (pool == null || pool.isShutdown()) {
            synchronized (AdaptiveRecipeConverter.class) {
                pool = recipePool;
                if (pool == null || pool.isShutdown()) {
                    pool = new ForkJoinPool(
                            Math.max(2, Runtime.getRuntime().availableProcessors() - 1),
                            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                            (t, e) -> ComplexityAnalyzer.LOGGER.error("Recipe converter error", e),
                            true
                    );
                    recipePool = pool;
                }
            }
        }
        return pool;
    }

    private static final ConcurrentHashMap<Class<?>, ClassMeta> CLASS_META_CACHE = new ConcurrentHashMap<>(256);

    private static final ConcurrentHashMap<Class<?>, AdapterSnapshot> ADAPTER_CACHE = new ConcurrentHashMap<>(256);

    private static final ConcurrentHashMap<LearningKey, CompletableFuture<Accessor>> LEARNING_FUTURES = new ConcurrentHashMap<>(64);

    private static volatile MachineRegistry machineRegistry;

    private static final ThreadLocal<ArrayList<ItemStack>> TL_ITEM_LIST =
            ThreadLocal.withInitial(() -> new ArrayList<>(32));
    private static final ThreadLocal<ArrayList<FluidStack>> TL_FLUID_LIST =
            ThreadLocal.withInitial(() -> new ArrayList<>(16));
    private static final ThreadLocal<ArrayList<ChemicalOutput>> TL_CHEM_LIST =
            ThreadLocal.withInitial(() -> new ArrayList<>(16));
    private static final ThreadLocal<Set<Object>> TL_VISITED =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>(32)));

    private static <T> ArrayList<T> borrowList(ThreadLocal<ArrayList<T>> tl) {
        ArrayList<T> list = tl.get();
        list.clear();
        return list;
    }

    private static <T> List<T> snapshotAndReturn(ArrayList<T> borrowed) {
        if (borrowed.isEmpty()) return Collections.emptyList();
        List<T> result = List.copyOf(borrowed);
        borrowed.clear();
        return result;
    }

    static final class ClassMeta {
        final Map<String, Method> methodMap;
        final Map<String, MethodHandle> handleMap;
        final List<Field> allFields;
        final Map<String, Field> fieldMap;
        final RecordComponent[] recordComponents;
        final MethodHandle[] recordHandles;
        final boolean isRecord;
        final Method[] allMethods;

        ClassMeta(Class<?> clazz) {
            this.isRecord = clazz.isRecord();

            Method[] methods = clazz.getMethods();
            this.allMethods = methods;
            Map<String, Method> mMap = new HashMap<>(methods.length * 2);
            Map<String, MethodHandle> hMap = new HashMap<>(methods.length * 2);

            for (Method m : methods) {
                Method existing = mMap.get(m.getName());
                if (existing == null || (existing.getParameterCount() > 0 && m.getParameterCount() == 0)) {
                    mMap.put(m.getName(), m);
                    MethodHandle h = createHandle(m);
                    if (h != null) hMap.put(m.getName(), h);
                }
            }
            this.methodMap = Map.copyOf(mMap);
            this.handleMap = Map.copyOf(hMap);

            List<Field> fields = new ArrayList<>();
            Map<String, Field> fMap = new HashMap<>();
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    fields.add(f);
                    fMap.putIfAbsent(f.getName(), f);
                }
                current = current.getSuperclass();
            }
            this.allFields = List.copyOf(fields);
            this.fieldMap = Map.copyOf(fMap);

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
                MethodHandle raw = LOOKUP.unreflect(method);

                if (method.getParameterCount() == 0)
                    return raw.asType(MethodType.methodType(Object.class, Object.class));
                return raw;
            } catch (Exception e) {
                try {
                    MethodHandles.Lookup priv = MethodHandles.privateLookupIn(method.getDeclaringClass(), LOOKUP);
                    MethodHandle raw = priv.unreflect(method);
                    if (method.getParameterCount() == 0)
                        return raw.asType(MethodType.methodType(Object.class, Object.class));
                    return raw;
                } catch (Exception ignored) {
                    return null;
                }
            }
        }

        Method findMethod(String name) {
            return methodMap.get(name);
        }

        MethodHandle findHandle(String name) {
            return handleMap.get(name);
        }

        Field findField(String name) {
            return fieldMap.get(name);
        }

        Object invokeNoArg(String methodName, Object target) {
            MethodHandle h = handleMap.get(methodName);
            if (h == null) return null;
            try {
                return h.invokeExact(target);
            } catch (Throwable ignored) {
                return null;
            }
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

    private record LearningKey(Class<?> clazz, boolean isOutput, ResourceType type) {
    }

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
            Object[] args = prepareArguments(method, level);
            if (args == null) return null;
            Object[] all = new Object[1 + args.length];
            all[0] = recipe;
            System.arraycopy(args, 0, all, 1, args.length);
            return fullHandle.invokeWithArguments(all);
        }
    }

    static final class FieldAccessor implements Accessor {
        private final long offset;
        private final Field field;

        FieldAccessor(Field field) {
            this.field = field;
            long off = -1;
            try {
                var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
                @SuppressWarnings("deprecation")
                long fieldOffset = unsafe.objectFieldOffset(field);
                off = fieldOffset;
            } catch (Exception ignored) {
            }
            this.offset = off;
            if (off == -1) field.setAccessible(true);
        }

        @Override
        public Object extract(Object recipe, Level level) throws Throwable {
            if (offset != -1) {
                try {
                    var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                    unsafeField.setAccessible(true);
                    var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
                    return unsafe.getObject(recipe, offset);
                } catch (Exception ignored) {
                }
            }
            return field.get(recipe);
        }
    }

    public static void setMachineRegistry(MachineRegistry registry) {
        machineRegistry = registry;
    }

    public record ChemicalOutput(ResourceLocation id, long amount) {
    }

    public static List<RecipeNode> convertRecipesBatch(List<? extends Recipe<?>> recipes, Level level) {

        if (recipes.isEmpty()) return Collections.emptyList();

        ForkJoinPool pool = getPool();
        int size = recipes.size();

        @SuppressWarnings("unchecked")
        CompletableFuture<RecipeNode>[] futures = new CompletableFuture[size];

        for (int i = 0; i < size; i++) {
            final var recipe = recipes.get(i);
            futures[i] = CompletableFuture.supplyAsync(() -> convertRecipe(recipe, level), pool);
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(futures);
        try {
            all.get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            ComplexityAnalyzer.LOGGER.warn("Batch conversion timed out after {}s", BATCH_TIMEOUT_SECONDS);
            for (CompletableFuture<RecipeNode> f : futures) f.cancel(true);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Batch conversion error: {}", e.getMessage());
        }

        List<RecipeNode> results = new ArrayList<>(size);
        for (CompletableFuture<RecipeNode> f : futures) {
            if (f.isDone() && !f.isCompletedExceptionally() && !f.isCancelled()) {
                RecipeNode node = f.getNow(null);
                if (node != null) results.add(node);
            }
        }
        return results;
    }

    public static RecipeNode convertRecipe(Recipe<?> recipe, Level level) {
        List<ItemStack> itemOutputs = extractOutputs(recipe, level);
        List<FluidStack> fluidOutputs = extractFluidOutputs(recipe, level);

        if (itemOutputs.isEmpty()) itemOutputs = extractMekanismItemOutputs(recipe);
        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty()) return null;

        List<List<ItemStack>> itemInputs = extractInputs(recipe, level);
        List<List<FluidStack>> fluidInputs = extractFluidInputs(recipe, level);
        if (itemInputs.isEmpty() && fluidInputs.isEmpty()) return null;

        RecipeType<?> recipeType = recipe.getType();
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

        List<Ingredient> ingredients = new ArrayList<>(itemInputs.size());
        for (List<ItemStack> group : itemInputs) {
            if (!group.isEmpty()) ingredients.add(Ingredient.of(group.toArray(new ItemStack[0])));
        }

        RecipeCategory category = org.complexityanalyzer.graph.GraphBuilder.classifyRecipe(recipe, resultItem, ingredients);
        if (category == RecipeCategory.UNPROCESSABLE) return null;

        builder.recipeType(recipeType).category(category);

        for (List<ItemStack> group : itemInputs) {
            if (!group.isEmpty()) {
                builder.addIngredient(group.stream().map(ItemStack::getItem).distinct().toList(),
                        group.getFirst().getCount()
                );
            }
        }
        for (List<FluidStack> group : fluidInputs) {
            if (!group.isEmpty()) {
                builder.addFluidIngredient(group.stream().map(FluidStack::getFluid).distinct().toList(),
                        group.getFirst().getAmount()
                );
            }
        }

        RecipeNode node = builder.rawRecipe(recipe).build();
        if (node.getIngredients().isEmpty() && node.getFluidIngredients().isEmpty()) return null;
        return node;
    }

    public static List<ItemStack> extractOutputs(Object recipe, Level level) {
        Object actual = unwrap(recipe);
        Accessor acc = resolveAccessor(actual, true, ResourceType.ITEM, level);
        if (acc == null) return Collections.emptyList();
        try {
            Object raw = acc.extract(actual, level);
            ArrayList<ItemStack> buf = borrowList(TL_ITEM_LIST);
            collectItemStacks(raw, 0, buf);
            return snapshotAndReturn(buf);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    public static List<FluidStack> extractFluidOutputs(Object recipe, Level level) {
        Object actual = unwrap(recipe);
        Accessor acc = resolveAccessor(actual, true, ResourceType.FLUID, level);
        if (acc == null) return Collections.emptyList();
        try {
            Object raw = acc.extract(actual, level);
            ArrayList<FluidStack> buf = borrowList(TL_FLUID_LIST);
            collectFluidStacks(raw, 0, buf);
            return snapshotAndReturn(buf);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    public static List<List<ItemStack>> extractInputs(Object recipe, Level level) {
        Object actual = unwrap(recipe);
        ClassMeta meta = getMeta(actual.getClass());

        if (meta.isRecord) return extractItemInputsFromRecord(actual, meta);

        Accessor acc = resolveAccessor(actual, false, ResourceType.ITEM, level);
        if (acc == null) return Collections.emptyList();
        try {
            Object raw = acc.extract(actual, level);
            return collectItemStackGroups(raw, 0);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    public static List<List<FluidStack>> extractFluidInputs(Object recipe, Level level) {
        Object actual = unwrap(recipe);
        ClassMeta meta = getMeta(actual.getClass());

        if (meta.isRecord) return extractFluidInputsFromRecord(actual, meta);

        Accessor acc = resolveAccessor(actual, false, ResourceType.FLUID, level);
        if (acc == null) return Collections.emptyList();
        try {
            Object raw = acc.extract(actual, level);
            return collectFluidStackGroups(raw, 0);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    public static List<ChemicalOutput> extractChemicalInputs(Object recipe) {
        Object actual = unwrap(recipe);
        ClassMeta meta = getMeta(actual.getClass());

        if (meta.isRecord) return extractChemicalFromRecord(actual, meta, true);

        String[] methods = {"getChemicalInput", "getChemicalInputs", "getGasInput",
                "getGasInputs", "getLeftGasInput", "getRightGasInput",
                "getLeftInput", "getRightInput", "getInput"};

        ArrayList<ChemicalOutput> buf = borrowList(TL_CHEM_LIST);
        for (String name : methods) {
            MethodHandle h = meta.findHandle(name);
            if (h == null) continue;
            try {
                Object result = h.invokeExact(actual);
                if (result != null) {
                    collectChemicals(result, buf);
                    if (!buf.isEmpty()) return snapshotAndReturn(buf);
                }
            } catch (Throwable ignored) {
            }
        }
        return Collections.emptyList();
    }

    public static List<ChemicalOutput> extractChemicalOutputs(Object recipe, Level level) {
        Object actual = unwrap(recipe);
        ClassMeta meta = getMeta(actual.getClass());

        if (meta.isRecord) return extractChemicalFromRecord(actual, meta, false);

        Accessor acc = resolveAccessor(actual, true, ResourceType.CHEMICAL, level);
        if (acc == null) return Collections.emptyList();
        try {
            Object raw = acc.extract(actual, level);
            ArrayList<ChemicalOutput> buf = borrowList(TL_CHEM_LIST);
            collectChemicals(raw, buf);
            return snapshotAndReturn(buf);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    private static void collectItemStacks(Object obj, int depth, List<ItemStack> acc) {
        if (obj == null || depth > MAX_DEPTH) return;

        if (obj instanceof ItemStack stack) {
            if (!stack.isEmpty()) acc.add(stack);
            return;
        }
        if (obj instanceof Ingredient ingredient) {
            for (ItemStack s : ingredient.getItems()) {
                if (!s.isEmpty()) acc.add(s);
            }
            return;
        }

        String className = obj.getClass().getName();

        if (className.contains("ItemStackIngredient") || className.contains("ItemStackOutput")
                || className.contains("OutputIngredient")) {
            collectFromProviderMethods(obj, depth, acc);
            if (!acc.isEmpty()) return;
        }

        if (obj instanceof Collection<?> coll) {
            for (Object item : coll) collectItemStacks(item, depth + 1, acc);
            return;
        }
        if (obj.getClass().isArray()) {
            try {
                for (Object item : (Object[]) obj) collectItemStacks(item, depth + 1, acc);
            } catch (ClassCastException ignored) {
            }
            return;
        }

        ClassMeta meta = getMeta(obj.getClass());

        if (meta.isRecord) {
            for (int i = 0; i < meta.recordHandles.length; i++) {
                MethodHandle h = meta.recordHandles[i];
                if (h == null) continue;
                try {
                    collectItemStacks(h.invokeExact(obj), depth + 1, acc);
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        for (Method m : meta.allMethods) {
            if (m.getParameterCount() != 0) continue;
            String name = m.getName();
            if (isItemNotRelevantGetter(name)) continue;
            MethodHandle h = meta.findHandle(name);
            if (h == null) continue;
            try {
                Object result = h.invokeExact(obj);
                if (result != null && result != obj) {
                    int sizeBefore = acc.size();
                    collectItemStacks(result, depth + 1, acc);
                    if (acc.size() > sizeBefore) return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectFluidStacks(Object obj, int depth, List<FluidStack> acc) {
        if (obj == null || depth > MAX_DEPTH) return;

        if (obj instanceof FluidStack stack) {
            if (!stack.isEmpty()) acc.add(stack);
            return;
        }

        String className = obj.getClass().getName();
        if (isChemicalClassName(className)) {
            FluidStack converted = tryConvertToFluid(obj);
            if (converted != null && !converted.isEmpty()) {
                acc.add(converted);
                return;
            }
        }

        if (obj instanceof Collection<?> coll) {
            for (Object item : coll) collectFluidStacks(item, depth + 1, acc);
            return;
        }
        if (obj.getClass().isArray()) {
            try {
                for (Object item : (Object[]) obj) collectFluidStacks(item, depth + 1, acc);
            } catch (ClassCastException ignored) {
            }
            return;
        }

        ClassMeta meta = getMeta(obj.getClass());
        if (meta.isRecord) {
            for (MethodHandle h : meta.recordHandles) {
                if (h == null) continue;
                try {
                    collectFluidStacks(h.invokeExact(obj), depth + 1, acc);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void collectChemicals(Object obj, List<ChemicalOutput> acc) {
        if (obj == null) return;

        Set<Object> visited = TL_VISITED.get();
        try {
            collectChemicalsInner(obj, 0, acc, visited);
        } finally {
            visited.clear();
        }
    }

    private static void collectChemicalsInner(Object obj, int depth, List<ChemicalOutput> acc, Set<Object> visited) {
        if (obj == null || depth > MAX_DEPTH || !visited.add(obj)) return;

        if (!obj.getClass().isRecord()) {
            ChemicalOutput chem = tryExtractChemical(obj);
            if (chem != null) {
                acc.add(chem);
                return;
            }
        }

        if (obj instanceof Collection<?> coll) {
            for (Object item : coll) collectChemicalsInner(item, depth + 1, acc, visited);
            return;
        }
        if (obj.getClass().isArray()) {
            try {
                for (Object item : (Object[]) obj) collectChemicalsInner(item, depth + 1, acc, visited);
            } catch (ClassCastException ignored) {
            }
            return;
        }

        ClassMeta meta = getMeta(obj.getClass());
        if (meta.isRecord) {
            for (MethodHandle h : meta.recordHandles) {
                if (h == null) continue;
                try {
                    collectChemicalsInner(h.invokeExact(obj), depth + 1, acc, visited);
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        if (!isPrimitive(obj)) {
            for (Method m : meta.allMethods) {
                if (m.getParameterCount() != 0) continue;
                String name = m.getName();
                if (!isChemicalRelevantGetter(name)) continue;
                MethodHandle h = meta.findHandle(name);
                if (h == null) continue;
                try {
                    Object result = h.invokeExact(obj);
                    if (result != null && result != obj) collectChemicalsInner(result, depth + 1, acc, visited);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<List<ItemStack>> collectItemStackGroups(Object obj, int depth) {
        if (obj == null || depth > MAX_DEPTH) return Collections.emptyList();

        if (obj instanceof Ingredient ingredient) {
            ItemStack[] items = ingredient.getItems();
            List<ItemStack> stacks = new ArrayList<>(items.length);
            for (ItemStack s : items) {
                if (!s.isEmpty()) stacks.add(s);
            }
            return stacks.isEmpty() ? Collections.emptyList() : List.of(stacks);
        }

        String className = obj.getClass().getName();
        if (className.contains("ItemStackIngredient") || className.contains("ItemStackOutput")
                || className.contains("OutputIngredient")) {
            ArrayList<ItemStack> buf = borrowList(TL_ITEM_LIST);
            collectFromProviderMethods(obj, depth, buf);
            if (!buf.isEmpty()) {
                List<ItemStack> snapshot = List.copyOf(buf);
                buf.clear();
                return List.of(snapshot);
            }
        }

        ClassMeta meta = getMeta(obj.getClass());
        if (meta.isRecord) {
            List<List<ItemStack>> result = new ArrayList<>(4);
            for (int i = 0; i < meta.recordHandles.length; i++) {
                MethodHandle h = meta.recordHandles[i];
                if (h == null) continue;
                try {
                    Object value = h.invokeExact(obj);
                    if (value != null) {
                        List<List<ItemStack>> nested = collectItemStackGroups(value, depth + 1);
                        result.addAll(nested);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!result.isEmpty()) return result;

            ArrayList<ItemStack> flat = borrowList(TL_ITEM_LIST);
            collectItemStacks(obj, depth, flat);
            if (!flat.isEmpty()) {
                List<ItemStack> snapshot = List.copyOf(flat);
                flat.clear();
                return List.of(snapshot);
            }
        }

        if (obj instanceof Collection<?> coll && !coll.isEmpty()) {
            Object first = coll.iterator().next();
            if (first instanceof Ingredient) {
                List<List<ItemStack>> result = new ArrayList<>(coll.size());
                for (Object item : coll) {
                    if (item instanceof Ingredient ing) {
                        ItemStack[] items = ing.getItems();
                        List<ItemStack> stacks = new ArrayList<>(items.length);
                        for (ItemStack s : items) {
                            if (!s.isEmpty()) stacks.add(s);
                        }
                        if (!stacks.isEmpty()) result.add(stacks);
                    }
                }
                return result;
            }
            if (first instanceof ItemStack) {
                List<ItemStack> stacks = new ArrayList<>(coll.size());
                for (Object item : coll) {
                    if (item instanceof ItemStack s && !s.isEmpty()) stacks.add(s);
                }
                return stacks.isEmpty() ? Collections.emptyList() : List.of(stacks);
            }
            if (first instanceof Collection) {
                List<List<ItemStack>> result = new ArrayList<>();
                for (Object inner : coll) result.addAll(collectItemStackGroups(inner, depth + 1));
                return result;
            }
            ArrayList<ItemStack> buf = borrowList(TL_ITEM_LIST);
            for (Object item : coll) collectItemStacks(item, depth + 1, buf);
            if (!buf.isEmpty()) {
                List<ItemStack> snapshot = List.copyOf(buf);
                buf.clear();
                return List.of(snapshot);
            }
        }

        for (Method m : meta.allMethods) {
            if (m.getParameterCount() != 0) continue;
            String name = m.getName();
            if (isItemNotRelevantGetter(name)) continue;
            if (name.equals("getClass")) continue;
            MethodHandle h = meta.findHandle(name);
            if (h == null) continue;
            try {
                List<List<ItemStack>> found = collectItemStackGroups(h.invokeExact(obj), depth + 1);
                if (!found.isEmpty()) return found;
            } catch (Throwable ignored) {
            }
        }

        return Collections.emptyList();
    }

    private static List<List<FluidStack>> collectFluidStackGroups(Object obj, int depth) {
        if (obj == null || depth > MAX_DEPTH) return Collections.emptyList();

        String className = obj.getClass().getName();
        if (className.contains("FluidStackIngredient")) {
            ClassMeta meta = getMeta(obj.getClass());
            Object repr = meta.invokeNoArg("getRepresentations", obj);
            if (repr instanceof List<?> list) {
                List<FluidStack> stacks = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof FluidStack fs && !fs.isEmpty()) stacks.add(fs);
                }
                return stacks.isEmpty() ? Collections.emptyList() : List.of(stacks);
            }
        }

        if (obj instanceof Collection<?> coll && !coll.isEmpty()) {
            Object first = coll.iterator().next();
            if (first instanceof FluidStack) {
                List<FluidStack> stacks = new ArrayList<>(coll.size());
                for (Object item : coll) {
                    if (item instanceof FluidStack fs && !fs.isEmpty()) stacks.add(fs);
                }
                return stacks.isEmpty() ? Collections.emptyList() : List.of(stacks);
            }
            if (first instanceof Collection) {
                List<List<FluidStack>> result = new ArrayList<>();
                for (Object inner : coll) result.addAll(collectFluidStackGroups(inner, depth + 1));
                return result;
            }
            ArrayList<FluidStack> buf = borrowList(TL_FLUID_LIST);
            for (Object item : coll) collectFluidStacks(item, depth + 1, buf);
            if (!buf.isEmpty()) {
                List<FluidStack> snapshot = List.copyOf(buf);
                buf.clear();
                return List.of(snapshot);
            }
        }

        ClassMeta meta = getMeta(obj.getClass());
        if (meta.isRecord) {
            List<List<FluidStack>> result = new ArrayList<>(4);
            for (MethodHandle h : meta.recordHandles) {
                if (h == null) continue;
                try {
                    Object value = h.invokeExact(obj);
                    result.addAll(collectFluidStackGroups(value, depth + 1));
                } catch (Throwable ignored) {
                }
            }
            if (!result.isEmpty()) return result;

            ArrayList<FluidStack> flat = borrowList(TL_FLUID_LIST);
            collectFluidStacks(obj, depth, flat);
            if (!flat.isEmpty()) {
                List<FluidStack> snapshot = List.copyOf(flat);
                flat.clear();
                return List.of(snapshot);
            }
        }

        for (Method m : meta.allMethods) {
            if (m.getParameterCount() != 0) continue;
            if (!m.getName().startsWith("get") && !m.getName().startsWith("as")) continue;
            if (m.getName().equals("getClass")) continue;
            MethodHandle h = meta.findHandle(m.getName());
            if (h == null) continue;
            try {
                List<List<FluidStack>> found = collectFluidStackGroups(h.invokeExact(obj), depth + 1);
                if (!found.isEmpty()) return found;
            } catch (Throwable ignored) {
            }
        }

        return Collections.emptyList();
    }

    private static List<List<ItemStack>> extractItemInputsFromRecord(Object record, ClassMeta meta) {
        List<List<ItemStack>> results = new ArrayList<>(4);
        for (int i = 0; i < meta.recordComponents.length; i++) {
            String name = meta.recordComponents[i].getName();
            if (!isRecordItemInput(name)) continue;
            MethodHandle h = meta.recordHandles[i];
            if (h == null) continue;
            try {
                Object value = h.invokeExact(record);
                if (value != null) results.addAll(collectItemStackGroups(value, 0));
            } catch (Throwable ignored) {
            }
        }
        return results;
    }

    private static List<List<FluidStack>> extractFluidInputsFromRecord(Object record, ClassMeta meta) {
        List<List<FluidStack>> results = new ArrayList<>(4);
        for (int i = 0; i < meta.recordComponents.length; i++) {
            String name = meta.recordComponents[i].getName();
            if (!isRecordFluidInput(name)) continue;
            MethodHandle h = meta.recordHandles[i];
            if (h == null) continue;
            try {
                Object value = h.invokeExact(record);
                if (value != null) results.addAll(collectFluidStackGroups(value, 0));
            } catch (Throwable ignored) {
            }
        }
        return results;
    }

    private static List<ChemicalOutput> extractChemicalFromRecord(Object record, ClassMeta meta, boolean isInput) {
        ArrayList<ChemicalOutput> results = borrowList(TL_CHEM_LIST);
        for (int i = 0; i < meta.recordComponents.length; i++) {
            String name = meta.recordComponents[i].getName();
            if (isInput ? !isRecordChemicalInput(name) : !isRecordChemicalOutput(name)) continue;
            MethodHandle h = meta.recordHandles[i];
            if (h == null) continue;
            try {
                Object value = h.invokeExact(record);
                if (value != null) {
                    ChemicalOutput chem = tryExtractChemical(value);
                    if (chem != null) {
                        results.add(chem);
                    } else {
                        collectChemicals(value, results);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return snapshotAndReturn(results);
    }

    private static boolean isItemNotRelevantGetter(String name) {
        return !name.startsWith("get") && !name.startsWith("as") && !name.startsWith("to") && !name.contains("output")
                && !name.contains("Output") && !name.contains("result") && !name.contains("Result");
    }

    private static boolean isChemicalRelevantGetter(String name) {
        return (name.contains("Output") || name.contains("output") || name.contains("Chemical")
                || name.contains("chemical") || name.contains("Definition") || name.contains("definition"))
                && !name.equals("getClass") && !name.equals("toString") && !name.equals("hashCode")
                && !name.equals("getName");
    }

    private static boolean isRecordItemInput(String name) {
        return (name.contains("input") || name.contains("Input") || name.contains("ingredient"))
                && !name.toLowerCase(Locale.ROOT).contains("chemical") && !name.toLowerCase(Locale.ROOT).contains("gas")
                && !name.toLowerCase(Locale.ROOT).contains("fluid");
    }

    private static boolean isRecordFluidInput(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.contains("water") || lower.contains("fluid") || lower.contains("liquid"))
                && !lower.contains("output");
    }

    private static boolean isRecordChemicalInput(String name) {
        return name.contains("input") || name.contains("Input") || name.equals("superHeatedCoolant")
                || name.contains("ingredient") || name.contains("source");
    }

    private static boolean isRecordChemicalOutput(String name) {
        return name.contains("output") || name.contains("Output") || name.equals("steam")
                || name.equals("cooledCoolant") || name.contains("product") || name.contains("result");
    }

    private static boolean isChemicalClassName(String className) {
        return className.contains("Chemical") || className.contains("Gas") || className.contains("Slurry")
                || className.contains("Infusion") || className.contains("Pigment");
    }

    private static boolean isPrimitive(Object obj) {
        return obj instanceof String || obj instanceof Number || obj instanceof Boolean || obj instanceof Character
                || obj.getClass().isPrimitive();
    }

    private static Accessor resolveAccessor(Object recipe, boolean isOutput, ResourceType type, Level level) {
        Class<?> clazz = recipe.getClass();

        AdapterSnapshot snapshot = ADAPTER_CACHE.getOrDefault(clazz, AdapterSnapshot.EMPTY);
        Accessor existing = snapshot.get(isOutput, type);
        if (existing != null) return existing;

        LearningKey key = new LearningKey(clazz, isOutput, type);

        CompletableFuture<Accessor> future = LEARNING_FUTURES.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return learnAccessor(recipe, isOutput, type, level);
                    } finally {
                        LEARNING_FUTURES.remove(k);
                    }
                }, getPool())
        );

        try {
            Accessor accessor = future.get(SINGLE_LEARN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (accessor != null) {
                ADAPTER_CACHE.compute(clazz, (c, old) -> {
                    AdapterSnapshot base = old != null ? old : AdapterSnapshot.EMPTY;
                    return base.with(isOutput, type, accessor);
                });
            }
            return accessor;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Learning timeout for {}", clazz.getSimpleName());
            return null;
        }
    }

    private static Accessor learnAccessor(Object recipe, boolean isOutput, ResourceType type, Level level) {
        Method method = learnMethod(recipe, isOutput, type, level);
        if (method != null) {
            ClassMeta meta = getMeta(recipe.getClass());
            MethodHandle h = meta.findHandle(method.getName());
            if (h != null) return new FastHandleAccessor(h, method);
        }
        Field field = learnField(recipe, isOutput, type);
        if (field != null) return new FieldAccessor(field);
        return null;
    }

    private static Method learnMethod(Object recipe, boolean isOutput, ResourceType type, Level level) {
        String[] candidates = getCandidateMethods(isOutput, type);
        ClassMeta meta = getMeta(recipe.getClass());

        for (String name : candidates) {
            Method m = meta.findMethod(name);
            if (m != null && m.getParameterCount() <= 1 && validate(m, recipe, isOutput, type, level)) return m;
        }

        Set<String> candidateSet = Set.of(candidates);
        String[] wildcards = getWildcards(isOutput, type);

        for (String wildcard : wildcards) {
            for (Method m : meta.allMethods) {
                if (m.getParameterCount() > 1 || candidateSet.contains(m.getName())) continue;
                if (m.getName().equals("getClass") || m.getName().equals("toString")) continue;
                if (m.getName().toLowerCase(Locale.ROOT).contains(wildcard)) {
                    if (validate(m, recipe, isOutput, type, level)) return m;
                }
            }
        }
        return null;
    }

    private static Field learnField(Object recipe, boolean isOutput, ResourceType type) {
        ClassMeta meta = getMeta(recipe.getClass());
        String[] candidates = getCandidateFields(isOutput, type);

        for (String name : candidates) {
            Field f = meta.findField(name);
            if (f != null && validateField(f, recipe, isOutput, type)) return f;
        }

        List<String> keywords = isOutput ? OUTPUT_KEYWORDS : INPUT_KEYWORDS;
        for (Field f : meta.allFields) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            String lower = f.getName().toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String kw : keywords) {
                if (lower.contains(kw)) {
                    match = true;
                    break;
                }
            }
            if (match && validateField(f, recipe, isOutput, type)) return f;
        }
        return null;
    }

    private static boolean validate(Method method, Object recipe, boolean isOutput, ResourceType type, Level level) {
        try {
            Object result = invokeWithArgs(method, recipe, level);
            return validateResult(result, isOutput, type);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validateField(Field field, Object recipe, boolean isOutput, ResourceType type) {
        try {
            field.setAccessible(true);
            Object value = field.get(recipe);
            return validateResult(value, isOutput, type);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validateResult(Object result, boolean isOutput, ResourceType type) {
        Object resolved = resolveValue(result);
        if (resolved == null) return false;
        if (resolved != result) return validateResult(resolved, isOutput, type);

        if (isOutput) {
            if (type == ResourceType.ITEM) {
                ArrayList<ItemStack> buf = borrowList(TL_ITEM_LIST);
                collectItemStacks(resolved, 0, buf);
                boolean found = !buf.isEmpty();
                buf.clear();
                return found;
            } else {
                ArrayList<FluidStack> buf = borrowList(TL_FLUID_LIST);
                collectFluidStacks(resolved, 0, buf);
                boolean found = !buf.isEmpty();
                buf.clear();
                return found;
            }
        } else {
            if (type == ResourceType.ITEM) {
                return !collectItemStackGroups(resolved, 0).isEmpty();
            } else {
                return !collectFluidStackGroups(resolved, 0).isEmpty();
            }
        }
    }

    private static ChemicalOutput tryExtractChemical(Object obj) {
        if (obj == null) return null;
        String className = obj.getClass().getName();

        if (className.contains("ChemicalStackIngredient") || (className.contains("Ingredient")
                && className.contains("Chemical"))) {
            ClassMeta meta = getMeta(obj.getClass());
            Object repr = meta.invokeNoArg("getRepresentations", obj);
            if (repr instanceof List<?> list && !list.isEmpty()) return tryExtractChemical(list.getFirst());
            return null;
        }

        if (!isChemicalClassName(className)) return null;

        ClassMeta meta = getMeta(obj.getClass());

        long amount = 1000;
        for (String name : new String[]{"getAmount", "amount"}) {
            Object result = meta.invokeNoArg(name, obj);
            if (result instanceof Number num) {
                amount = num.longValue();
                break;
            }
        }

        ResourceLocation chemicalId = extractChemicalId(obj, meta);
        return chemicalId != null ? new ChemicalOutput(chemicalId, amount) : null;
    }

    private static ResourceLocation extractChemicalId(Object obj, ClassMeta meta) {
        Object regName = meta.invokeNoArg("getTypeRegistryName", obj);
        if (regName instanceof ResourceLocation rl) return rl;
        if (regName != null) {
            try {
                return ResourceLocation.parse(regName.toString());
            } catch (Exception ignored) {
            }
        }

        Object chemical = meta.invokeNoArg("getChemical", obj);
        if (chemical != null) {
            ClassMeta chemMeta = getMeta(chemical.getClass());
            Object rn = chemMeta.invokeNoArg("getRegistryName", chemical);
            if (rn instanceof ResourceLocation rl) return rl;
            if (rn != null) {
                try {
                    return ResourceLocation.parse(rn.toString());
                } catch (Exception ignored) {
                }
            }
            String str = chemical.toString();
            if (str.contains(":")) return parseResourceLocation(str);
        }

        return null;
    }

    private static FluidStack tryConvertToFluid(Object obj) {
        if (obj == null) return null;
        String simple = obj.getClass().getSimpleName();
        if (simple.contains("Slurry") || simple.contains("Pigment")) return null;

        ClassMeta meta = getMeta(obj.getClass());

        long amount = 1000;
        for (String name : new String[]{"getAmount", "amount"}) {
            Object result = meta.invokeNoArg(name, obj);
            if (result instanceof Number num) {
                amount = num.longValue();
                break;
            }
        }

        Object inner = obj;
        for (String name : new String[]{"getChemical", "chemical", "getGas", "gas", "getFluid", "fluid", "getType", "type"}) {
            Object result = meta.invokeNoArg(name, obj);
            if (result != null && result != obj) {
                inner = result;
                break;
            }
        }

        ClassMeta innerMeta = getMeta(inner.getClass());
        for (String name : new String[]{"toString", "getName", "name", "getRegistryName", "registryName", "getId", "id"}) {
            Object result = innerMeta.invokeNoArg(name, inner);
            if (result instanceof String str && str.contains(":")) {
                String clean = str.replaceAll(".*?([a-z0-9_]+:[a-z0-9_/]+).*", "$1");
                try {
                    ResourceLocation loc = ResourceLocation.parse(clean);
                    var fluid = BuiltInRegistries.FLUID.get(loc);
                    if (fluid != Fluids.EMPTY) return new FluidStack(fluid, (int) Math.min(amount, Integer.MAX_VALUE));
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public static RecipeType<?> extractRecipeType(Object recipe) {
        if (recipe == null) return null;
        Object actual = unwrap(recipe);

        if (actual instanceof Recipe<?> r) return r.getType();

        ClassMeta meta = getMeta(actual.getClass());

        if (meta.isRecord) return extractRecipeTypeFromRecord(actual, meta);

        for (String name : RECIPE_TYPE_METHODS) {
            Object value = meta.invokeNoArg(name, actual);
            RecipeType<?> type = coerceRecipeType(value);
            if (type != null) return type;
        }

        RecipeType<?> fromFields = extractRecipeTypeFromFields(actual, meta);
        if (fromFields != null) return fromFields;

        return extractRecipeTypeFromAccessors(actual);
    }

    private static final String[] RECIPE_TYPE_METHODS = {
            "getRecipeType", "recipeType", "getType", "type",
            "getRecipe", "recipe", "getJeiRecipeType", "jeiRecipeType",
            "getViewerType", "viewerType"
    };

    private static RecipeType<?> extractRecipeTypeFromRecord(Object record, ClassMeta meta) {
        for (int i = 0; i < meta.recordComponents.length; i++) {
            String name = meta.recordComponents[i].getName();
            if (name.equals("type") || name.equals("recipeType")) {
                MethodHandle h = meta.recordHandles[i];
                if (h == null) continue;
                try {
                    RecipeType<?> type = coerceRecipeType(h.invokeExact(record));
                    if (type != null) return type;
                } catch (Throwable ignored) {
                }
            }
        }

        if (machineRegistry != null) {
            for (int i = 0; i < meta.recordComponents.length; i++) {
                if (meta.recordComponents[i].getName().equals("id")) {
                    MethodHandle h = meta.recordHandles[i];
                    if (h == null) continue;
                    try {
                        Object value = h.invokeExact(record);
                        if (value instanceof ResourceLocation rl) {
                            RecipeType<?> type = findRecipeTypeViaRegistry(rl);
                            if (type != null) return type;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static RecipeType<?> extractRecipeTypeFromFields(Object actual, ClassMeta meta) {
        for (Field f : meta.allFields) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (!(lower.contains("recipetype") || lower.equals("type") || lower.contains("viewer"))) continue;
            try {
                f.setAccessible(true);
                RecipeType<?> type = coerceRecipeType(resolveValue(f.get(actual)));
                if (type != null) return type;
            } catch (Exception ignored) {
            }
        }

        if (meta.isRecord) {
            for (int i = 0; i < meta.recordComponents.length; i++) {
                String lower = meta.recordComponents[i].getName().toLowerCase(Locale.ROOT);
                if (!(lower.contains("recipetype") || lower.equals("type") || lower.contains("viewer"))) continue;
                MethodHandle h = meta.recordHandles[i];
                if (h == null) continue;
                try {
                    RecipeType<?> type = coerceRecipeType(resolveValue(h.invokeExact(actual)));
                    if (type != null) return type;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static RecipeType<?> extractRecipeTypeFromAccessors(Object actual) {
        AdapterSnapshot snap = ADAPTER_CACHE.get(actual.getClass());
        if (snap == null) return null;

        for (ResourceType rt : ResourceType.values()) {
            for (boolean output : new boolean[]{true, false}) {
                Accessor acc = snap.get(output, rt);
                if (acc == null) continue;
                try {
                    Object value = acc.extract(actual, null);
                    if (value == null) continue;
                    ClassMeta vm = getMeta(value.getClass());
                    for (String name : new String[]{"getRecipeType", "recipeType", "getType", "type"}) {
                        Object r = vm.invokeNoArg(name, value);
                        RecipeType<?> type = coerceRecipeType(r);
                        if (type != null) return type;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static RecipeType<?> findRecipeTypeViaRegistry(ResourceLocation recipeId) {
        if (machineRegistry == null) return null;
        String path = recipeId.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        String[] parts = path.split("/");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        String typeHint = parts[0];
        String namespace = recipeId.getNamespace();

        List<RecipeType<?>> candidates = new ArrayList<>(4);
        for (Map.Entry<ResourceKey<RecipeType<?>>, RecipeType<?>> entry : BuiltInRegistries.RECIPE_TYPE.entrySet()) {
            ResourceLocation typeId = entry.getKey().location();
            if (!typeId.getNamespace().equals(namespace) || !typeId.getPath().contains(typeHint)) continue;
            if (machineRegistry.getMachineForRecipe(entry.getValue()).isPresent()) {
                candidates.add(entry.getValue());
            }
        }

        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.getFirst();

        candidates.sort(Comparator.comparingInt(rt -> {
            ResourceLocation loc = BuiltInRegistries.RECIPE_TYPE.getKey(rt);
            return loc != null ? loc.getPath().length() : Integer.MAX_VALUE;
        }));
        return candidates.getFirst();
    }

    private static RecipeType<?> coerceRecipeType(Object value) {
        if (value instanceof RecipeType<?> rt) return rt;
        if (value instanceof Optional<?> opt && opt.isPresent()) return coerceRecipeType(opt.get());
        if (value instanceof ResourceLocation rl) return BuiltInRegistries.RECIPE_TYPE.get(rl);
        if (value instanceof String str) {
            try {
                return BuiltInRegistries.RECIPE_TYPE.get(ResourceLocation.parse(str));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<ItemStack> extractMekanismItemOutputs(Object recipe) {
        if (recipe == null) return Collections.emptyList();
        if (!recipe.getClass().getName().startsWith("mekanism")) return Collections.emptyList();

        ClassMeta meta = getMeta(recipe.getClass());

        MethodHandle defHandle = meta.findHandle("getOutputDefinition");
        if (defHandle != null) {
            try {
                Object definition = defHandle.invokeExact(recipe);
                if (definition != null) {
                    ArrayList<ItemStack> buf = borrowList(TL_ITEM_LIST);
                    collectItemStacks(definition, 0, buf);
                    if (!buf.isEmpty()) return snapshotAndReturn(buf);

                    collectFromProviderMethods(definition, 0, buf);
                    if (!buf.isEmpty()) return snapshotAndReturn(buf);
                }
            } catch (Throwable ignored) {
            }
        }

        ArrayList<ItemStack> buf = borrowList(TL_ITEM_LIST);
        for (String name : new String[]{"getPrimaryOutput", "getItemOutput", "getOutput", "getResult", "getOutputs",
                "primaryOutput", "itemOutput", "output", "result"}) {
            MethodHandle h = meta.findHandle(name);
            if (h == null) continue;
            try {
                Object result = h.invokeExact(recipe);
                if (result != null) {
                    collectItemStacks(result, 0, buf);
                    if (!buf.isEmpty()) return snapshotAndReturn(buf);
                }
            } catch (Throwable ignored) {
            }
        }

        return Collections.emptyList();
    }

    private static void collectFromProviderMethods(Object provider, int depth, List<ItemStack> acc) {
        ClassMeta meta = getMeta(provider.getClass());
        for (String name : PROVIDER_METHODS) {
            Object result = meta.invokeNoArg(name, provider);
            if (result != null) {
                int before = acc.size();
                collectItemStacks(result, depth + 1, acc);
                if (acc.size() > before) return;
            }
        }
    }

    private static final String[] PROVIDER_METHODS = {
            "getOutput", "getOutputs", "getRepresentations", "getDefinition", "getItem", "getResult"
    };

    private static Object unwrap(Object obj) {
        return obj instanceof RecipeHolder<?> h ? h.value() : obj;
    }

    private static Object resolveValue(Object value) {
        if (value instanceof Optional<?> opt) return opt.orElse(null);
        if (value instanceof Supplier<?> sup) {
            try {
                return sup.get();
            } catch (Exception ignored) {
                return null;
            }
        }
        return value;
    }

    private static Object invokeWithArgs(Method method, Object target, Level level) throws Exception {
        Object[] args = prepareArguments(method, level);
        if (args == null) return null;

        ClassMeta meta = getMeta(method.getDeclaringClass());
        MethodHandle h = meta.findHandle(method.getName());

        if (h != null) {
            try {
                if (args.length == 0) return h.invokeExact(target);
                Object[] all = new Object[1 + args.length];
                all[0] = target;
                System.arraycopy(args, 0, all, 1, args.length);
                return h.invokeWithArguments(all);
            } catch (Throwable t) {
                if (t instanceof Exception ex) throw ex;
                throw new RuntimeException(t);
            }
        }
        return method.invoke(target, args);
    }

    private static Object[] prepareArguments(Method method, Level level) {
        int count = method.getParameterCount();
        if (count == 0) return new Object[0];

        Object[] args = new Object[count];
        Object registryAccess = level != null ? level.registryAccess() : null;
        Class<?>[] types = method.getParameterTypes();

        for (int i = 0; i < count; i++) {
            Class<?> pt = types[i];
            if (pt.isPrimitive()) {
                args[i] = primitiveDefault(pt);
                if (args[i] == null) return null;
            } else if (level != null && pt.isAssignableFrom(level.getClass())) {
                args[i] = level;
            } else if (registryAccess != null && (pt.isInstance(registryAccess)
                    || pt.getName().contains("RegistryAccess") || pt.getName().contains("HolderLookup"))) {
                args[i] = registryAccess;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static Object primitiveDefault(Class<?> p) {
        if (p == boolean.class) return Boolean.FALSE;
        if (p == int.class) return 0;
        if (p == long.class) return 0L;
        if (p == float.class) return 0F;
        if (p == double.class) return 0D;
        if (p == byte.class) return (byte) 0;
        if (p == short.class) return (short) 0;
        if (p == char.class) return (char) 0;
        return null;
    }

    private static ResourceLocation parseResourceLocation(String str) {
        if (str == null || !str.contains(":")) return null;
        String clean = str.replaceAll(".*?([a-z0-9_]+:[a-z0-9_/]+).*", "$1");
        if (!clean.contains(":")) return null;
        try {
            return ResourceLocation.parse(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private static final List<String> OUTPUT_KEYWORDS = List.of(
            "output", "result", "product", "produce", "yield", "generate", "reward", "primary", "secondary", "byproduct"
    );
    private static final List<String> INPUT_KEYWORDS = List.of(
            "input", "ingredient", "require", "consume", "use", "need", "supply", "source", "catalyst", "cost"
    );

    private static String[] getCandidateMethods(boolean isOutput, ResourceType type) {
        if (isOutput) {
            return switch (type) {
                case ITEM -> ITEM_OUTPUT_METHODS;
                case FLUID -> FLUID_OUTPUT_METHODS;
                case CHEMICAL -> CHEMICAL_OUTPUT_METHODS;
            };
        }
        return switch (type) {
            case ITEM -> ITEM_INPUT_METHODS;
            case FLUID -> FLUID_INPUT_METHODS;
            case CHEMICAL -> CHEMICAL_INPUT_METHODS;
        };
    }

    private static String[] getWildcards(boolean isOutput, ResourceType type) {
        if (isOutput) {
            return switch (type) {
                case ITEM -> new String[]{"item", "stack", "output", "result", "produce", "craft", "create", "yield",
                        "generate"};
                case FLUID -> new String[]{"fluid", "liquid", "output", "result"};
                case CHEMICAL -> new String[]{"gas", "chemical", "output", "result"};
            };
        }
        return switch (type) {
            case ITEM -> new String[]{"item", "stack", "input", "ingredient", "solid"};
            case FLUID -> new String[]{"fluid", "liquid", "input", "ingredient"};
            case CHEMICAL -> new String[]{"gas", "chemical", "input", "ingredient"};
        };
    }

    private static String[] getCandidateFields(boolean isOutput, ResourceType type) {
        if (isOutput) {
            return switch (type) {
                case ITEM -> new String[]{"result", "results", "output", "outputs", "product", "products", "mainOutput",
                        "secondaryOutput", "bonusOutput", "primaryOutput", "byproduct", "outputDefinition"};
                case FLUID -> new String[]{
                        "fluidOutput", "fluidOutputs", "outputFluid", "outputFluids", "liquidOutput"};
                case CHEMICAL -> new String[]{"gasOutput", "chemicalOutput", "output", "outputDefinition",
                        "leftGasOutput", "rightGasOutput"};
            };
        }
        return switch (type) {
            case ITEM -> new String[]{"inputs", "input", "ingredient", "ingredients", "itemInput", "itemInputs",
                    "stackInput", "solidInput", "inputDefinition"};
            case FLUID -> new String[]{"fluidInput", "fluidInputs", "inputFluid", "inputFluids", "liquidInput"};
            case CHEMICAL -> new String[]{"gasInput", "chemicalInput", "input", "inputDefinition", "leftGasInput",
                    "rightGasInput"};
        };
    }

    private static final String[] ITEM_OUTPUT_METHODS = {
            "getResultItem", "getResultItems", "getItemOutput", "getItemOutputs",
            "itemOutput", "itemOutputs", "stackOutput", "stackOutputs", "getStack", "getStacks",
            "outputItem", "outputItems", "resultStack", "resultStacks", "producedItem", "producedItems",
            "getOutputStack", "getOutput", "getOutputs", "getResult", "getResults", "getProduct", "getProducts",
            "output", "outputs", "result", "results", "product", "products", "fetchOutput", "fetchOutputs",
            "retrieveOutput", "retrieveOutputs", "produce", "produces", "produced", "getProduce", "create", "creates",
            "getCreate", "make", "makes", "getMake", "yield", "getYield", "generate", "generated", "getGenerate",
            "getProcessingOutput", "getRecipeOutput", "getMainOutput", "getSecondaryOutput", "getBonusOutput",
            "getPrimaryOutput", "getByproduct", "getByproducts", "getResultDefinition", "getOutputDefinition",
            "getOutputData", "getResultData", "getOutputSlot", "getOutputSlots", "getOutputContainer",
            "getOutputContents", "getOutputChemical", "getOutputChemicals", "getOutputGas", "getOutputGases"
    };

    private static final String[] FLUID_OUTPUT_METHODS = {
            "getFluidOutput", "getFluidOutputs", "outputFluid", "outputFluids", "getOutputFluids", "getFluidResult",
            "getFluidResults", "fetchFluidOutput", "retrieveFluidOutput", "fluidOutput", "fluidOutputs",
            "producedFluid", "producedFluids", "resultFluid", "outputsFluid", "getOutputFluid"
    };

    private static final String[] CHEMICAL_OUTPUT_METHODS = {
            "getOutput", "getOutputDefinition", "getChemicalOutput", "getChemicalOutputs", "getGasOutput",
            "getGasOutputs", "chemicalOutput", "gasOutput", "getLeftGasOutput", "getRightGasOutput", "getLeftOutput",
            "getRightOutput"
    };

    private static final String[] ITEM_INPUT_METHODS = {
            "getInputItem", "getInputItems", "getItemInput", "getItemInputs", "inputItem", "inputItems", "itemInput",
            "itemInputs", "stackInput", "stackInputs", "getInputStack", "getInputStacks", "ingredientItem",
            "ingredientItems", "getIngredientItem", "getIngredientItems", "getIngredientStack", "getInput",
            "getInputs", "getIngredient", "getIngredients", "input", "inputs", "ingredient", "ingredients",
            "getInputSolid", "inputSolid", "solidInput"
    };

    private static final String[] FLUID_INPUT_METHODS = {
            "getFluidInput", "getFluidInputs", "getInputFluid", "getInputFluids", "inputFluid", "inputFluids",
            "fluidInput", "fluidInputs", "getFluidIngredient", "getFluidIngredients", "fetchFluidInput",
            "retrieveFluidInput", "ingredientFluid", "ingredientFluids"
    };

    private static final String[] CHEMICAL_INPUT_METHODS = {
            "getInput", "getInputDefinition", "getChemicalInput", "getChemicalInputs", "getGasInput", "getGasInputs",
            "chemicalInput", "gasInput", "getLeftGasInput", "getRightGasInput"
    };

    public static void shutdown() {
        ForkJoinPool pool = recipePool;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void clearCaches() {
        CLASS_META_CACHE.clear();
        ADAPTER_CACHE.clear();
        LEARNING_FUTURES.clear();
    }
}