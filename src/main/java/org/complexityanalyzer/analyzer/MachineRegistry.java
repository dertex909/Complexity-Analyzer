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
import org.complexityanalyzer.cache.MachineRegistryCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static net.minecraft.core.BlockPos.ZERO;

public class MachineRegistry {

    private static final ClassInfo EMPTY_INFO = new ClassInfo(new Method[0], new Field[0]);
    private final Object2ObjectMap<ResourceLocation, ObjectList<Item>> mapping = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectMap<Class<?>, ClassInfo> classInfoCache = new Object2ObjectOpenHashMap<>();
    private boolean initialized = false;

    public void initialize(MinecraftServer server) {
        if (initialized) return;
        int vanilla = registerVanilla();
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Registered {} vanilla machines", vanilla);

        boolean cacheEnabled = ComplexityConfig.ENABLE_CACHE.get();
        var cacheFile = cacheEnabled ? MachineRegistryCache.INSTANCE.file(server) : null;
        MachineRegistryCache.Fingerprint fingerprint = null;
        if (cacheFile != null) {
            fingerprint = MachineRegistryCache.INSTANCE.computeFingerprint();
            int restored = MachineRegistryCache.INSTANCE.tryLoad(cacheFile, fingerprint, mapping);
            if (restored >= 0) {
                ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Loaded {} machine mappings from cache (block scan skipped)", restored);
                initialized = true;
                return;
            }
        }

        int dynamic = registerModdedMachines();
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Registered {} dynamic modded machines via BlockEntity scanning", dynamic);

        if (cacheFile != null) MachineRegistryCache.INSTANCE.save(cacheFile, fingerprint, mapping);

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
        int entityBlocks = 0;
        int errors = 0;

        var blocks = GameRegistryManager.getAllBlocks();
        int totalBlocks = blocks.size();

        for (var block : blocks) {
            try {
                var rt = findRecipeTypeDeep(block, 0, new ReferenceOpenHashSet<>());
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
                            var rt = findRecipeTypeDeep(be, 0, new ReferenceOpenHashSet<>());
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
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Dynamic machine scan results: totalBlocks={}, entityBlocks={}, registered={}, errors={}",
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

    private ClassInfo classInfo(Class<?> clazz) {
        var info = classInfoCache.get(clazz);
        if (info != null) return info;
        info = buildClassInfo(clazz);
        classInfoCache.put(clazz, info);
        return info;
    }

    private ClassInfo buildClassInfo(Class<?> clazz) {
        var name = clazz.getName();
        if (name.startsWith("java.") || name.startsWith("net.minecraft.")) return EMPTY_INFO;

        var methods = new ObjectArrayList<Method>();
        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() == 0 && RecipeType.class.isAssignableFrom(method.getReturnType())) try {
                method.setAccessible(true);
                methods.add(method);
            } catch (Throwable ignored) {
            }
        }

        var fields = new ObjectArrayList<Field>();
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return new ClassInfo(methods.toArray(new Method[0]), fields.toArray(new Field[0]));
    }

    @Nullable
    private RecipeType<?> findRecipeTypeDeep(Object obj, int depth, ReferenceSet<Object> visited) {
        if (obj == null || depth > 3 || !visited.add(obj)) return null;
        var info = classInfo(obj.getClass());

        for (var method : info.recipeMethods()) {
            try {
                var recipeType = (RecipeType<?>) method.invoke(obj);
                if (recipeType != null) return recipeType;
            } catch (Throwable ignored) {
            }
        }

        for (var field : info.fields()) {
            try {
                var val = field.get(obj);
                if (val != null) {
                    if (val instanceof RecipeType<?> rt) return rt;
                    var deep = findRecipeTypeDeep(val, depth + 1, visited);
                    if (deep != null) return deep;
                }
            } catch (Throwable ignored) {
            }
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
            ComplexityAnalyzer.LOGGER.warn("[MachineRegistry] Failed to register machine: {} -> {} (item not found)", recipeTypeId, itemId);
            return;
        }

        mapping.computeIfAbsent(typeRL, k -> new ObjectArrayList<>()).add(item);
    }

    private record ClassInfo(Method[] recipeMethods, Field[] fields) {
    }
}