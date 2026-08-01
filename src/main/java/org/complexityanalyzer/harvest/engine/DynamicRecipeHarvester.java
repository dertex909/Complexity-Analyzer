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

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.util.ParallelUtils;
import org.complexityanalyzer.util.ProbeScope;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DynamicRecipeHarvester {

    private DynamicRecipeHarvester() {
    }

    public static void harvest(RecipeGraph graph, Level level, ObjectSet<ResourceLocation> knownRecipeIds) {
        try (var ignored = ProbeScope.open()) {
            ComplexityAnalyzer.LOGGER.debug("[Harvest] Starting autonomous dynamic recipe probe...");

            var recipeManager = level.getRecipeManager();
            var inputTypeMap = getTypeMap(recipeManager);
            ComplexityAnalyzer.LOGGER.debug("[Harvest] Discovered {} recipe type input mappings for dynamic probe.", inputTypeMap.size());

            var allItems = GameRegistryManager.getAllItems();
            if (allItems.isEmpty()) {
                ComplexityAnalyzer.LOGGER.info("[Harvest] No registered items, skipping dynamic probe.");
                return;
            }

            int detectionSampleSize = ComplexityConfig.DETECTION_SAMPLE_SIZE.get();

            var ctx = new ProbeContext(
                    recipeManager, level, graph, new FastHarvester(), allItems,
                    knownRecipeIds, ConcurrentHashMap.newKeySet(), new AtomicInteger(), new AtomicInteger(), detectionSampleSize
            );

            var types = new ObjectArrayList<RecipeType<?>>(inputTypeMap.size());
            var classesList = new ObjectArrayList<ObjectSet<Class<?>>>(inputTypeMap.size());
            for (var entry : inputTypeMap.object2ObjectEntrySet()) {
                types.add(entry.getKey());
                classesList.add(entry.getValue());
            }

            int typeCount = types.size();
            if (typeCount == 1) {
                probeType(types.getFirst(), classesList.getFirst(), ctx);
            } else if (typeCount > 1) {
                ParallelUtils.forRange(0, typeCount, 1, i -> probeType(types.get(i), classesList.get(i), ctx));
            }

            ComplexityAnalyzer.LOGGER.info("[Harvest] Autonomous dynamic probe complete: discovered {} new recipes across {} dynamic type(s).",
                    ctx.addedCount().get(), ctx.dynamicTypes().get());
        }
    }

    private static void probeType(RecipeType<?> type, ObjectSet<Class<?>> inputClasses, ProbeContext ctx) {
        for (var inputClass : inputClasses) probeInputClass(type, inputClass, ctx);
    }

    private static void probeInputClass(RecipeType<?> type, Class<?> inputClass, ProbeContext ctx) {
        var creator = resolveCreator(inputClass);
        if (creator == null) return;

        int detectedAt = detectDynamic(type, creator, ctx);
        if (detectedAt < 0) return;

        fullScan(type, creator, ctx);
        ctx.dynamicTypes().incrementAndGet();
    }

    private static int detectDynamic(RecipeType<?> type, InputCreator creator, ProbeContext ctx) {
        int n = ctx.allItems().size();
        int detectLimit = Math.min(n, ctx.detectionSampleSize());
        for (int d = 0; d < detectLimit; d++) {
            int idx = (int) ((long) d * n / detectLimit);
            var recipes = getRecipesFor(ctx.allItems().get(idx), creator, type, ctx);
            for (var holder : recipes) {
                if (!ctx.known().contains(holder.id())) return idx;
            }
        }
        return -1;
    }

    private static void fullScan(RecipeType<?> type, InputCreator creator, ProbeContext ctx) {
        var allItems = ctx.allItems();
        for (var allItem : allItems) {
            var recipes = getRecipesFor(allItem, creator, type, ctx);
            for (var holder : recipes) {
                if (!ctx.known().contains(holder.id())) harvestAndAdd(holder, ctx);
            }
        }
    }

    private static void harvestAndAdd(RecipeHolder<?> holder, ProbeContext ctx) {
        if (!ctx.discovered().add(holder.id())) return;
        try {
            var items = ctx.harvester().harvest(holder.value(), ctx.level());
            var node = HarvestedRecipeConverter.convert(items, ctx.level());
            if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty()
                    || !node.getChemicalIngredients().isEmpty())) {
                ctx.graph().addRecipe(node);
                ctx.addedCount().incrementAndGet();
            }
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error("[Harvest:Dynamic] Error harvesting recipe ID={}", holder.id(), t);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> getRecipesFor(Item item, InputCreator creator, RecipeType<?> type, ProbeContext ctx) {
        try {
            var stack = item.getDefaultInstance();
            if (stack.isEmpty()) return Collections.emptyList();
            var input = creator.create(stack);
            if (input == null) return Collections.emptyList();
            return ctx.recipeManager().getRecipesFor((RecipeType) type, (RecipeInput) input, ctx.level());
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static Class<?> getRecipeInputClass(Class<?> recipeClass) {
        Class<?> bestParam = null;
        var methods = recipeClass.getMethods();
        for (var m : methods) {
            if (m.getName().equals("assemble") && m.getParameterCount() == 2) {
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
        var methods = inputClass.getMethods();
        for (var m : methods) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals("of")
                    || !inputClass.isAssignableFrom(m.getReturnType())) continue;
            try {
                int pc = m.getParameterCount();
                Class<?>[] pt = m.getParameterTypes();
                if (pc == 1 && pt[0].isAssignableFrom(ItemStack.class)) return stack -> m.invoke(null, stack);
                if (pc == 3 && pt[0] == int.class && pt[1] == int.class && List.class.isAssignableFrom(pt[2])) {
                    return stack -> m.invoke(null, 1, 1, List.of(stack));
                }
            } catch (Throwable ignored) {
            }
        }

        var constructors = inputClass.getDeclaredConstructors();
        for (var c : constructors) {
            try {
                int pc = c.getParameterCount();
                Class<?>[] pt = c.getParameterTypes();
                if (pc == 1 && pt[0].isAssignableFrom(ItemStack.class)) {
                    c.setAccessible(true);
                    return c::newInstance;
                }
                if (pc == 3 && pt[0] == int.class && pt[1] == int.class && List.class.isAssignableFrom(pt[2])) {
                    c.setAccessible(true);
                    return stack -> c.newInstance(1, 1, List.of(stack));
                }
                if (pc == 1 && List.class.isAssignableFrom(pt[0])) {
                    c.setAccessible(true);
                    return stack -> c.newInstance(List.of(stack));
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface InputCreator {
        Object create(ItemStack stack) throws Throwable;
    }

    private record ProbeContext(
            RecipeManager recipeManager,
            Level level,
            RecipeGraph graph,
            FastHarvester harvester,
            ObjectList<Item> allItems,
            ObjectSet<ResourceLocation> known,
            Set<ResourceLocation> discovered,
            AtomicInteger addedCount,
            AtomicInteger dynamicTypes,
            int detectionSampleSize) {
    }
}