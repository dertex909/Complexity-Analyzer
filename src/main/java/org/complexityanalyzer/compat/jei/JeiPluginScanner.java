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

        int pluginsProcessed = 0;
        int totalRecipesImported = 0;
        Map<ResourceLocation, List<ItemStack>> allCatalysts = new HashMap<>();

        for (IModPlugin plugin : cleanPlugins) {
            String pluginId = plugin.getPluginUid().toString();

            if (isBlacklisted(plugin.getPluginUid().getNamespace())) {
                ComplexityAnalyzer.LOGGER.info("Skipping blacklisted plugin: {}", pluginId);
                continue;
            }

            MockRecipeRegistration mockRegistration = new MockRecipeRegistration(level);
            MockRecipeCatalystRegistration mockCatalystReg = new MockRecipeCatalystRegistration();

            // Пытаемся зарегистрировать рецепты
            try {
                plugin.registerRecipes(mockRegistration);
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                ComplexityAnalyzer.LOGGER.debug("Plugin {} skipped recipe registration (client-only): {}",
                        pluginId, e.getMessage());
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("Plugin {} failed recipe registration: {}",
                        pluginId, e.getMessage());
            }

            // Пытаемся зарегистрировать каталисты
            try {
                plugin.registerRecipeCatalysts(mockCatalystReg);
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                ComplexityAnalyzer.LOGGER.debug("Plugin {} skipped catalyst registration (client-only): {}",
                        pluginId, e.getMessage());
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("Plugin {} failed catalyst registration: {}",
                        pluginId, e.getMessage());
            }

            // Собираем каталисты
            Map<ResourceLocation, List<ItemStack>> pluginCatalysts = mockCatalystReg.getCatalysts();
            if (!pluginCatalysts.isEmpty()) {
                ComplexityAnalyzer.LOGGER.info("Plugin {} registered {} catalyst entries",
                        pluginId, pluginCatalysts.size());

                for (var entry : pluginCatalysts.entrySet()) {
                    allCatalysts.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                            .addAll(entry.getValue());
                }
            }

            // Конвертируем рецепты
            List<RecipeNode> recipes = JeiRecipeConverter.convertAll(
                    mockRegistration.getCollectedRecipes(),
                    mockRegistration.getLevel()
            );

            if (!recipes.isEmpty()) {
                recipes.forEach(graph::addRecipe);
                pluginsProcessed++;
                totalRecipesImported += recipes.size();
            }
        }

        ComplexityAnalyzer.LOGGER.info("JEI import complete: {} plugins processed, {} recipes imported.",
                pluginsProcessed, totalRecipesImported);

        if (!allCatalysts.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("Processing {} catalyst types for MachineRegistry...", allCatalysts.size());

            try {
                AnalysisEngine.getInstance().getMachineRegistry().ifPresentOrElse(
                        registry -> {
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
                            ComplexityAnalyzer.LOGGER.info("Successfully loaded machine catalysts from JEI");
                        },
                        () -> ComplexityAnalyzer.LOGGER.warn("MachineRegistry is not available!")
                );
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Failed to transfer catalysts to MachineRegistry", e);
            }
        } else {
            ComplexityAnalyzer.LOGGER.warn("No catalysts were collected from JEI plugins");
        }
    }

    private static boolean isBlacklisted(String modId) {
        return ComplexityConfig.JEI_PLUGIN_BLACKLIST.get().contains(modId);
    }
}