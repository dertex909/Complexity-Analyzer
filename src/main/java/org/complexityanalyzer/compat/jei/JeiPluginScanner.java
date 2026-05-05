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
import mezz.jei.api.IModPlugin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.List;

public class JeiPluginScanner {
    public static void scanAndImportRecipes(RecipeGraph graph, Level level) {
        ObjectList<IModPlugin> cleanPlugins = new ObjectArrayList<>();

        List<IModInfo> mods = ModList.get().getMods();
        for (IModInfo modInfo : mods) {
            String modId = modInfo.getModId();
            if (modId.equals("jei") || modId.equals(ComplexityAnalyzer.MODID)) continue;

            try {
                var scanData = modInfo.getOwningFile().getFile().getScanResult();
                for (var ad : scanData.getAnnotations()) {
                    if (ad.annotationType().getClassName().equals("mezz.jei.api.JeiPlugin")) {
                        IModPlugin plugin = SafePluginLoader.tryLoadPlugin(ad.clazz().getClassName(), modId);
                        if (plugin != null) cleanPlugins.add(plugin);
                    }
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Error scanning mod {} for JEI plugins: {}", modId, e.getMessage());
            }
        }

        if (cleanPlugins.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("No server-safe JEI plugins found to import recipes from.");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Found {} server-safe JEI plugin(s), extracting recipes...", cleanPlugins.size());

        Object2ObjectMap<ResourceLocation, ObjectList<ItemStack>> allCatalysts = new Object2ObjectOpenHashMap<>();

        for (IModPlugin plugin : cleanPlugins) {
            String pluginId = plugin.getPluginUid().toString();

            if (isBlacklisted(plugin.getPluginUid().getNamespace())) {
                ComplexityAnalyzer.LOGGER.info("Skipping blacklisted plugin: {}", pluginId);
                continue;
            }

            MockRecipeCatalystRegistration mockCatalystReg = new MockRecipeCatalystRegistration();

            try {
                plugin.registerRecipeCatalysts(mockCatalystReg);
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.debug("Plugin {} is not server-compatible, skipping its catalyst registration. Reason: {}",
                        pluginId, t.getClass().getSimpleName());
            }

            var pluginCatalysts = mockCatalystReg.getCatalysts();
            if (!pluginCatalysts.isEmpty()) {
                ComplexityAnalyzer.LOGGER.info("Plugin {} registered {} catalyst entries", pluginId, pluginCatalysts.size());

                for (var entry : Object2ObjectMaps.fastIterable(pluginCatalysts)) {
                    ObjectList<ItemStack> list = allCatalysts.get(entry.getKey());
                    if (list == null) {
                        list = new ObjectArrayList<>();
                        allCatalysts.put(entry.getKey(), list);
                    }
                    list.addAll(entry.getValue());
                }
            }
        }

        if (!allCatalysts.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("Loading {} catalyst types to MachineRegistry...", allCatalysts.size());

            try {
                var registry = AnalysisEngine.getInstance().getMachineRegistry();
                if (registry != null) {
                    Object2ObjectMap<ResourceLocation, ObjectList<Item>> catalystItemMap = new Object2ObjectOpenHashMap<>();

                    for (var entry : Object2ObjectMaps.fastIterable(allCatalysts)) {
                        ResourceLocation recipeType = entry.getKey();
                        ObjectList<Item> items = new ObjectArrayList<>();
                        ReferenceSet<Item> itemSet = new ReferenceOpenHashSet<>();

                        ObjectList<ItemStack> catalystStacks = entry.getValue();
                        for (ItemStack catalystStack : catalystStacks) {
                            Item item = catalystStack.getItem();
                            if (itemSet.add(item)) items.add(item);
                        }

                        if (!items.isEmpty()) catalystItemMap.put(recipeType, items);
                    }

                    ComplexityAnalyzer.LOGGER.info("Converted {} catalyst types to item mapping", catalystItemMap.size());
                    registry.loadFromJEI(catalystItemMap);

                    AdaptiveRecipeConverter.setMachineRegistry();
                    ComplexityAnalyzer.LOGGER.info("MachineRegistry loaded and connected to AdaptiveRecipeConverter");
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Failed to load MachineRegistry", e);
            }
        } else {
            ComplexityAnalyzer.LOGGER.warn("No catalysts were collected from JEI plugins");
        }

        int pluginsProcessed = 0;
        int totalJeiRecipesImported = 0;

        for (IModPlugin plugin : cleanPlugins) {
            String pluginId = plugin.getPluginUid().toString();

            if (isBlacklisted(plugin.getPluginUid().getNamespace())) continue;

            long pluginStart = System.currentTimeMillis();
            MockRecipeRegistration mockRegistration = new MockRecipeRegistration(level);

            try {
                plugin.registerRecipes(mockRegistration);
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.debug("Plugin {} is not server-compatible, skipping its recipe registration. Reason: {}",
                        pluginId, t.getClass().getSimpleName());
            }

            long registerTime = System.currentTimeMillis() - pluginStart;

            long convertStart = System.currentTimeMillis();
            ObjectList<RecipeNode> recipes = JeiRecipeConverter.convertAllFromJei(
                    mockRegistration.getCollectedRecipes(),
                    mockRegistration.getLevel()
            );
            long convertTime = System.currentTimeMillis() - convertStart;

            if (!recipes.isEmpty()) {
                for (RecipeNode recipe : recipes) graph.addRecipe(recipe);
                pluginsProcessed++;
                totalJeiRecipesImported += recipes.size();
            }

            long totalTime = System.currentTimeMillis() - pluginStart;
            if (totalTime > 1000) {
                ComplexityAnalyzer.LOGGER.warn("Slow JEI plugin {}: {}ms total (register={}ms, convert={}ms, {} recipes)",
                        pluginId, totalTime, registerTime, convertTime, recipes.size());
            } else {
                ComplexityAnalyzer.LOGGER.debug("JEI plugin {}: {}ms (register={}ms, convert={}ms, {} recipes)",
                        pluginId, totalTime, registerTime, convertTime, recipes.size());
            }
        }

        ComplexityAnalyzer.LOGGER.info("JEI import complete: {} plugins processed, {} recipes imported.",
                pluginsProcessed, totalJeiRecipesImported);

        try {
            ComplexityAnalyzer.LOGGER.info("Starting RecipeManager processing (this may take a while)...");

            ObjectList<RecipeNode> mcRecipes = JeiRecipeConverter.convertAllFromRecipeManager(level);
            for (RecipeNode mcRecipe : mcRecipes) graph.addRecipe(mcRecipe);

            ComplexityAnalyzer.LOGGER.info("RecipeManager import complete: {} recipes added.", mcRecipes.size());

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to process RecipeManager", e);
        }

        ComplexityAnalyzer.LOGGER.info(
                "=== TOTAL IMPORT SUMMARY: {} JEI recipes + RecipeManager recipes ===",
                totalJeiRecipesImported
        );
    }

    private static boolean isBlacklisted(String modId) {
        return ComplexityConfig.isJeiPluginBlacklisted(modId);
    }
}