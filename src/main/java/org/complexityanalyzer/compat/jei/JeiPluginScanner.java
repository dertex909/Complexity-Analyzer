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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

public class JeiPluginScanner {

    public static void scanAndImportRecipes(RecipeGraph graph, Level level) {
        List<IModPlugin> plugins = findJeiPlugins();

        if (plugins.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("No JEI plugins found in loaded mods");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Found {} JEI plugin(s), extracting recipes...", plugins.size());

        int successCount = 0;
        int totalRecipes = 0;

        for (IModPlugin plugin : plugins) {
            try {
                String pluginId = plugin.getPluginUid().toString();

                if (isBlacklisted(plugin.getPluginUid().getNamespace())) {
                    ComplexityAnalyzer.LOGGER.info("Skipping blacklisted plugin: {}", pluginId);
                    continue;
                }

                MockRecipeRegistration mockRegistration = new MockRecipeRegistration(level);

                try { plugin.registerRecipes(mockRegistration); }
                catch (NullPointerException ignored) {}
                catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.debug("Plugin {} threw exception: {}", pluginId, e.getMessage());
                }

                List<RecipeNode> recipes = JeiRecipeConverter.convertAll(
                        mockRegistration.getCollectedRecipes(),
                        mockRegistration.getLevel()
                );

                for (RecipeNode node : recipes) {
                    graph.addRecipe(node);
                }

                successCount++;
                totalRecipes += recipes.size();

            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to process JEI plugin {}: {}",
                        plugin.getClass().getName(), e.getMessage());
            }
        }

        ComplexityAnalyzer.LOGGER.info("JEI import complete: {} plugins processed, {} recipes imported",
                successCount, totalRecipes);
    }

    private static List<IModPlugin> findJeiPlugins() {
        List<IModPlugin> plugins = new ArrayList<>();

        ModList.get().getMods().forEach(modInfo -> {
            String modId = modInfo.getModId();

            if (modId.equals("jei") || modId.equals(ComplexityAnalyzer.MODID)) {
                return;
            }

            try {
                var scanResult = ModList.get().getModFileById(modId).getFile().getScanResult();

                scanResult.getAnnotations().stream()
                        .filter(ad -> ad.annotationType().getClassName().equals("mezz.jei.api.JeiPlugin"))
                        .forEach(ad -> {
                            try {
                                Class<?> pluginClass = Class.forName(ad.clazz().getClassName());

                                if (IModPlugin.class.isAssignableFrom(pluginClass)) {
                                    Constructor<?> ctor = pluginClass.getDeclaredConstructor();
                                    ctor.setAccessible(true);
                                    IModPlugin plugin = (IModPlugin) ctor.newInstance();
                                    plugins.add(plugin);

                                    ComplexityAnalyzer.LOGGER.debug("Loaded JEI plugin: {} from mod {}",
                                            pluginClass.getSimpleName(), modId);
                                }
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        });

        return plugins;
    }

    private static boolean isBlacklisted(String modId) {
        return ComplexityConfig.JEI_PLUGIN_BLACKLIST.get().contains(modId);
    }
}