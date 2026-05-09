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

package org.complexityanalyzer.analyzer;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.registry.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

public class MachineRegistry {

    private final Object2ObjectMap<ResourceLocation, Item> mapping = new Object2ObjectOpenHashMap<>();
    private boolean initialized = false;

    public void initialize() {
        if (initialized) return;
        int vanilla = registerVanilla();
        ComplexityAnalyzer.LOGGER.info("Registered {} vanilla machines", vanilla);
        initialized = true;
    }

    public void loadFromJEI(Object2ObjectMap<ResourceLocation, ObjectList<Item>> jeiCatalysts) {
        if (jeiCatalysts.isEmpty()) {
            ComplexityAnalyzer.LOGGER.warn("JEI provided 0 catalysts");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Loading machines from {} JEI catalyst entries...", jeiCatalysts.size());
        int added = 0;
        int updated = 0;
        int skipped = 0;

        for (var entry : Object2ObjectMaps.fastIterable(jeiCatalysts)) {
            var typeId = entry.getKey();
            var machines = entry.getValue();

            if (machines.isEmpty()) continue;

            if (typeId.getNamespace().equals("minecraft")) if (mapping.containsKey(typeId)) {
                skipped++;
                continue;
            }

            var machine = machines.getFirst();

            if (mapping.containsKey(typeId)) {
                updated++;
            } else {
                added++;
            }

            mapping.put(typeId, machine);
        }

        ComplexityAnalyzer.LOGGER.info("Loaded from JEI: {} new, {} updated, {} skipped. Total machines: {}",
                added, updated, skipped, mapping.size());

        if (ComplexityAnalyzer.LOGGER.isDebugEnabled()) logAllMachines();
    }

    @Nullable
    public Item getMachineForRecipe(RecipeType<?> type) {
        if (!initialized) return null;
        var typeId = GameRegistryManager.getRecipeTypeId(type);
        return (typeId != null) ? mapping.get(typeId) : null;
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
        var typeRL = ResourceLocation.parse(recipeTypeId);
        var itemRL = ResourceLocation.parse(itemId);

        var item = GameRegistryManager.getItem(itemRL);

        if (item == Items.AIR) {
            ComplexityAnalyzer.LOGGER.warn("Failed to register machine: {} -> {} (item not found)", recipeTypeId, itemId);
            return;
        }

        mapping.put(typeRL, item);
    }

    private void logAllMachines() {
        ComplexityAnalyzer.LOGGER.debug("=== All registered machines ({}) ===", mapping.size());
        var keys = new ObjectArrayList<>(mapping.keySet());
        keys.sort(Comparator.comparing(ResourceLocation::toString));
        for (var key : keys) {
            var item = mapping.get(key);
            ComplexityAnalyzer.LOGGER.debug("  {} -> {}", key, GameRegistryManager.getItemId(item));
        }
    }
}