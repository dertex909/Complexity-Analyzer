package org.complexityanalyzer.analyzer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.util.*;

public class MachineRegistry {

    private final Map<ResourceLocation, Item> mapping = new HashMap<>();
    private boolean initialized = false;

    public void initialize() {
        if (initialized) {
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Initializing MachineRegistry...");
        int vanilla = registerVanilla();
        ComplexityAnalyzer.LOGGER.info("Registered {} vanilla machines", vanilla);

        initialized = true;
    }

    public void loadFromJEI(Map<ResourceLocation, List<Item>> jeiCatalysts) {
        if (jeiCatalysts.isEmpty()) {
            ComplexityAnalyzer.LOGGER.warn("JEI provided 0 catalysts");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Loading machines from {} JEI catalyst entries...", jeiCatalysts.size());
        int added = 0;
        int updated = 0;
        int skipped = 0;

        for (Map.Entry<ResourceLocation, List<Item>> entry : jeiCatalysts.entrySet()) {
            ResourceLocation typeId = entry.getKey();
            List<Item> machines = entry.getValue();

            if (machines.isEmpty()) {
                continue;
            }

            // ========== ДОБАВЛЕНО: защита ванильных машин ==========
            if (typeId.getNamespace().equals("minecraft")) {
                // Не перезаписываем ванильные типы рецептов
                if (mapping.containsKey(typeId)) {
                    skipped++;
                    ComplexityAnalyzer.LOGGER.debug("Skipped vanilla recipe type: {}", typeId);
                    continue;
                }
            }
            // =======================================================

            Item machine = machines.get(0);

            if (mapping.containsKey(typeId)) {
                updated++;
            } else {
                added++;
            }

            mapping.put(typeId, machine);
            ComplexityAnalyzer.LOGGER.debug("  {} -> {}", typeId, BuiltInRegistries.ITEM.getKey(machine));
        }

        ComplexityAnalyzer.LOGGER.info("Loaded from JEI: {} new, {} updated, {} skipped. Total machines: {}",
                added, updated, skipped, mapping.size());

        if (ComplexityAnalyzer.LOGGER.isDebugEnabled()) {
            logAllMachines();
        }
    }

    public Optional<Item> getMachineForRecipe(RecipeType<?> type) {
        if (!initialized) {
            return Optional.empty();
        }

        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return Optional.ofNullable((typeId != null) ? mapping.get(typeId) : null);
    }

    private int registerVanilla() {
        register("minecraft:crafting", "minecraft:crafting_table");
        register("minecraft:smelting", "minecraft:furnace");
        register("minecraft:blasting", "minecraft:blast_furnace");
        register("minecraft:smoking", "minecraft:smoker");
        register("minecraft:campfire_cooking", "minecraft:campfire");
        register("minecraft:stonecutting", "minecraft:stonecutter");
        register("minecraft:smithing", "minecraft:smithing_table");
        return 7;
    }

    private void register(String recipeTypeId, String itemId) {
        ResourceLocation typeRL = ResourceLocation.parse(recipeTypeId);
        ResourceLocation itemRL = ResourceLocation.parse(itemId);

        Item item = BuiltInRegistries.ITEM.get(itemRL);

        if (item == Items.AIR) {
            ComplexityAnalyzer.LOGGER.warn("Failed to register machine: {} -> {} (item not found)",
                    recipeTypeId, itemId);
            return;
        }

        mapping.put(typeRL, item);
    }

    private void logAllMachines() {
        ComplexityAnalyzer.LOGGER.debug("=== All registered machines ({}) ===", mapping.size());
        mapping.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry ->
                        ComplexityAnalyzer.LOGGER.debug("  {} -> {}",
                                entry.getKey(),
                                BuiltInRegistries.ITEM.getKey(entry.getValue()))
                );
    }
}