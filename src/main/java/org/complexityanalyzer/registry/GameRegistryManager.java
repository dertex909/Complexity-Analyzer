package org.complexityanalyzer.registry;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.ComplexityAnalyzer;

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
    private static final ObjectList<Block> ALL_BLOCKS = new ObjectArrayList<>();
    private static final ObjectList<Item> ALL_ITEMS = new ObjectArrayList<>();
    private static final ObjectList<Fluid> ALL_FLUIDS = new ObjectArrayList<>();
    private static final ObjectList<RecipeType<?>> ALL_RECIPE_TYPES = new ObjectArrayList<>();
    private static final ObjectList<EntityType<?>> ALL_ENTITY_TYPES = new ObjectArrayList<>();
    private static boolean initialized = false;

    private GameRegistryManager() {
    }

    public static void initialize() {
        if (initialized) {
            ComplexityAnalyzer.LOGGER.warn("[GameRegistryManager] Already initialized, skipping");
            return;
        }

        long startTime = System.currentTimeMillis();

        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (state.isAir()) continue;
            if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) continue;

            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            BLOCK_MAP.put(id, block);
            BLOCK_ID_MAP.put(block, id);
            ALL_BLOCKS.add(block);
        }

        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            ITEM_MAP.put(id, item);
            ITEM_ID_MAP.put(item, id);
            ALL_ITEMS.add(item);
        }

        for (Fluid fluid : BuiltInRegistries.FLUID) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
            FLUID_MAP.put(id, fluid);
            FLUID_ID_MAP.put(fluid, id);
            ALL_FLUIDS.add(fluid);
        }

        for (RecipeType<?> recipeType : BuiltInRegistries.RECIPE_TYPE) {
            ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
            RECIPE_TYPE_MAP.put(id, recipeType);
            RECIPE_TYPE_ID_MAP.put(recipeType, id);
            ALL_RECIPE_TYPES.add(recipeType);
        }

        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            ENTITY_TYPE_MAP.put(id, entityType);
            ENTITY_TYPE_ID_MAP.put(entityType, id);
            ALL_ENTITY_TYPES.add(entityType);
        }

        initialized = true;
        long duration = System.currentTimeMillis() - startTime;
        ComplexityAnalyzer.LOGGER.info("[GameRegistryManager] Initialized in {}ms. Blocks: {}, Items: {}, Fluids: {}, RecipeTypes: {}, EntityTypes: {}",
                duration, ALL_BLOCKS.size(), ALL_ITEMS.size(), ALL_FLUIDS.size(), ALL_RECIPE_TYPES.size(), ALL_ENTITY_TYPES.size());
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

    public static ObjectList<Block> getAllBlocks() {
        return ALL_BLOCKS;
    }

    public static ObjectList<Item> getAllItems() {
        return ALL_ITEMS;
    }

    public static ObjectList<Fluid> getAllFluids() {
        return ALL_FLUIDS;
    }

    public static ObjectList<RecipeType<?>> getAllRecipeTypes() {
        return ALL_RECIPE_TYPES;
    }

    public static ObjectList<EntityType<?>> getAllEntityTypes() {
        return ALL_ENTITY_TYPES;
    }

    public static boolean isValidBlock(Block block) {
        return BLOCK_MAP.containsValue(block);
    }

    public static boolean isValidItem(Item item) {
        return ITEM_MAP.containsValue(item);
    }

    public static boolean isValidFluid(Fluid fluid) {
        return FLUID_MAP.containsValue(fluid);
    }

    public static boolean isValidRecipeType(RecipeType<?> recipeType) {
        return RECIPE_TYPE_MAP.containsValue(recipeType);
    }

    public static boolean isValidEntityType(EntityType<?> entityType) {
        return ENTITY_TYPE_MAP.containsValue(entityType);
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
