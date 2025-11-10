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

import mezz.jei.api.IModPlugin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class JeiPluginScanner {
    public static void scanAndImportRecipes(RecipeGraph graph, Level level) {
        List<IModPlugin> cleanPlugins = new ArrayList<>();

        ModList.get().getMods().forEach(modInfo -> {
            String modId = modInfo.getModId();
            if (modId.equals("jei") || modId.equals(ComplexityAnalyzer.MODID)) {
                return;
            }

            try {
                var scanData = modInfo.getOwningFile().getFile().getScanResult();
                scanData.getAnnotations().stream()
                        .filter(ad -> ad.annotationType().getClassName().equals("mezz.jei.api.JeiPlugin"))
                        .forEach(ad -> {
                            IModPlugin plugin = SafePluginLoader.tryLoadPlugin(ad.clazz().getClassName(), modId);
                            if (plugin != null) {
                                cleanPlugins.add(plugin);
                            }
                        });
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Error scanning mod {} for JEI plugins: {}", modId, e.getMessage());
            }
        });

        if (cleanPlugins.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("No server-safe JEI plugins found to import recipes from.");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Found {} server-safe JEI plugin(s), extracting recipes...", cleanPlugins.size());

        Map<ResourceLocation, List<ItemStack>> allCatalysts = new HashMap<>();

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

            Map<ResourceLocation, List<ItemStack>> pluginCatalysts = mockCatalystReg.getCatalysts();
            if (!pluginCatalysts.isEmpty()) {
                ComplexityAnalyzer.LOGGER.info("Plugin {} registered {} catalyst entries",
                        pluginId, pluginCatalysts.size());

                for (var entry : pluginCatalysts.entrySet()) {
                    allCatalysts.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                            .addAll(entry.getValue());
                }
            }
        }

        if (!allCatalysts.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("Loading {} catalyst types to MachineRegistry...", allCatalysts.size());

            try {
                AnalysisEngine.getInstance().getMachineRegistry().ifPresent(registry -> {
                    Map<ResourceLocation, List<Item>> catalystItemMap = new HashMap<>();

                    for (var entry : allCatalysts.entrySet()) {
                        ResourceLocation recipeType = entry.getKey();
                        List<Item> items = entry.getValue().stream()
                                .map(ItemStack::getItem)
                                .distinct()
                                .toList();

                        if (!items.isEmpty()) {
                            catalystItemMap.put(recipeType, items);
                        }
                    }

                    ComplexityAnalyzer.LOGGER.info("Converted {} catalyst types to item mapping", catalystItemMap.size());
                    registry.loadFromJEI(catalystItemMap);

                    AdaptiveRecipeConverter.setMachineRegistry(registry);
                    ComplexityAnalyzer.LOGGER.info("MachineRegistry loaded and connected to AdaptiveRecipeConverter");
                });
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

            if (isBlacklisted(plugin.getPluginUid().getNamespace())) {
                continue;
            }

            MockRecipeRegistration mockRegistration = new MockRecipeRegistration(level);

            try {
                plugin.registerRecipes(mockRegistration);
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.debug("Plugin {} is not server-compatible, skipping its recipe registration. Reason: {}",
                        pluginId, t.getClass().getSimpleName());
            }

            List<RecipeNode> recipes = JeiRecipeConverter.convertAllFromJei(
                    mockRegistration.getCollectedRecipes(),
                    mockRegistration.getLevel()
            );

            if (!recipes.isEmpty()) {
                recipes.forEach(graph::addRecipe);
                pluginsProcessed++;
                totalJeiRecipesImported += recipes.size();
            }
        }

        ComplexityAnalyzer.LOGGER.info("JEI import complete: {} plugins processed, {} recipes imported.",
                pluginsProcessed, totalJeiRecipesImported);

        try {
            ComplexityAnalyzer.LOGGER.info("Starting RecipeManager processing (this may take a while)...");

            List<RecipeNode> mcRecipes = JeiRecipeConverter.convertAllFromRecipeManager(level);

            mcRecipes.forEach(graph::addRecipe);

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
        return ComplexityConfig.JEI_PLUGIN_BLACKLIST.get().contains(modId);
    }
}