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

package org.complexityanalyzer.core;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.ComplexityAnalyzer;

import static net.minecraft.world.item.Items.AIR;

public class GameRegistryManager {

    private static final Object2ObjectMap<ResourceLocation, Block> BLOCK_MAP = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectMap<ResourceLocation, Item> ITEM_MAP = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectMap<ResourceLocation, Fluid> FLUID_MAP = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectMap<ResourceLocation, RecipeType<?>> RECIPE_TYPE_MAP = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectMap<ResourceLocation, EntityType<?>> ENTITY_TYPE_MAP = new Object2ObjectOpenHashMap<>();

    private static final Reference2ObjectMap<Block, ResourceLocation> BLOCK_ID_MAP = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectMap<Item, ResourceLocation> ITEM_ID_MAP = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectMap<Fluid, ResourceLocation> FLUID_ID_MAP = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectMap<RecipeType<?>, ResourceLocation> RECIPE_TYPE_ID_MAP = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectMap<EntityType<?>, ResourceLocation> ENTITY_TYPE_ID_MAP = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectMap<VillagerProfession, ResourceLocation> PROFESSION_ID_MAP = new Reference2ObjectOpenHashMap<>();

    private static final ObjectList<Block> ALL_BLOCKS = new ObjectArrayList<>();
    private static final ObjectList<Item> ALL_ITEMS = new ObjectArrayList<>();
    private static final ObjectList<Fluid> ALL_FLUIDS = new ObjectArrayList<>();
    private static final ObjectList<RecipeType<?>> ALL_RECIPE_TYPES = new ObjectArrayList<>();
    private static final ObjectList<EntityType<?>> ALL_ENTITY_TYPES = new ObjectArrayList<>();
    private static final ObjectList<VillagerProfession> ALL_PROFESSIONS = new ObjectArrayList<>();
    private static final ObjectList<ResourceLocation> ALL_ITEM_IDS = new ObjectArrayList<>();
    private static boolean initialized = false;

    private GameRegistryManager() {
    }

    public static void initialize() {
        if (initialized) {
            ComplexityAnalyzer.LOGGER.warn("[GameRegistryManager] Already initialized, skipping");
            return;
        }

        long startTime = System.currentTimeMillis();

        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            BLOCK_MAP.put(id, block);
            BLOCK_ID_MAP.put(block, id);
            ALL_BLOCKS.add(block);
        }

        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            ITEM_MAP.put(id, item);
            ITEM_ID_MAP.put(item, id);
            ALL_ITEMS.add(item);
            ALL_ITEM_IDS.add(id);
        }

        for (var fluid : BuiltInRegistries.FLUID) {
            var id = BuiltInRegistries.FLUID.getKey(fluid);
            FLUID_MAP.put(id, fluid);
            FLUID_ID_MAP.put(fluid, id);
            ALL_FLUIDS.add(fluid);
        }

        for (var recipeType : BuiltInRegistries.RECIPE_TYPE) {
            var id = BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
            RECIPE_TYPE_MAP.put(id, recipeType);
            RECIPE_TYPE_ID_MAP.put(recipeType, id);
            ALL_RECIPE_TYPES.add(recipeType);
        }

        for (var entityType : BuiltInRegistries.ENTITY_TYPE) {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            ENTITY_TYPE_MAP.put(id, entityType);
            ENTITY_TYPE_ID_MAP.put(entityType, id);
            ALL_ENTITY_TYPES.add(entityType);
        }

        for (var profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
            PROFESSION_ID_MAP.put(profession, id);
            ALL_PROFESSIONS.add(profession);
        }

        initialized = true;
        long duration = System.currentTimeMillis() - startTime;
        ComplexityAnalyzer.LOGGER.info("[GameRegistryManager] Initialized in {}ms. Blocks: {}, Items: {}, Fluids: {}, RecipeTypes: {}, EntityTypes: {}, Professions: {}",
                duration, ALL_BLOCKS.size(), ALL_ITEMS.size(), ALL_FLUIDS.size(), ALL_RECIPE_TYPES.size(), ALL_ENTITY_TYPES.size(), ALL_PROFESSIONS.size());
    }

    public static void clear() {
        BLOCK_MAP.clear();
        ITEM_MAP.clear();
        FLUID_MAP.clear();
        RECIPE_TYPE_MAP.clear();
        ENTITY_TYPE_MAP.clear();

        BLOCK_ID_MAP.clear();
        ITEM_ID_MAP.clear();
        FLUID_ID_MAP.clear();
        RECIPE_TYPE_ID_MAP.clear();
        ENTITY_TYPE_ID_MAP.clear();
        PROFESSION_ID_MAP.clear();

        ALL_BLOCKS.clear();
        ALL_ITEMS.clear();
        ALL_FLUIDS.clear();
        ALL_RECIPE_TYPES.clear();
        ALL_ENTITY_TYPES.clear();
        ALL_PROFESSIONS.clear();
        ALL_ITEM_IDS.clear();
        initialized = false;
        ComplexityAnalyzer.LOGGER.debug("[GameRegistryManager] Registry cache cleared.");
    }

    public static Item getFirstItemByTag(TagKey<Item> tagKey) {
        var optionalTag = BuiltInRegistries.ITEM.getTag(tagKey);
        if (optionalTag.isPresent()) for (var holder : optionalTag.get()) {
            var item = holder.value();
            if (item != AIR) return item;
        }
        return AIR;
    }

    public static Fluid getFirstFluidByTag(TagKey<Fluid> tagKey) {
        var optionalTag = BuiltInRegistries.FLUID.getTag(tagKey);
        if (optionalTag.isPresent()) for (var holder : optionalTag.get()) {
            var fluid = holder.value();
            if (fluid != Fluids.EMPTY) return fluid;
        }
        return Fluids.EMPTY;
    }

    public static Block getBlock(ResourceLocation id) {
        return BLOCK_MAP.get(id);
    }

    public static Item getItem(ResourceLocation id) {
        return ITEM_MAP.get(id);
    }

    public static Fluid getFluid(ResourceLocation id) {
        return FLUID_MAP.get(id);
    }

    public static RecipeType<?> getRecipeType(ResourceLocation id) {
        return RECIPE_TYPE_MAP.get(id);
    }

    public static EntityType<?> getEntityType(ResourceLocation id) {
        return ENTITY_TYPE_MAP.get(id);
    }

    public static ResourceLocation getBlockId(Block block) {
        return BLOCK_ID_MAP.get(block);
    }

    public static ResourceLocation getItemId(Item item) {
        return ITEM_ID_MAP.get(item);
    }

    public static ResourceLocation getFluidId(Fluid fluid) {
        return FLUID_ID_MAP.get(fluid);
    }

    public static ResourceLocation getRecipeTypeId(RecipeType<?> recipeType) {
        return RECIPE_TYPE_ID_MAP.get(recipeType);
    }

    public static ResourceLocation getEntityTypeId(EntityType<?> entityType) {
        return ENTITY_TYPE_ID_MAP.get(entityType);
    }

    public static ResourceLocation getProfessionId(VillagerProfession profession) {
        return PROFESSION_ID_MAP.get(profession);
    }

    public static ObjectList<ResourceLocation> getItemIds() {
        return ALL_ITEM_IDS;
    }

    public static ObjectList<Block> getAllBlocks() {
        return ALL_BLOCKS;
    }

    public static ObjectList<Item> getAllItems() {
        return ALL_ITEMS;
    }

    public static ObjectList<Fluid> getAllFluids() {
        return ALL_FLUIDS;
    }

    public static ObjectList<EntityType<?>> getAllEntityTypes() {
        return ALL_ENTITY_TYPES;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}