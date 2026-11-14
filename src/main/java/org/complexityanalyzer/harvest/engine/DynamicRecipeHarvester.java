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

package org.complexityanalyzer.harvest.engine;

import com.google.common.collect.ImmutableCollection;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import org.complexityanalyzer.harvest.inspector.StandardRecipeMethods;
import org.complexityanalyzer.util.ParallelUtils;
import org.complexityanalyzer.util.ProbeScope;
import org.complexityanalyzer.util.Scope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.complexityanalyzer.util.LogMarkers.DYNAMIC_RECIPE_HARVESTER;

public final class DynamicRecipeHarvester {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ThreadLocal<ActiveProbe> ACTIVE_PROBE = new ThreadLocal<>();

    private DynamicRecipeHarvester() {
    }

    @Nullable
    public static ActiveProbe getActiveProbe() {
        return ACTIVE_PROBE.get();
    }

    public static void harvest(RecipeGraph graph, Level level, ObjectSet<ResourceLocation> knownRecipeIds) {
        try (var ignored = ProbeScope.open()) {
            ComplexityAnalyzer.LOGGER.debug(DYNAMIC_RECIPE_HARVESTER, "Starting autonomous dynamic recipe probe...");

            var recipeManager = level.getRecipeManager();
            var inputTypeMap = getTypeMap(recipeManager);
            ComplexityAnalyzer.LOGGER.debug(DYNAMIC_RECIPE_HARVESTER, "Discovered {} recipe type input mappings for dynamic probe.", inputTypeMap.size());

            var allItems = GameRegistryManager.getAllItems();
            if (allItems.isEmpty()) {
                ComplexityAnalyzer.LOGGER.info(DYNAMIC_RECIPE_HARVESTER, "No registered items, skipping dynamic probe.");
                return;
            }

            var byInputClass = new Object2ObjectLinkedOpenHashMap<Class<?>, ObjectArrayList<RecipeType<?>>>();
            for (var entry : inputTypeMap.object2ObjectEntrySet()) {
                var type = entry.getKey();
                for (var inputClass : entry.getValue()) {
                    byInputClass.computeIfAbsent(inputClass, k -> new ObjectArrayList<>()).add(type);
                }
            }

            var pendingNodes = new ConcurrentHashMap<ResourceLocation, RecipeNode>();
            var ctx = new ProbeContext(recipeManager, level, new FastHarvester(), allItems, knownRecipeIds,
                    ConcurrentHashMap.newKeySet(), pendingNodes, new AtomicInteger(), new AtomicInteger());
            var activeProbe = new ActiveProbe(knownRecipeIds);

            for (var entry : byInputClass.object2ObjectEntrySet()) {
                var creator = resolveCreator(entry.getKey());
                if (creator == null) continue;
                probeInputGroup(creator, entry.getValue().toArray(new RecipeType<?>[0]), ctx, activeProbe);
            }

            for (var node : pendingNodes.values()) graph.addRecipe(node);
            ComplexityAnalyzer.LOGGER.info(DYNAMIC_RECIPE_HARVESTER, "Autonomous dynamic probe complete: discovered {} new recipes across {} dynamic type(s).",
                    ctx.addedCount().get(), ctx.dynamicTypes().get());
        }
    }

    private static void probeInputGroup(InputCreator creator, RecipeType<?>[] types, ProbeContext ctx, ActiveProbe activeProbe) {
        int typeCount = types.length;
        var foundByType = new AtomicIntegerArray(typeCount);

        var allItems = ctx.allItems();
        int totalItems = allItems.size();
        int threads = Math.max(1, ThreadPoolManager.getInstance().getParallelism());
        int chunkSize = Math.clamp((long) totalItems / (threads * 4L), 32, 256);
        int chunkCount = (totalItems + chunkSize - 1) / chunkSize;

        ParallelUtils.forRange(0, chunkCount, 1, chunkIdx -> {
            int start = chunkIdx * chunkSize;
            int end = Math.min(start + chunkSize, totalItems);

            ACTIVE_PROBE.set(activeProbe);
            try (var ignored = Scope.of(ACTIVE_PROBE::remove)) {
                for (int i = start; i < end; i++) {
                    RecipeInput input;
                    try {
                        var stack = allItems.get(i).getDefaultInstance();
                        if (stack.isEmpty()) continue;
                        var rawInput = creator.create(stack);
                        if (!(rawInput instanceof RecipeInput ri) || ri.isEmpty()) continue;
                        input = ri;
                    } catch (Throwable ignoredErr) {
                        continue;
                    }

                    for (int t = 0; t < typeCount; t++) {
                        try {
                            var recipes = queryRecipes(ctx.recipeManager(), types[t], input, ctx.level());
                            if (recipes.isEmpty()) continue;

                            for (var holder : recipes) {
                                if (ctx.known().contains(holder.id())) continue;
                                if (foundByType.get(t) == 0) foundByType.set(t, 1);
                                harvestAndAdd(holder, ctx);
                            }
                        } catch (Throwable ignoredErr) {
                        }
                    }
                }
            }
        });

        for (int t = 0; t < typeCount; t++) {
            if (foundByType.get(t) != 0) ctx.dynamicTypes().incrementAndGet();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> queryRecipes(RecipeManager manager, RecipeType<?> type, RecipeInput input, Level level) {
        return (List<RecipeHolder<?>>) (List<?>) manager.getRecipesFor((RecipeType) type, input, level);
    }

    private static void harvestAndAdd(RecipeHolder<?> holder, ProbeContext ctx) {
        if (!ctx.discovered().add(holder.id())) return;
        try {
            var items = ctx.harvester().harvest(holder.value(), ctx.level());
            var node = HarvestedRecipeConverter.convert(items, ctx.level());
            if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty())) {
                ctx.pendingNodes().put(holder.id(), node);
                ctx.addedCount().incrementAndGet();
            }
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error(DYNAMIC_RECIPE_HARVESTER, "Error harvesting recipe ID={}", holder.id(), t);
        }
    }

    private static Class<?> getRecipeInputClass(Class<?> recipeClass) {
        Class<?> bestParam = null;
        var methods = recipeClass.getMethods();
        for (var m : methods) {
            if (m.getName().equals(StandardRecipeMethods.ASSEMBLE) && m.getParameterCount() == 2) {
                var paramType = m.getParameterTypes()[0];
                if (RecipeInput.class.isAssignableFrom(paramType)) {
                    if (paramType != RecipeInput.class) return paramType;
                    bestParam = RecipeInput.class;
                }
            }
        }
        return bestParam;
    }

    private static @NotNull Object2ObjectMap<RecipeType<?>, ObjectSet<Class<?>>> getTypeMap(RecipeManager recipeManager) {
        var inputTypeMap = new Object2ObjectLinkedOpenHashMap<RecipeType<?>, ObjectSet<Class<?>>>();
        var classCache = new Object2ObjectOpenHashMap<Class<?>, Class<?>>();
        for (var holder : recipeManager.getRecipes()) {
            var type = holder.value().getType();
            var recipeClass = holder.value().getClass();
            var inputClass = classCache.computeIfAbsent(recipeClass, DynamicRecipeHarvester::getRecipeInputClass);
            if (inputClass == null) continue;
            inputTypeMap.computeIfAbsent(type, k -> new ObjectOpenHashSet<>()).add(inputClass);
        }

        for (var classes : inputTypeMap.values()) if (classes.size() > 1) classes.remove(RecipeInput.class);
        return inputTypeMap;
    }

    private static InputCreator resolveCreator(Class<?> inputClass) {
        if (inputClass == CraftingInput.class) return stack -> CraftingInput.of(1, 1, ObjectLists.singleton(stack));
        if (inputClass == SingleRecipeInput.class) return SingleRecipeInput::new;

        var methods = inputClass.getMethods();
        for (var m : methods) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals("of")
                    || !inputClass.isAssignableFrom(m.getReturnType())) continue;
            try {
                int pc = m.getParameterCount();
                var pt = m.getParameterTypes();
                if (pc == 1 && pt[0].isAssignableFrom(ItemStack.class)) {
                    var mh = unreflect(m).asType(MethodType.methodType(Object.class, ItemStack.class));
                    return mh::invokeExact;
                }
                if (pc == 3 && pt[0] == int.class && pt[1] == int.class && List.class.isAssignableFrom(pt[2])) {
                    var mh = unreflect(m).asType(MethodType.methodType(Object.class, int.class, int.class, List.class));
                    return stack -> mh.invokeExact(1, 1, (List<ItemStack>) ObjectLists.singleton(stack));
                }
            } catch (Throwable ignored) {
            }
        }

        var constructors = inputClass.getDeclaredConstructors();
        for (var c : constructors) {
            try {
                int pc = c.getParameterCount();
                var pt = c.getParameterTypes();
                if (pc == 1 && pt[0].isAssignableFrom(ItemStack.class)) {
                    var mh = unreflectConstructor(c).asType(MethodType.methodType(Object.class, ItemStack.class));
                    return mh::invokeExact;
                }
                if (pc == 3 && pt[0] == int.class && pt[1] == int.class && List.class.isAssignableFrom(pt[2])) {
                    var mh = unreflectConstructor(c).asType(MethodType.methodType(Object.class, int.class, int.class, List.class));
                    return stack -> mh.invokeExact(1, 1, (List<ItemStack>) ObjectLists.singleton(stack));
                }
                if (pc == 1 && List.class.isAssignableFrom(pt[0])) {
                    var mh = unreflectConstructor(c).asType(MethodType.methodType(Object.class, List.class));
                    return stack -> mh.invokeExact((List<ItemStack>) ObjectLists.singleton(stack));
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static MethodHandle unreflect(Method m) throws IllegalAccessException {
        m.setAccessible(true);
        try {
            return MethodHandles.privateLookupIn(m.getDeclaringClass(), LOOKUP).unreflect(m);
        } catch (IllegalAccessException e) {
            return LOOKUP.unreflect(m);
        }
    }

    private static MethodHandle unreflectConstructor(Constructor<?> c) throws IllegalAccessException {
        c.setAccessible(true);
        try {
            return MethodHandles.privateLookupIn(c.getDeclaringClass(), LOOKUP).unreflectConstructor(c);
        } catch (IllegalAccessException e) {
            return LOOKUP.unreflectConstructor(c);
        }
    }

    @FunctionalInterface
    private interface InputCreator {
        Object create(ItemStack stack) throws Throwable;
    }

    public static final class ActiveProbe {
        private final ObjectSet<ResourceLocation> known;
        private final ConcurrentHashMap<RecipeType<?>, FilteredEntry> cache = new ConcurrentHashMap<>();

        private ActiveProbe(ObjectSet<ResourceLocation> known) {
            this.known = known;
        }

        @SuppressWarnings("unchecked")
        public <T extends Recipe<?>> Collection<RecipeHolder<T>> filterKnown(RecipeType<?> type, Collection<RecipeHolder<T>> raw) {
            var entry = cache.get(type);
            if (entry != null && entry.rawRef() == raw) return (Collection<RecipeHolder<T>>) entry.filtered();
            var filtered = filterList(raw);
            if (raw instanceof ImmutableCollection<?> || raw instanceof ObjectImmutableList<?>)
                cache.put(type, new FilteredEntry(raw, filtered));
            return filtered;
        }

        private <T extends Recipe<?>> ObjectList<RecipeHolder<T>> filterList(Collection<RecipeHolder<T>> raw) {
            ObjectArrayList<RecipeHolder<T>> out = null;
            for (var holder : raw) {
                if (!known.contains(holder.id())) {
                    if (out == null) out = new ObjectArrayList<>(4);
                    out.add(holder);
                }
            }
            return out != null ? out : ObjectLists.emptyList();
        }

        private record FilteredEntry(Collection<?> rawRef, Collection<?> filtered) {
        }
    }

    private record ProbeContext(
            RecipeManager recipeManager,
            Level level,
            FastHarvester harvester,
            ObjectList<Item> allItems,
            ObjectSet<ResourceLocation> known,
            ConcurrentHashMap.KeySetView<ResourceLocation, Boolean> discovered,
            ConcurrentHashMap<ResourceLocation, RecipeNode> pendingNodes,
            AtomicInteger addedCount,
            AtomicInteger dynamicTypes) {
    }
}