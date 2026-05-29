package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeGraph;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DynamicRecipeHarvester {

    private DynamicRecipeHarvester() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void harvest(RecipeGraph graph, Level level, ObjectSet<ResourceLocation> knownRecipeIds) {
        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting autonomous dynamic recipe probe...");
        var recipeManager = level.getRecipeManager();

        Map<RecipeType<?>, Class<?>> inputTypeMap = new LinkedHashMap<>();
        for (var holder : recipeManager.getRecipes()) {
            var type = holder.value().getType();
            if (inputTypeMap.containsKey(type)) continue;
            for (var m : holder.value().getClass().getMethods()) {
                if (m.getName().equals("assemble") && m.getParameterCount() == 2 && RecipeInput.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    inputTypeMap.put(type, m.getParameterTypes()[0]);
                    break;
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("[Harvest] Discovered {} recipe type input mappings for dynamic probe.", inputTypeMap.size());

        ObjectSet<ResourceLocation> discovered = new ObjectOpenHashSet<>();
        var harvester = new FastHarvester();
        int addedCount = 0;

        for (var entry : inputTypeMap.entrySet()) {
            for (Item item : BuiltInRegistries.ITEM) {
                try {
                    var inputObj = createInput(entry.getValue(), item);
                    if (inputObj == null) continue;

                    var recipes = recipeManager.getRecipesFor((RecipeType) entry.getKey(), (RecipeInput) inputObj, level);

                    for (var rh : recipes) {
                        var holder = (RecipeHolder<?>) rh;
                        if (knownRecipeIds.contains(holder.id()) || !discovered.add(holder.id())) continue;

                        try {
                            var items = harvester.harvest(holder.value(), level);
                            var node = HarvestedRecipeConverter.convert(items, level);
                            if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty()
                                    || !node.getChemicalIngredients().isEmpty())) {
                                graph.addRecipe(node);
                                addedCount++;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("[Harvest] Autonomous dynamic probe complete: discovered {} new recipes.", addedCount);
    }

    private static Object createInput(Class<?> inputClass, Item item) {
        var stack = new ItemStack(item);
        List<ItemStack> singleList = List.of(stack);

        for (var m : inputClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals("of")
                    || !inputClass.isAssignableFrom(m.getReturnType())) continue;
            try {
                int pc = m.getParameterCount();
                Class<?>[] pt = m.getParameterTypes();
                if (pc == 1 && pt[0].isAssignableFrom(ItemStack.class)) return m.invoke(null, stack);
                if (pc == 3 && pt[0] == int.class && pt[1] == int.class && List.class.isAssignableFrom(pt[2])) {
                    return m.invoke(null, 1, 1, singleList);
                }
            } catch (Throwable ignored) {
            }
        }

        for (var c : inputClass.getDeclaredConstructors()) {
            try {
                c.setAccessible(true);
                int pc = c.getParameterCount();
                Class<?>[] pt = c.getParameterTypes();
                if (pc == 1 && pt[0].isAssignableFrom(ItemStack.class)) return c.newInstance(stack);
                if (pc == 3 && pt[0] == int.class && pt[1] == int.class && List.class.isAssignableFrom(pt[2])) {
                    return c.newInstance(1, 1, singleList);
                }
                if (pc == 1 && List.class.isAssignableFrom(pt[0])) return c.newInstance(singleList);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}