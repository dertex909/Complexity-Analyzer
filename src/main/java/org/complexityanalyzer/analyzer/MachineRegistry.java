/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import static net.minecraft.core.BlockPos.ZERO;

public class MachineRegistry {

    private final Object2ObjectMap<ResourceLocation, ObjectList<Item>> mapping = new Object2ObjectOpenHashMap<>();
    private boolean initialized = false;

    public void initialize(MinecraftServer server) {
        if (initialized) return;
        int vanilla = registerVanilla();
        ComplexityAnalyzer.LOGGER.info("Registered {} vanilla machines", vanilla);

        boolean cacheEnabled = ComplexityConfig.HARVEST_ENABLE_CACHE.get();
        var cacheFile = cacheEnabled ? MachineRegistryCache.cacheFile(server) : null;
        MachineRegistryCache.Fingerprint fingerprint = null;
        if (cacheFile != null) {
            fingerprint = MachineRegistryCache.computeFingerprint();
            int restored = MachineRegistryCache.tryLoad(cacheFile, fingerprint, mapping);
            if (restored >= 0) {
                ComplexityAnalyzer.LOGGER.info("Loaded {} machine mappings from cache (block scan skipped)", restored);
                initialized = true;
                return;
            }
        }

        int dynamic = registerModdedMachines();
        ComplexityAnalyzer.LOGGER.info("Registered {} dynamic modded machines via BlockEntity scanning", dynamic);

        if (cacheFile != null) MachineRegistryCache.save(cacheFile, fingerprint, mapping);

        initialized = true;
    }

    @Nullable
    public ObjectList<Item> getMachinesForRecipe(RecipeType<?> type) {
        if (!initialized) return null;
        var typeId = GameRegistryManager.getRecipeTypeId(type);
        return (typeId != null) ? mapping.get(typeId) : null;
    }

    @Nullable
    public Item getMachineForRecipe(RecipeType<?> type) {
        var list = getMachinesForRecipe(type);
        return (list != null && !list.isEmpty()) ? list.getFirst() : null;
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

    private int registerModdedMachines() {
        int registeredCount = 0;
        int totalBlocks = 0;
        int entityBlocks = 0;
        int errors = 0;

        for (var block : GameRegistryManager.getAllBlocks()) {
            totalBlocks++;
            try {
                ReferenceSet<Object> blockVisited = new ReferenceOpenHashSet<>();
                var rt = findRecipeTypeDeep(block, 0, blockVisited);
                if (rt != null && registerDynamicMachine(rt, block.asItem())) registeredCount++;
            } catch (Throwable ignored) {
            }

            if (block instanceof EntityBlock entityBlock) {
                entityBlocks++;
                try {
                    var be = entityBlock.newBlockEntity(ZERO, block.defaultBlockState());
                    if (be != null) {
                        var beClass = be.getClass();
                        int scanned = scanBlockEntityClass(beClass, be, block);
                        if (scanned == 0) {
                            ReferenceSet<Object> visited = new ReferenceOpenHashSet<>();
                            var rt = findRecipeTypeDeep(be, 0, visited);
                            if (rt != null && registerDynamicMachine(rt, block.asItem())) registeredCount++;
                        } else {
                            registeredCount += scanned;
                        }
                    }
                } catch (Throwable t) {
                    errors++;
                }
            }
        }
        ComplexityAnalyzer.LOGGER.info("Dynamic machine scan results: totalBlocks={}, entityBlocks={}, registered={}, errors={}",
                totalBlocks, entityBlocks, registeredCount, errors);
        return registeredCount;
    }

    private int scanBlockEntityClass(Class<?> beClass, BlockEntity be, Block block) {
        int count = 0;

        for (var method : beClass.getMethods()) {
            if (method.getParameterCount() == 0 && RecipeType.class.isAssignableFrom(method.getReturnType())) try {
                method.setAccessible(true);
                var recipeType = (RecipeType<?>) method.invoke(be);
                if (recipeType != null && registerDynamicMachine(recipeType, block.asItem())) count++;
            } catch (Throwable ignored) {
            }
        }

        var currentClass = beClass;
        while (currentClass != null && currentClass != Object.class) {
            for (var field : currentClass.getDeclaredFields()) {
                if (RecipeType.class.isAssignableFrom(field.getType())) try {
                    field.setAccessible(true);
                    var recipeType = (RecipeType<?>) field.get(be);
                    if (recipeType != null && registerDynamicMachine(recipeType, block.asItem())) count++;
                } catch (Throwable ignored) {
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return count;
    }

    private RecipeType<?> findRecipeTypeDeep(Object obj, int depth, ReferenceSet<Object> visited) {
        if (obj == null || depth > 3 || !visited.add(obj)) return null;
        var clazz = obj.getClass();
        if (clazz.getName().startsWith("java.") || clazz.getName().startsWith("net.minecraft.")) return null;

        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() == 0 && RecipeType.class.isAssignableFrom(method.getReturnType())) try {
                method.setAccessible(true);
                var recipeType = (RecipeType<?>) method.invoke(obj);
                if (recipeType != null) return recipeType;
            } catch (Throwable ignored) {
            }
        }

        var current = clazz;
        while (current != null && current != Object.class) {
            for (var field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    var val = field.get(obj);
                    if (val != null) {
                        if (val instanceof RecipeType<?> rt) return rt;
                        var deep = findRecipeTypeDeep(val, depth + 1, visited);
                        if (deep != null) return deep;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean registerDynamicMachine(RecipeType<?> recipeType, Item item) {
        if (item == Items.AIR) return false;
        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
        if (typeId == null) return false;

        var list = mapping.computeIfAbsent(typeId, k -> new ObjectArrayList<>());
        if (!list.contains(item)) {
            list.add(item);
            return true;
        }
        return false;
    }

    private void register(String recipeTypeId, String itemId) {
        var typeRL = ResourceLocation.parse(recipeTypeId);
        var itemRL = ResourceLocation.parse(itemId);
        var item = GameRegistryManager.getItem(itemRL);

        if (item == null || item == Items.AIR) {
            ComplexityAnalyzer.LOGGER.warn("Failed to register machine: {} -> {} (item not found)", recipeTypeId, itemId);
            return;
        }

        mapping.computeIfAbsent(typeRL, k -> new ObjectArrayList<>()).add(item);
    }
}