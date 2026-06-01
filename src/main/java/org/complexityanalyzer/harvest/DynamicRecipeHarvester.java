package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
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
import java.util.List;
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
    private static List<RecipeHolder<?>> getRecipesFor(Item item, InputCreator creator, RecipeType<?> recipeType,
                                                       RecipeManager recipeManager, Level level) {
        try {
            var stack = new ItemStack(item);
            if (stack.isEmpty()) return List.of();

            var inputObj = creator.create(stack);
            if (inputObj == null) return List.of();

            return recipeManager.getRecipesFor((RecipeType) recipeType, (RecipeInput) inputObj, level);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static void harvest(RecipeGraph graph, Level level, ObjectSet<ResourceLocation> knownRecipeIds) {
        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting autonomous dynamic recipe probe...");
        var recipeManager = level.getRecipeManager();

        final var inputTypeMap = getTypeMap(recipeManager);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Discovered {} recipe type input mappings for dynamic probe.", inputTypeMap.size());

        final var safeKnown = ConcurrentHashMap.newKeySet();
        safeKnown.addAll(knownRecipeIds);

        final var discovered = ConcurrentHashMap.newKeySet();
        final var harvester = new FastHarvester();
        final var addedCount = new AtomicInteger(0);

        inputTypeMap.entrySet().parallelStream().forEach(entry -> {
            var recipeType = entry.getKey();
            var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
            String typeName = typeId != null ? typeId.toString() : recipeType.toString();

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

            ObjectCollection<Item> candidates;
            if (recipeCount <= 100) {
                candidates = GameRegistryManager.getAllItems();
            } else {
                ObjectSet<Item> union = new ObjectOpenHashSet<>();
                union.addAll(graph.getCorpus());
                union.addAll(recipesIngredients);
                candidates = union;
            }

            if (candidates.isEmpty()) {
                ComplexityAnalyzer.LOGGER.info("[Harvest:Debug][{}] Candidates are empty, skipping.", typeName);
                return;
            }

            for (var inputClass : entry.getValue()) {
                var creator = resolveCreator(inputClass);
                if (creator == null) {
                    ComplexityAnalyzer.LOGGER.info("[Harvest:Debug][{}] Skipping input class {} because creator is null.", typeName, inputClass.getSimpleName());
                    continue;
                }

                var candidateList = new ObjectArrayList<>(candidates);
                int n = candidateList.size();

                int preProbeSize = Math.min(n, 15);
                var preProbeSample = new ObjectArrayList<Item>();
                boolean[] sampled = new boolean[n];
                for (int i = 0; i < preProbeSize; i++) {
                    int index = (int) ((long) i * n / preProbeSize);
                    if (!sampled[index]) {
                        sampled[index] = true;
                        preProbeSample.add(candidateList.get(index));
                    }
                }

                int matchedStatic = 0;
                int matchedDynamic = 0;
                var discoveredInPreProbe = new ObjectArrayList<RecipeHolder<?>>();

                for (var item : preProbeSample) {
                    for (var rh : getRecipesFor(item, creator, recipeType, recipeManager, level)) {
                        var holder = (RecipeHolder<?>) rh;
                        if (safeKnown.contains(holder.id())) {
                            matchedStatic++;
                        } else {
                            matchedDynamic++;
                            discoveredInPreProbe.add(holder);
                        }
                    }
                }

                if (matchedStatic == 0 && matchedDynamic == 0 && n > preProbeSize) {
                    int mediumProbeSize = Math.min(n, 100);
                    var mediumProbeSample = new ObjectArrayList<Item>();
                    for (int i = 0; i < mediumProbeSize; i++) {
                        int index = (int) ((long) i * n / mediumProbeSize);
                        if (!sampled[index]) {
                            sampled[index] = true;
                            mediumProbeSample.add(candidateList.get(index));
                        }
                    }

                    for (var item : mediumProbeSample) {
                        for (var rh : getRecipesFor(item, creator, recipeType, recipeManager, level)) {
                            var holder = (RecipeHolder<?>) rh;
                            if (safeKnown.contains(holder.id())) {
                                matchedStatic++;
                            } else {
                                matchedDynamic++;
                                discoveredInPreProbe.add(holder);
                            }
                        }
                    }
                }

                boolean isDynamic = (matchedDynamic > 0);
                ComplexityAnalyzer.LOGGER.info("[Harvest:Debug][{}] Probe for {} - candidates: {}, matchedStatic: {}, matchedDynamic: {}, isDynamic: {}",
                        typeName, inputClass.getSimpleName(), n, matchedStatic, matchedDynamic, isDynamic);

                if (isDynamic) {
                    int preProbeAdded = 0;
                    for (var holder : discoveredInPreProbe) {
                        if (!discovered.add(holder.id())) continue;
                        try {
                            var items = harvester.harvest(holder.value(), level);
                            var node = HarvestedRecipeConverter.convert(items, level);

                            if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty()
                                    || !node.getChemicalIngredients().isEmpty())) {
                                graph.addRecipe(node);
                                addedCount.incrementAndGet();
                                preProbeAdded++;
                            } else {
                                ComplexityAnalyzer.LOGGER.info("[Harvest:Debug][{}]   Dropped during conversion pre-probe: {}", typeName, holder.id());
                            }
                        } catch (Throwable t) {
                            ComplexityAnalyzer.LOGGER.error("[Harvest:Debug] Error harvesting matched recipe: ID={}", holder.id(), t);
                        }
                    }
                    if (preProbeAdded > 0) {
                        ComplexityAnalyzer.LOGGER.info("[Harvest:Debug][{}]   Added {} recipes from sample pre-probe.", typeName, preProbeAdded);
                    }

                    int fullScanAdded = 0;
                    for (int i = 0; i < n; i++) {
                        if (sampled[i]) continue;
                        var item = candidateList.get(i);

                        for (var rh : getRecipesFor(item, creator, recipeType, recipeManager, level)) {
                            var holder = (RecipeHolder<?>) rh;
                            if (safeKnown.contains(holder.id()) || !discovered.add(holder.id())) continue;

                            try {
                                var items = harvester.harvest(holder.value(), level);
                                var node = HarvestedRecipeConverter.convert(items, level);

                                if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty()
                                        || !node.getChemicalIngredients().isEmpty())) {
                                    graph.addRecipe(node);
                                    addedCount.incrementAndGet();
                                    fullScanAdded++;
                                } else {
                                    ComplexityAnalyzer.LOGGER.info("[Harvest:Debug][{}]   Dropped during conversion full scan: {}", typeName, holder.id());
                                }

                            } catch (Throwable t) {
                                ComplexityAnalyzer.LOGGER.error("[Harvest:Debug] Error harvesting matched recipe: ID={}", holder.id(), t);
                            }
                        }
                    }
                    ComplexityAnalyzer.LOGGER.info("[Harvest:Debug][{}]   Full scan added {} new recipes.", typeName, fullScanAdded);
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