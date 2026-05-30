package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeGraph;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DynamicRecipeHarvester {

    private DynamicRecipeHarvester() {
    }

    @FunctionalInterface
    private interface InputCreator {
        Object create(ItemStack stack) throws Throwable;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void harvest(RecipeGraph graph, Level level, ObjectSet<ResourceLocation> knownRecipeIds) {
        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting autonomous dynamic recipe probe...");
        var recipeManager = level.getRecipeManager();

        final var inputTypeMap = getTypeMap(recipeManager);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Discovered {} recipe type input mappings for dynamic probe.", inputTypeMap.size());

        final Set<ResourceLocation> safeKnown = ConcurrentHashMap.newKeySet();
        safeKnown.addAll(knownRecipeIds);

        final Set<ResourceLocation> discovered = ConcurrentHashMap.newKeySet();
        final var harvester = new FastHarvester();
        final AtomicInteger addedCount = new AtomicInteger(0);

        inputTypeMap.entrySet().parallelStream().forEach(entry -> {
            var recipeType = entry.getKey();

            ObjectSet<Item> recipesIngredients = new ObjectOpenHashSet<>();
            int recipeCount = 0;
            for (var holder : recipeManager.getRecipes()) {
                if (holder.value().getType() == recipeType) {
                    recipeCount++;
                    try {
                        for (var ingredient : holder.value().getIngredients()) {
                            if (ingredient != null) for (var stack : ingredient.getItems()) {
                                if (stack != null && !stack.isEmpty()) recipesIngredients.add(stack.getItem());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            Collection<Item> candidates;
            if (recipeCount <= 100) {
                candidates = GameRegistryManager.getAllItems();
            } else {
                ObjectSet<Item> union = new ObjectOpenHashSet<>();
                union.addAll(graph.getCorpus());
                union.addAll(recipesIngredients);
                candidates = union;
            }

            if (candidates.isEmpty()) return;

            for (var inputClass : entry.getValue()) {
                var creator = resolveCreator(inputClass);
                if (creator == null) continue;

                for (var item : candidates) {
                    try {
                        var stack = new ItemStack(item);
                        if (stack.isEmpty()) continue;

                        var inputObj = creator.create(stack);
                        if (inputObj == null) continue;

                        var recipes = recipeManager.getRecipesFor((RecipeType) recipeType, (RecipeInput) inputObj, level);

                        for (var rh : recipes) {
                            var holder = (RecipeHolder<?>) rh;
                            if (safeKnown.contains(holder.id()) || !discovered.add(holder.id())) continue;

                            try {
                                var items = harvester.harvest(holder.value(), level);
                                var node = HarvestedRecipeConverter.convert(items, level);
                                if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty()
                                        || !node.getChemicalIngredients().isEmpty())) {
                                    graph.addRecipe(node);
                                    addedCount.incrementAndGet();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        ComplexityAnalyzer.LOGGER.info("[Harvest] Autonomous dynamic probe complete: discovered {} new recipes.", addedCount.get());
    }

    private static Class<?> getRecipeInputClass(Class<?> recipeClass) {
        Class<?> bestParam = null;
        for (var m : recipeClass.getMethods()) {
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
        Object2ObjectMap<RecipeType<?>, ObjectSet<Class<?>>> inputTypeMap = new Object2ObjectLinkedOpenHashMap<>();
        for (var holder : recipeManager.getRecipes()) {
            var type = holder.value().getType();
            var inputClass = getRecipeInputClass(holder.value().getClass());
            if (inputClass == null) continue;
            inputTypeMap.computeIfAbsent(type, k -> new ObjectOpenHashSet<>()).add(inputClass);
        }

        for (var classes : inputTypeMap.values()) if (classes.size() > 1) classes.remove(RecipeInput.class);
        return inputTypeMap;
    }

    private static InputCreator resolveCreator(Class<?> inputClass) {
        for (var m : inputClass.getMethods()) {
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

        for (var c : inputClass.getDeclaredConstructors()) {
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
}