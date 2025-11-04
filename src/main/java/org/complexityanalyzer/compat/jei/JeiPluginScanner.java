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
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.ArrayList;
import java.util.List;

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

        int pluginsProcessed = 0;
        int totalRecipesImported = 0;

        for (IModPlugin plugin : cleanPlugins) {
            try {
                String pluginId = plugin.getPluginUid().toString();

                if (isBlacklisted(plugin.getPluginUid().getNamespace())) {
                    ComplexityAnalyzer.LOGGER.info("Skipping blacklisted plugin: {}", pluginId);
                    continue;
                }

                MockRecipeRegistration mockRegistration = new MockRecipeRegistration(level);

                try {
                    plugin.registerRecipes(mockRegistration);
                } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                    ComplexityAnalyzer.LOGGER.warn("Plugin {} failed during recipe registration (client-only code likely). Skipping. Error: {}", pluginId, e.getMessage());
                    continue;
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.debug("Plugin {} threw exception during recipe registration: {}", pluginId, e.getMessage());
                }

                List<RecipeNode> recipes = JeiRecipeConverter.convertAll(
                        mockRegistration.getCollectedRecipes(),
                        mockRegistration.getLevel()
                );

                if (!recipes.isEmpty()) {
                    recipes.forEach(graph::addRecipe);
                    pluginsProcessed++;
                    totalRecipesImported += recipes.size();
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to process JEI plugin {}: {}",
                        plugin.getClass().getName(), e.getMessage());
            }
        }

        ComplexityAnalyzer.LOGGER.info("JEI import complete: {} plugins processed, {} recipes imported.",
                pluginsProcessed, totalRecipesImported);
    }

    private static boolean isBlacklisted(String modId) {
        return ComplexityConfig.JEI_PLUGIN_BLACKLIST.get().contains(modId);
    }
}