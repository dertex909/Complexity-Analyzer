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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest.machine;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.MachineRegistryCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.harvest.debug.MachineRegistryDebugLogger;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import static net.minecraft.core.BlockPos.ZERO;
import static net.minecraft.world.item.Items.AIR;

public class MachineRegistry {

    private final Object2ObjectMap<ResourceLocation, ObjectList<Item>> idMapping = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectMap<RecipeType<?>, ObjectList<Item>> instanceMapping = new Reference2ObjectOpenHashMap<>();
    private final MachineReflectionScanner reflectionScanner = new MachineReflectionScanner(this::registerDynamicMachine);

    private boolean initialized = false;

    public void initialize(MinecraftServer server) {
        if (initialized) return;
        registerVanilla();

        var cacheFile = ComplexityConfig.ENABLE_CACHE.get() ? MachineRegistryCache.INSTANCE.file(server) : null;
        var fingerprint = cacheFile != null ? MachineRegistryCache.INSTANCE.computeFingerprint() : null;

        if (cacheFile != null && tryRestoreCache(cacheFile, fingerprint)) {
            initialized = true;
            return;
        }

        int dynamic = registerModdedMachines(server);
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Total registered {} machines", dynamic);

        if (cacheFile != null) MachineRegistryCache.INSTANCE.save(cacheFile, fingerprint, idMapping);

        initialized = true;
    }

    private boolean tryRestoreCache(Path cacheFile, MachineRegistryCache.Fingerprint fingerprint) {
        int restored = MachineRegistryCache.INSTANCE.tryLoad(cacheFile, fingerprint, idMapping);
        if (restored < 0) return false;

        for (var entry : idMapping.object2ObjectEntrySet()) {
            var rt = BuiltInRegistries.RECIPE_TYPE.get(entry.getKey());
            if (rt == null) continue;

            var instList = instanceMapping.computeIfAbsent(rt, k -> new ObjectArrayList<>());
            for (var item : entry.getValue()) if (!instList.contains(item)) instList.add(item);
        }

        ComplexityAnalyzer.LOGGER.debug("[MachineRegistry] Loaded {} machine mappings from cache (block scan skipped)", restored);
        return true;
    }

    private int registerModdedMachines(MinecraftServer server) {
        var blocks = GameRegistryManager.getAllBlocks();
        var logger = new MachineRegistryDebugLogger(server, blocks.size());

        var blockEntityCache = new Reference2ObjectOpenHashMap<Block, BlockEntity>();
        var uniqueClasses = collectUniqueTargetClasses(blocks, blockEntityCache);
        var classAsmResults = MachineAsmScanner.precomputeAsmResults(uniqueClasses);

        int registeredCount = 0;
        int entityBlocks = 0;
        int errors = 0;
        var classStaticResults = new Object2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>>();
        var deepScanVisited = new ReferenceOpenHashSet<>();

        for (var block : blocks) {
            var machineItem = block.asItem();
            if (machineItem == AIR) continue;

            var blockId = GameRegistryManager.getBlockId(block);
            logger.logBlockHeader(blockId, machineItem, block.getClass());

            try {
                var be = blockEntityCache.get(block);
                if (block instanceof EntityBlock) {
                    entityBlocks++;
                    if (be != null) logger.logBeCreated(be);
                    else logger.logBeCreateNull();
                } else {
                    logger.logNonBeBlock();
                }

                int matched = processBlock(block, machineItem, be, classAsmResults, classStaticResults, deepScanVisited, logger);
                registeredCount += matched;
                logger.logBlockResult(blockId, matched);
            } catch (Throwable t) {
                errors++;
                logger.logFatalBlockError(t);
            }
        }

        registeredCount += MachineStaticHolderScanner.scanModStaticHoldersAndRegistries(this::registerDynamicMachine);
        logger.finishAndSave(blocks.size(), entityBlocks, registeredCount, errors);
        return registeredCount;
    }

    private ReferenceSet<Class<?>> collectUniqueTargetClasses(ObjectList<Block> blocks, Reference2ObjectMap<Block, BlockEntity> beCache) {
        var uniqueClasses = new ReferenceOpenHashSet<Class<?>>();
        for (var block : blocks) {
            if (block.asItem() == AIR) continue;

            if (block instanceof EntityBlock entityBlock) try {
                var be = entityBlock.newBlockEntity(ZERO, block.defaultBlockState());
                if (be != null) {
                    beCache.put(block, be);
                    uniqueClasses.add(be.getClass());
                    continue;
                }
            } catch (Throwable ignored) {
            }
            uniqueClasses.add(block.getClass());
        }
        return uniqueClasses;
    }

    private int processBlock(Block block, Item item, @Nullable BlockEntity be,
                             Object2ObjectMap<Class<?>, ObjectList<RecipeType<?>>> asmResults,
                             Object2ObjectMap<Class<?>, ObjectList<RecipeType<?>>> staticResults,
                             ReferenceSet<Object> deepVisited,
                             MachineRegistryDebugLogger logger) {
        var targetClass = be != null ? be.getClass() : block.getClass();
        int matchedCount = scanClassBytecodeASM(targetClass, item, (Object2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>>) asmResults, logger);

        if (be != null) {
            matchedCount += reflectionScanner.scanBlockEntityInstance(be, item, logger);
            if (matchedCount == 0) {
                logger.logDeepScanStart();
                deepVisited.clear();
                var rt = reflectionScanner.findRecipeTypeDeep(be, 0, deepVisited, logger);
                if (rt != null && registerDynamicMachine(rt, item)) {
                    logger.logDeepResult(rt, item);
                    matchedCount++;
                } else {
                    logger.logDeepResult(null, item);
                }
            }
        } else {
            matchedCount += reflectionScanner.scanStaticFieldsOnly(block.getClass(), item, staticResults, logger);
        }

        return matchedCount;
    }

    private int scanClassBytecodeASM(Class<?> clazz, Item machineItem, Object2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>> classAsmResults, MachineRegistryDebugLogger logger) {
        if (!MachineTypeUnwrapper.curClsValid(clazz)) return 0;

        var recipeTypes = classAsmResults.get(clazz);
        if (recipeTypes == null || recipeTypes.isEmpty()) return 0;

        int count = 0;
        logger.logAsmScanStart(clazz, recipeTypes.size());
        for (var rt : recipeTypes) {
            try {
                boolean matched = false;
                if (registerDynamicMachine(rt, machineItem)) {
                    matched = true;
                    count++;
                }
                logger.logAsmRef(clazz.getName(), "resolvedViaTransitiveASM", rt, matched, null);
            } catch (Throwable t) {
                logger.logAsmRef(clazz.getName(), "resolvedViaTransitiveASM", null, false, t);
            }
        }
        return count;
    }

    public boolean registerDynamicMachine(RecipeType<?> recipeType, Item item) {
        if (item == AIR) return false;

        boolean added = false;
        var instList = instanceMapping.computeIfAbsent(recipeType, k -> new ObjectArrayList<>());
        if (!instList.contains(item)) {
            instList.add(item);
            added = true;
        }

        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
        if (typeId != null) {
            var list = idMapping.computeIfAbsent(typeId, k -> new ObjectArrayList<>());
            if (!list.contains(item)) {
                list.add(item);
                added = true;
                ComplexityAnalyzer.LOGGER.debug("[MachineRegistry] Mapped recipe type '{}' -> Machine item '{}'", typeId, GameRegistryManager.getItemId(item));
            }
        }
        return added;
    }

    private void registerVanilla() {
        register("minecraft:crafting", "minecraft:crafting_table");
        register("minecraft:smelting", "minecraft:furnace");
        register("minecraft:blasting", "minecraft:blast_furnace");
        register("minecraft:smoking", "minecraft:smoker");
        register("minecraft:campfire_cooking", "minecraft:campfire");
        register("minecraft:stonecutting", "minecraft:stonecutter");
        register("minecraft:smithing", "minecraft:smithing_table");
    }

    private void register(String recipeTypeId, String itemId) {
        var typeRL = ResourceLocation.parse(recipeTypeId);
        var itemRL = ResourceLocation.parse(itemId);
        var item = GameRegistryManager.getItem(itemRL);

        if (item == null || item == AIR) {
            ComplexityAnalyzer.LOGGER.warn("[MachineRegistry] Failed to register machine: {} -> {} (item not found)", recipeTypeId, itemId);
            return;
        }

        var list = idMapping.computeIfAbsent(typeRL, k -> new ObjectArrayList<>());
        if (!list.contains(item)) list.add(item);

        var rt = GameRegistryManager.getRecipeType(typeRL);
        if (rt != null) {
            var instList = instanceMapping.computeIfAbsent(rt, k -> new ObjectArrayList<>());
            if (!instList.contains(item)) instList.add(item);
        }

        ComplexityAnalyzer.LOGGER.debug("[MachineRegistry] Mapped vanilla machine: '{}' -> '{}'", recipeTypeId, itemId);
    }

    @Nullable
    public Item getMachineForRecipe(RecipeType<?> type) {
        var list = getMachinesForRecipe(type);
        return (list != null && !list.isEmpty()) ? list.getFirst() : null;
    }

    @Nullable
    public ObjectList<Item> getMachinesForRecipe(RecipeType<?> type) {
        if (!initialized || type == null) return null;
        return instanceMapping.get(type);
    }
}