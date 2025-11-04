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

                try {
                    plugin.registerRecipes(mockRegistration);
                } catch (NullPointerException e) {
                    if (!e.getMessage().contains("factory")) {
                        ComplexityAnalyzer.LOGGER.debug("Plugin {} threw NPE: {}", pluginId, e.getMessage());
                    }
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.debug("Plugin {} threw exception: {}", pluginId, e.getMessage());
                }

                List<RecipeNode> recipes = JeiRecipeConverter.convertAll(mockRegistration.getCollectedRecipes());

                for (RecipeNode node : recipes) {
                    graph.addRecipe(node);
                }

                ComplexityAnalyzer.LOGGER.info("Imported {} recipes from plugin: {}", recipes.size(), pluginId);
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
                            } catch (Exception e) {
                                ComplexityAnalyzer.LOGGER.debug("Could not load plugin from {}: {}",
                                        modId, e.getMessage());
                            }
                        });
            } catch (Exception ignored) {}
        });

        return plugins;
    }

    private static boolean isBlacklisted(String modId) {
        return ComplexityConfig.JEI_PLUGIN_BLACKLIST.get().contains(modId);
    }
}