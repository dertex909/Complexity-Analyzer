package org.complexityanalyzer.api.testAPI;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.HardcodedSourcesProvider;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;

import java.util.Map;
import java.util.Optional;

public class HardcodedSourcesAPITest {

    private static boolean hasRun = false;

        public static void runTests(IHardcodedSourceRegistry registry) {
        if (hasRun) {
            ComplexityAnalyzer.LOGGER.debug("[API Test] Tests already executed, skipping");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("[API Test] Starting HardcodedSources API validation...");

        int passed = 0;
        int failed = 0;

        if (testRegisterTransformation(registry)) {
            passed++;
        } else {
            failed++;
        }

        if (testRegisterComplexSource(registry)) {
            passed++;
        } else {
            failed++;
        }

        if (testRegisterOverride(registry)) {
            passed++;
        } else {
            failed++;
        }

        if (testRegisterUnobtainable(registry)) {
            passed++;
        } else {
            failed++;
        }

        if (testIsRegistered(registry)) {
            passed++;
        } else {
            failed++;
        }

        if (testBaseResourceDataGetters(registry)) {
            passed++;
        } else {
            failed++;
        }

        hasRun = true;

        if (failed == 0) {
            ComplexityAnalyzer.LOGGER.info("[API Test] ✓ All {} tests PASSED", passed);
        } else {
            ComplexityAnalyzer.LOGGER.warn("[API Test] Tests completed: {} passed, {} FAILED", passed, failed);
        }
    }

    private static boolean testRegisterTransformation(IHardcodedSourceRegistry registry) {
        try {
            Item testInput = Items.STONE;
            Item testOutput = Items.STONE_BRICKS; // Используем что-то безопасное

            if (registry.isRegistered(testOutput)) {
                ComplexityAnalyzer.LOGGER.debug("[API Test] SKIP: registerTransformation - item already registered");
                return true; // Не фейл, просто уже есть
            }

            registry.registerTransformation(
                    testOutput,
                    testInput,
                    Map.of(Items.WOODEN_PICKAXE, 0.01),
                    5.0,
                    "Test transformation"
            );

            boolean registered = registry.isRegistered(testOutput);

            if (registered) {
                ComplexityAnalyzer.LOGGER.debug("[API Test] ✓ registerTransformation works");
                return true;
            } else {
                ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerTransformation failed - item not registered");
                return false;
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerTransformation threw exception", e);
            return false;
        }
    }

    private static boolean testRegisterComplexSource(IHardcodedSourceRegistry registry) {
        try {
            Item testItem = Items.ENCHANTED_BOOK; // Что-то что вряд ли будет конфликтовать

            Map<Item, Double> ingredients = Map.of(
                    Items.BOOK, 1.0,
                    Items.LAPIS_LAZULI, 3.0
            );

            registry.registerComplexSource(
                    testItem,
                    ingredients,
                    10.0,
                    BaseResourceData.ResourceSourceType.CRAFTING,
                    "Test complex source"
            );

            boolean registered = registry.isRegistered(testItem);

            if (registered) {
                ComplexityAnalyzer.LOGGER.debug("[API Test] ✓ registerComplexSource works");
                return true;
            } else {
                ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerComplexSource failed");
                return false;
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerComplexSource threw exception", e);
            return false;
        }
    }

    private static boolean testRegisterOverride(IHardcodedSourceRegistry registry) {
        try {
            Item testItem = Items.BEDROCK; // Что-то что точно нельзя получить

            registry.registerOverride(
                    testItem,
                    Map.of(Items.DIAMOND, 64.0),
                    9999.0,
                    "Test override - bedrock from diamonds"
            );

            boolean registered = registry.isRegistered(testItem);

            if (registered) {
                ComplexityAnalyzer.LOGGER.debug("[API Test] ✓ registerOverride works");
                return true;
            } else {
                ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerOverride failed");
                return false;
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerOverride threw exception", e);
            return false;
        }
    }

    private static boolean testRegisterUnobtainable(IHardcodedSourceRegistry registry) {
        try {
            Item testItem = Items.COMMAND_BLOCK;

            registry.registerUnobtainable(testItem, "Test unobtainable - creative only");

            boolean registered = registry.isRegistered(testItem);

            if (registered) {
                ComplexityAnalyzer.LOGGER.debug("[API Test] ✓ registerUnobtainable works");
                return true;
            } else {
                ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerUnobtainable failed");
                return false;
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[API Test] ✗ registerUnobtainable threw exception", e);
            return false;
        }
    }

    private static boolean testIsRegistered(IHardcodedSourceRegistry registry) {
        try {
            boolean airNotRegistered = !registry.isRegistered(Items.AIR);

            boolean vanillaRegistered = registry.isRegistered(Items.STRIPPED_OAK_LOG) ||
                    registry.isRegistered(Items.DEAD_TUBE_CORAL);

            if (airNotRegistered && vanillaRegistered) {
                ComplexityAnalyzer.LOGGER.debug("[API Test] ✓ isRegistered works correctly");
                return true;
            } else {
                ComplexityAnalyzer.LOGGER.error("[API Test] ✗ isRegistered logic error");
                return false;
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[API Test] ✗ isRegistered threw exception", e);
            return false;
        }
    }

    private static boolean testBaseResourceDataGetters(IHardcodedSourceRegistry registry) {
        try {
            if (!(registry instanceof HardcodedSourcesProvider provider)) {
                ComplexityAnalyzer.LOGGER.warn("[API Test] SKIP: BaseResourceData test - cannot cast registry");
                return true;
            }

            Item testItem = Items.STRIPPED_OAK_LOG;

            Optional<BaseResourceData> dataOpt = provider.analyze(testItem);

            if (dataOpt.isEmpty()) {
                ComplexityAnalyzer.LOGGER.warn("[API Test] SKIP: BaseResourceData test - no data for test item");
                return true;
            }

            BaseResourceData data = dataOpt.get();

            Item item = data.getItem();
            BaseResourceData.ResourceSourceType type = data.getSourceType();
            double factor = data.getBaseFactor();
            String details = data.getDetails();
            String sourceName = data.getSourceName();
            Map<Item, Double> sourceItems = data.getSourceItems();
            Map<String, String> metadata = data.getMetadata();
            boolean isOverride = data.isOverride();
            String overrideModId = data.getOverrideModId();
            String toString = data.toString();

            boolean allGettersWork = item != null &&
                    type != null &&
                    !Double.isNaN(factor) &&
                    details != null &&
                    sourceName != null &&
                    sourceItems != null &&
                    metadata != null &&
                    toString != null;

            if (allGettersWork) {
                ComplexityAnalyzer.LOGGER.debug("[API Test] ✓ BaseResourceData all getters work");
                ComplexityAnalyzer.LOGGER.debug("[API Test]   Item: {}, Type: {}, Factor: {}",
                        item, type, factor);
                ComplexityAnalyzer.LOGGER.debug("[API Test]   Override: {}, Mod: {}",
                        isOverride, overrideModId);
                return true;
            } else {
                ComplexityAnalyzer.LOGGER.error("[API Test] ✗ BaseResourceData some getters returned null");
                return false;
            }

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[API Test] ✗ BaseResourceData getters threw exception", e);
            return false;
        }
    }
}