package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.complexityanalyzer.bytecode.graph.MethodRef;

public final class SemanticAnchorRegistry {

    private final Object2ObjectOpenHashMap<MethodRef, SemanticTag> exactAnchors;
    private final Object2ObjectOpenHashMap<String, SemanticTag> nameAnchors;

    private SemanticAnchorRegistry(Builder builder) {
        this.exactAnchors = new Object2ObjectOpenHashMap<>(builder.exactAnchors);
        this.nameAnchors = new Object2ObjectOpenHashMap<>(builder.nameAnchors);
    }

    public SemanticTag resolve(MethodRef ref) {
        SemanticTag tag = exactAnchors.get(ref);
        if (tag != null) return tag;
        return nameAnchors.get(ref.name());
    }

    public boolean isAnchor(MethodRef ref) {
        return exactAnchors.containsKey(ref) || nameAnchors.containsKey(ref.name());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SemanticAnchorRegistry defaultRegistry() {
        Builder b = new Builder();

        // Мир / блоки
        b.name("setBlockState", SemanticTag.WORLD_MUTATION);
        b.name("removeBlock", SemanticTag.WORLD_MUTATION);
        b.name("destroyBlock", SemanticTag.WORLD_MUTATION);
        b.name("explode", SemanticTag.WORLD_MUTATION);
        b.name("getBlockState", SemanticTag.BLOCK_CHECK);
        b.name("is", SemanticTag.BLOCK_CHECK);
        b.exact(new MethodRef("net/minecraft/world/item/ItemStack", "is", "(Lnet/minecraft/tags/TagKey;)Z"), SemanticTag.TAG_CHECK);
        b.exact(new MethodRef("net/minecraft/world/level/block/state/BlockState", "is", "(Lnet/minecraft/tags/TagKey;)Z"), SemanticTag.TAG_CHECK);
        b.name("getValue", SemanticTag.REDSTONE_CHECK);
        b.name("getComponents", SemanticTag.DATA_COMPONENT_CHECK);

        // Сущности
        b.name("addFreshEntity", SemanticTag.ENTITY_SPAWN);
        b.name("spawn", SemanticTag.ENTITY_SPAWN);
        b.name("kill", SemanticTag.ENTITY_KILL);
        b.name("remove", SemanticTag.ENTITY_KILL);
        b.name("discard", SemanticTag.ENTITY_KILL);
        b.name("getType", SemanticTag.ENTITY_CHECK);
        b.name("isAlive", SemanticTag.ENTITY_CHECK);

        // Предметы
        b.name("shrink", SemanticTag.ITEM_CONSUME);
        b.name("hurtAndBreak", SemanticTag.ITEM_CONSUME);
        b.name("consume", SemanticTag.ITEM_CONSUME);
        b.name("setDamageValue", SemanticTag.ITEM_CONSUME);
        b.exact(new MethodRef("net/minecraft/world/item/ItemStack", "<init>", "(Lnet/minecraft/world/item/Item;)V"), SemanticTag.ITEM_PRODUCE);
        b.name("addItem", SemanticTag.ITEM_GIVE);
        b.name("addItemStackToInventory", SemanticTag.ITEM_GIVE);
        b.name("extractItem", SemanticTag.ITEM_MOVE);
        b.name("insertItem", SemanticTag.ITEM_MOVE);
        b.name("getStackInSlot", SemanticTag.ITEM_MOVE);
        b.name("setStackInSlot", SemanticTag.ITEM_MOVE);
        b.name("getItem", SemanticTag.ITEM_GET_TYPE);
        b.exact(new MethodRef("net/minecraft/world/item/ItemStack", "get", "(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"), SemanticTag.DATA_COMPONENT_CHECK);
        b.exact(new MethodRef("net/minecraft/world/item/ItemStack", "has", "(Lnet/minecraft/core/component/DataComponentType;)Z"), SemanticTag.DATA_COMPONENT_CHECK);
        b.name("getMainHandItem", SemanticTag.ITEM_HELD_CHECK);
        b.name("getOffhandItem", SemanticTag.ITEM_HELD_CHECK);
        b.name("getHeldItem", SemanticTag.ITEM_HELD_CHECK);
        b.name("getInventory", SemanticTag.INVENTORY_ACCESS);
        b.name("getSelected", SemanticTag.INVENTORY_ACCESS);

        // Энергия / жидкость
        b.name("extractEnergy", SemanticTag.ENERGY_CONSUME);
        b.name("drainEnergy", SemanticTag.ENERGY_CONSUME);
        b.name("receiveEnergy", SemanticTag.ENERGY_PRODUCE);
        b.name("drain", SemanticTag.FLUID_CONSUME);
        b.name("extractFluid", SemanticTag.FLUID_CONSUME);
        b.name("fill", SemanticTag.FLUID_PRODUCE);

        // Рецепты
        b.name("requires", SemanticTag.RECIPE_INPUT);
        b.name("define", SemanticTag.RECIPE_INPUT);
        b.name("pattern", SemanticTag.RECIPE_INPUT);
        b.name("addIngredient", SemanticTag.RECIPE_INPUT);
        b.name("inputItems", SemanticTag.RECIPE_INPUT);
        b.name("notConsumable", SemanticTag.RECIPE_INPUT);
        b.name("chancedInput", SemanticTag.RECIPE_INPUT);
        b.name("input", SemanticTag.RECIPE_INPUT);
        b.name("save", SemanticTag.RECIPE_OUTPUT);
        b.name("result", SemanticTag.RECIPE_OUTPUT);
        b.name("setOutput", SemanticTag.RECIPE_OUTPUT);
        b.name("outputItems", SemanticTag.RECIPE_OUTPUT);
        b.name("chancedOutput", SemanticTag.RECIPE_OUTPUT);
        b.name("output", SemanticTag.RECIPE_OUTPUT);
        b.name("inputFluids", SemanticTag.RECIPE_FLUID_INPUT);
        b.name("fluidInputs", SemanticTag.RECIPE_FLUID_INPUT);
        b.name("outputFluids", SemanticTag.RECIPE_FLUID_OUTPUT);
        b.name("fluidOutputs", SemanticTag.RECIPE_FLUID_OUTPUT);
        b.name("setProcessingTime", SemanticTag.RECIPE_PROCESS);
        b.name("duration", SemanticTag.RECIPE_DURATION);
        b.name("EUt", SemanticTag.RECIPE_ENERGY_COST);
        b.name("eu", SemanticTag.RECIPE_ENERGY_COST);
        b.name("circuitMeta", SemanticTag.RECIPE_CIRCUIT);
        b.name("recipeBuilder", SemanticTag.RECIPE_BUILDER_START);
        b.name("builder", SemanticTag.RECIPE_BUILDER_START);
        b.name("machine", SemanticTag.MACHINE_DEFINITION);
        b.name("simpleMachine", SemanticTag.MACHINE_DEFINITION);
        b.name("multiblock", SemanticTag.MACHINE_MULTIBLOCK);
        b.name("generator", SemanticTag.MACHINE_DEFINITION);
        b.name("steam", SemanticTag.MACHINE_TIER);
        b.name("tier", SemanticTag.MACHINE_TIER);
        b.name("tiers", SemanticTag.MACHINE_TIER);
        b.name("recipeType", SemanticTag.MACHINE_RECIPE_TYPE);
        b.name("recipeTypes", SemanticTag.MACHINE_RECIPE_TYPE);
        b.name("workable", SemanticTag.MACHINE_WORKABLE_LOGIC);
        b.name("recipeLogic", SemanticTag.MACHINE_WORKABLE_LOGIC);
        b.name("getRecipeLogic", SemanticTag.MACHINE_WORKABLE_LOGIC);
        b.name("getRecipeType", SemanticTag.MACHINE_RECIPE_TYPE);
        b.name("getCapabilities", SemanticTag.MACHINE_CAPABILITY);
        b.name("getCapability", SemanticTag.MACHINE_CAPABILITY);
        b.name("itemHandler", SemanticTag.MACHINE_CAPABILITY);
        b.name("fluidHandler", SemanticTag.MACHINE_CAPABILITY);
        b.name("energyContainer", SemanticTag.MACHINE_CAPABILITY);

        // Игрок / состояния
        b.name("getPos", SemanticTag.PLAYER_INTERACT);
        b.name("getHand", SemanticTag.PLAYER_INTERACT);
        b.name("getItemStack", SemanticTag.PLAYER_INTERACT);
        b.name("openMenu", SemanticTag.GUI_OPEN);
        b.exact(new MethodRef("net/minecraft/world/inventory/AbstractContainerMenu", "<init>", "(Lnet/minecraft/world/inventory/MenuType;I)V"), SemanticTag.GUI_OPEN);
        b.name("hurt", SemanticTag.PLAYER_HURT);
        b.name("heal", SemanticTag.PLAYER_HEAL);
        b.name("setHealth", SemanticTag.PLAYER_HEAL);
        b.name("isSprinting", SemanticTag.PLAYER_STATE_SPRINT);
        b.name("jumping", SemanticTag.PLAYER_STATE_JUMP);
        b.name("isCrouching", SemanticTag.PLAYER_STATE_SNEAK);
        b.name("isShiftKeyDown", SemanticTag.PLAYER_STATE_SNEAK);
        b.name("onGround", SemanticTag.PLAYER_STATE_GROUND);
        b.name("isOnGround", SemanticTag.PLAYER_STATE_GROUND);
        b.name("isFallFlying", SemanticTag.PLAYER_STATE_FLY);
        b.name("isSwimming", SemanticTag.PLAYER_STATE_SWIM);
        b.name("isInWater", SemanticTag.PLAYER_STATE_SWIM);
        b.name("isOnFire", SemanticTag.PLAYER_STATE_BURN);
        b.name("isPassenger", SemanticTag.PLAYER_STATE_RIDING);
        b.name("getVehicle", SemanticTag.PLAYER_STATE_RIDING);

        // Условия окружения
        b.name("isNight", SemanticTag.TIME_CHECK);
        b.name("isDay", SemanticTag.TIME_CHECK);
        b.name("getDayTime", SemanticTag.TIME_CHECK);
        b.name("dimension", SemanticTag.DIMENSION_CHECK);
        b.name("getBiome", SemanticTag.BIOME_CHECK);
        b.name("isRaining", SemanticTag.WEATHER_CHECK);
        b.name("isThundering", SemanticTag.WEATHER_CHECK);
        b.name("experienceLevel", SemanticTag.EXPERIENCE_CHECK);
        b.name("totalExperience", SemanticTag.EXPERIENCE_CHECK);
        b.name("hasEffect", SemanticTag.STATUS_EFFECT_CHECK);
        b.name("getRandomItems", SemanticTag.LOOT_TABLE_CHECK);
        b.name("create", SemanticTag.LOOT_TABLE_CHECK);
        b.name("withParameter", SemanticTag.LOOT_TABLE_CHECK);

        // Capability
        b.name("getCapability", SemanticTag.CAPABILITY_CHECK);

        // Мета
        b.name("playSound", SemanticTag.PLAY_SOUND);
        b.name("displayClientMessage", SemanticTag.SEND_MESSAGE);
        b.name("sendSystemMessage", SemanticTag.SEND_MESSAGE);

        return b.build();
    }

    public static final class Builder {
        private final Object2ObjectOpenHashMap<MethodRef, SemanticTag> exactAnchors = new Object2ObjectOpenHashMap<>();
        private final Object2ObjectOpenHashMap<String, SemanticTag> nameAnchors = new Object2ObjectOpenHashMap<>();

        public Builder exact(MethodRef ref, SemanticTag tag) {
            exactAnchors.put(ref, tag);
            return this;
        }

        public Builder name(String methodName, SemanticTag tag) {
            nameAnchors.put(methodName, tag);
            return this;
        }

        public SemanticAnchorRegistry build() {
            return new SemanticAnchorRegistry(this);
        }
    }
}
