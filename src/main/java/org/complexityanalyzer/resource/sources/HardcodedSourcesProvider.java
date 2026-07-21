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

package org.complexityanalyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.config.ComplexityConfig;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.locks.StampedLock;

import static java.util.Locale.ROOT;

public class HardcodedSourcesProvider implements IResourceSource, IHardcodedSourceRegistry {

    private static final int NORMAL_PRIORITY = 35;
    private static final int OVERRIDE_PRIORITY = 1000;
    private static HardcodedSourcesProvider INSTANCE;

    private final Reference2ObjectMap<Item, SourceRule> normalSources = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectMap<Item, SourceRule> overrideSources = new Reference2ObjectOpenHashMap<>();

    private final StampedLock lock = new StampedLock();

    public HardcodedSourcesProvider() {
        INSTANCE = this;
    }

    public static IHardcodedSourceRegistry getRegistry() {
        if (INSTANCE == null) throw new IllegalStateException("HardcodedSourcesProvider not initialized yet!");
        return INSTANCE;
    }

    @Override
    public void initialize(Level level) {
        ComplexityAnalyzer.LOGGER.info("[{}] Initializing hardcoded sources...", getName());

        registerVanillaSources();

        long stamp = lock.readLock();
        int normalSize;
        int overrideSize;
        try {
            normalSize = normalSources.size();
            overrideSize = overrideSources.size();
        } finally {
            lock.unlockRead(stamp);
        }

        ComplexityAnalyzer.LOGGER.info("[{}] Registered {} normal + {} override sources", getName(), normalSize, overrideSize);
    }

    @Override
    public void registerTransformation(Item result, Item input, Reference2DoubleMap<Item> toolWear,
                                       double baseCost, String description) {
        var ingredients = new Reference2DoubleOpenHashMap<Item>();
        ingredients.put(input, 1.0);
        if (toolWear != null) ingredients.putAll(toolWear);

        var modId = getCallingModId();
        var rule = new SourceRule(
                ingredients,
                baseCost,
                BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION,
                description,
                modId
        );

        long stamp = lock.writeLock();
        try {
            normalSources.put(result, rule);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public void registerComplexSource(Item result, Reference2DoubleMap<Item> ingredients, double baseCost,
                                      BaseResourceData.ResourceSourceType type, String description) {
        var modId = getCallingModId();
        var rule = new SourceRule(
                new Reference2DoubleOpenHashMap<>(ingredients),
                baseCost,
                type,
                description,
                modId
        );

        long stamp = lock.writeLock();
        try {
            normalSources.put(result, rule);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public void registerOverride(Item result, Reference2DoubleMap<Item> ingredients, double baseCost, String description) {
        var modId = getCallingModId();
        var rule = new SourceRule(
                new Reference2DoubleOpenHashMap<>(ingredients),
                baseCost,
                BaseResourceData.ResourceSourceType.CRAFTING,
                "[OVERRIDE by " + modId + "] " + description,
                modId
        );

        long stamp = lock.writeLock();
        try {
            overrideSources.put(result, rule);
        } finally {
            lock.unlockWrite(stamp);
        }

        ComplexityAnalyzer.LOGGER.warn("[{}] Mod {} OVERRIDING analysis for {}: {}", getName(), modId, result, description);
    }

    @Override
    public void registerUnobtainable(Item item, String reason) {
        var modId = getCallingModId();
        var rule = new SourceRule(
                Reference2DoubleMaps.emptyMap(),
                Double.POSITIVE_INFINITY,
                BaseResourceData.ResourceSourceType.UNOBTAINABLE,
                "[UNOBTAINABLE by " + modId + "] " + reason,
                modId
        );

        long stamp = lock.writeLock();
        try {
            overrideSources.put(item, rule);
        } finally {
            lock.unlockWrite(stamp);
        }

        ComplexityAnalyzer.LOGGER.info("[{}] Mod {} marked {} as unobtainable: {}", getName(), modId, item, reason);
    }

    @Override
    public boolean isRegistered(Item item) {
        long stamp = lock.tryOptimisticRead();
        boolean hasNormal = normalSources.containsKey(item);
        boolean hasOverride = overrideSources.containsKey(item);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                hasNormal = normalSources.containsKey(item);
                hasOverride = overrideSources.containsKey(item);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return hasNormal || hasOverride;
    }

    @Override
    public boolean canProvide(Item item) {
        return isRegistered(item);
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        long stamp = lock.tryOptimisticRead();
        var rule = overrideSources.get(item);
        boolean isOverride = rule != null;

        if (rule == null) rule = normalSources.get(item);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                rule = overrideSources.get(item);
                isOverride = rule != null;
                if (rule == null) rule = normalSources.get(item);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (rule == null) return null;

        var builder = new BaseResourceData.Builder(item, this)
                .sourceType(rule.type)
                .baseFactor(rule.baseCost)
                .details(rule.description)
                .sourceItems(rule.ingredients)
                .sourceSpecifier(rule.description);

        if (isOverride) {
            builder.addMetadata("override", "true");
            builder.addMetadata("override_by", rule.modId);
        }

        return builder.build();
    }

    @Override
    public int getPriority() {
        long stamp = lock.tryOptimisticRead();
        boolean empty = overrideSources.isEmpty();

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                empty = overrideSources.isEmpty();
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return empty ? NORMAL_PRIORITY : OVERRIDE_PRIORITY;
    }

    @Override
    public String getName() {
        return "HardcodedSources";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.SPECIAL_ACTION;
    }

    private String getCallingModId() {
        var stack = Thread.currentThread().getStackTrace();
        for (var element : stack) {
            var className = element.getClassName();

            if (className.startsWith("org.complexityanalyzer") || className.startsWith("java.") || className.startsWith("sun.") ||
                    className.startsWith("net.minecraft") || className.startsWith("com.mojang")) continue;

            var parts = className.split("\\.");
            if (parts.length > 0) {
                int index = 0;
                while (index < parts.length - 1) {
                    String segment = parts[index];
                    if (segment.equals("com") || segment.equals("net") || segment.equals("org") || segment.equals("io") ||
                            segment.equals("me") || segment.equals("ru") || segment.equals("github") || segment.equals("git")) {
                        index++;
                    } else {
                        break;
                    }
                }
                return parts[index].toLowerCase(ROOT);
            }
        }
        return "minecraft";
    }

    private void registerVanillaSources() {
        ComplexityAnalyzer.LOGGER.debug("[{}] Registering vanilla sources via API...", getName());

        registerWorldInteractions();
        registerBucketInteractions();
        registerSpecialLoot();
        registerSpecialCrafts();
        registerDragonItems();
    }

    private void registerWorldInteractions() {
        registerDeadCoral(Items.TUBE_CORAL_BLOCK, Items.DEAD_TUBE_CORAL_BLOCK);
        registerDeadCoral(Items.BRAIN_CORAL_BLOCK, Items.DEAD_BRAIN_CORAL_BLOCK);
        registerDeadCoral(Items.BUBBLE_CORAL_BLOCK, Items.DEAD_BUBBLE_CORAL_BLOCK);
        registerDeadCoral(Items.FIRE_CORAL_BLOCK, Items.DEAD_FIRE_CORAL_BLOCK);
        registerDeadCoral(Items.HORN_CORAL_BLOCK, Items.DEAD_HORN_CORAL_BLOCK);

        registerDeadCoral(Items.TUBE_CORAL, Items.DEAD_TUBE_CORAL);
        registerDeadCoral(Items.BRAIN_CORAL, Items.DEAD_BRAIN_CORAL);
        registerDeadCoral(Items.BUBBLE_CORAL, Items.DEAD_BUBBLE_CORAL);
        registerDeadCoral(Items.FIRE_CORAL, Items.DEAD_FIRE_CORAL);
        registerDeadCoral(Items.HORN_CORAL, Items.DEAD_HORN_CORAL);

        registerDeadCoral(Items.TUBE_CORAL_FAN, Items.DEAD_TUBE_CORAL_FAN);
        registerDeadCoral(Items.BRAIN_CORAL_FAN, Items.DEAD_BRAIN_CORAL_FAN);
        registerDeadCoral(Items.BUBBLE_CORAL_FAN, Items.DEAD_BUBBLE_CORAL_FAN);
        registerDeadCoral(Items.FIRE_CORAL_FAN, Items.DEAD_FIRE_CORAL_FAN);
        registerDeadCoral(Items.HORN_CORAL_FAN, Items.DEAD_HORN_CORAL_FAN);

        registerStripping(Items.OAK_LOG, Items.STRIPPED_OAK_LOG);
        registerStripping(Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG);
        registerStripping(Items.BIRCH_LOG, Items.STRIPPED_BIRCH_LOG);
        registerStripping(Items.JUNGLE_LOG, Items.STRIPPED_JUNGLE_LOG);
        registerStripping(Items.ACACIA_LOG, Items.STRIPPED_ACACIA_LOG);
        registerStripping(Items.CHERRY_LOG, Items.STRIPPED_CHERRY_LOG);
        registerStripping(Items.DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_LOG);
        registerStripping(Items.MANGROVE_LOG, Items.STRIPPED_MANGROVE_LOG);
        registerStripping(Items.CRIMSON_STEM, Items.STRIPPED_CRIMSON_STEM);
        registerStripping(Items.WARPED_STEM, Items.STRIPPED_WARPED_STEM);
        registerStripping(Items.BAMBOO_BLOCK, Items.STRIPPED_BAMBOO_BLOCK);

        registerStripping(Items.OAK_WOOD, Items.STRIPPED_OAK_WOOD);
        registerStripping(Items.SPRUCE_WOOD, Items.STRIPPED_SPRUCE_WOOD);
        registerStripping(Items.BIRCH_WOOD, Items.STRIPPED_BIRCH_WOOD);
        registerStripping(Items.JUNGLE_WOOD, Items.STRIPPED_JUNGLE_WOOD);
        registerStripping(Items.ACACIA_WOOD, Items.STRIPPED_ACACIA_WOOD);
        registerStripping(Items.CHERRY_WOOD, Items.STRIPPED_CHERRY_WOOD);
        registerStripping(Items.DARK_OAK_WOOD, Items.STRIPPED_DARK_OAK_WOOD);
        registerStripping(Items.MANGROVE_WOOD, Items.STRIPPED_MANGROVE_WOOD);
        registerStripping(Items.CRIMSON_HYPHAE, Items.STRIPPED_CRIMSON_HYPHAE);
        registerStripping(Items.WARPED_HYPHAE, Items.STRIPPED_WARPED_HYPHAE);

        registerTransformation(Items.CARVED_PUMPKIN, Items.PUMPKIN,
                Reference2DoubleMaps.singleton(Items.SHEARS, 0.01), 1.0, "Carving pumpkin");
        registerTransformation(Items.FARMLAND, Items.DIRT,
                Reference2DoubleMaps.singleton(Items.WOODEN_HOE, 0.01), 1.0, "Tilling dirt");

        registerConcrete(Items.WHITE_CONCRETE_POWDER, Items.WHITE_CONCRETE);
        registerConcrete(Items.ORANGE_CONCRETE_POWDER, Items.ORANGE_CONCRETE);
        registerConcrete(Items.MAGENTA_CONCRETE_POWDER, Items.MAGENTA_CONCRETE);
        registerConcrete(Items.LIGHT_BLUE_CONCRETE_POWDER, Items.LIGHT_BLUE_CONCRETE);
        registerConcrete(Items.YELLOW_CONCRETE_POWDER, Items.YELLOW_CONCRETE);
        registerConcrete(Items.LIME_CONCRETE_POWDER, Items.LIME_CONCRETE);
        registerConcrete(Items.PINK_CONCRETE_POWDER, Items.PINK_CONCRETE);
        registerConcrete(Items.GRAY_CONCRETE_POWDER, Items.GRAY_CONCRETE);
        registerConcrete(Items.LIGHT_GRAY_CONCRETE_POWDER, Items.LIGHT_GRAY_CONCRETE);
        registerConcrete(Items.CYAN_CONCRETE_POWDER, Items.CYAN_CONCRETE);
        registerConcrete(Items.PURPLE_CONCRETE_POWDER, Items.PURPLE_CONCRETE);
        registerConcrete(Items.BLUE_CONCRETE_POWDER, Items.BLUE_CONCRETE);
        registerConcrete(Items.BROWN_CONCRETE_POWDER, Items.BROWN_CONCRETE);
        registerConcrete(Items.GREEN_CONCRETE_POWDER, Items.GREEN_CONCRETE);
        registerConcrete(Items.RED_CONCRETE_POWDER, Items.RED_CONCRETE);
        registerConcrete(Items.BLACK_CONCRETE_POWDER, Items.BLACK_CONCRETE);

        registerOxidation(Items.COPPER_BLOCK, Items.EXPOSED_COPPER);
        registerOxidation(Items.EXPOSED_COPPER, Items.WEATHERED_COPPER);
        registerOxidation(Items.WEATHERED_COPPER, Items.OXIDIZED_COPPER);

        registerOxidation(Items.CUT_COPPER, Items.EXPOSED_CUT_COPPER);
        registerOxidation(Items.EXPOSED_CUT_COPPER, Items.WEATHERED_CUT_COPPER);
        registerOxidation(Items.WEATHERED_CUT_COPPER, Items.OXIDIZED_CUT_COPPER);

        registerOxidation(Items.CUT_COPPER_STAIRS, Items.EXPOSED_CUT_COPPER_STAIRS);
        registerOxidation(Items.EXPOSED_CUT_COPPER_STAIRS, Items.WEATHERED_CUT_COPPER_STAIRS);
        registerOxidation(Items.WEATHERED_CUT_COPPER_STAIRS, Items.OXIDIZED_CUT_COPPER_STAIRS);

        registerOxidation(Items.CUT_COPPER_SLAB, Items.EXPOSED_CUT_COPPER_SLAB);
        registerOxidation(Items.EXPOSED_CUT_COPPER_SLAB, Items.WEATHERED_CUT_COPPER_SLAB);
        registerOxidation(Items.WEATHERED_CUT_COPPER_SLAB, Items.OXIDIZED_CUT_COPPER_SLAB);

        registerOxidation(Items.COPPER_DOOR, Items.EXPOSED_COPPER_DOOR);
        registerOxidation(Items.EXPOSED_COPPER_DOOR, Items.WEATHERED_COPPER_DOOR);
        registerOxidation(Items.WEATHERED_COPPER_DOOR, Items.OXIDIZED_COPPER_DOOR);

        registerOxidation(Items.COPPER_TRAPDOOR, Items.EXPOSED_COPPER_TRAPDOOR);
        registerOxidation(Items.EXPOSED_COPPER_TRAPDOOR, Items.WEATHERED_COPPER_TRAPDOOR);
        registerOxidation(Items.WEATHERED_COPPER_TRAPDOOR, Items.OXIDIZED_COPPER_TRAPDOOR);

        registerOxidation(Items.COPPER_GRATE, Items.EXPOSED_COPPER_GRATE);
        registerOxidation(Items.EXPOSED_COPPER_GRATE, Items.WEATHERED_COPPER_GRATE);
        registerOxidation(Items.WEATHERED_COPPER_GRATE, Items.OXIDIZED_COPPER_GRATE);

        registerOxidation(Items.COPPER_BULB, Items.EXPOSED_COPPER_BULB);
        registerOxidation(Items.EXPOSED_COPPER_BULB, Items.WEATHERED_COPPER_BULB);
        registerOxidation(Items.WEATHERED_COPPER_BULB, Items.OXIDIZED_COPPER_BULB);

        registerTransformation(Items.CHIPPED_ANVIL, Items.ANVIL, null, 0, "Anvil usage damage");
        registerTransformation(Items.DAMAGED_ANVIL, Items.CHIPPED_ANVIL, null, 0, "Anvil usage damage");

        var mossIng = new Reference2DoubleOpenHashMap<Item>();
        mossIng.put(Items.MOSS_BLOCK, 1.0);
        mossIng.put(Items.BONE_MEAL, 2.0);
        registerComplexSource(Items.ROOTED_DIRT, mossIng,
                2.0, BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION, "Bonemeal on moss");

        var azaleaIng = new Reference2DoubleOpenHashMap<Item>();
        azaleaIng.put(Items.MOSS_BLOCK, 1.0);
        azaleaIng.put(Items.BONE_MEAL, 1.0);
        registerComplexSource(Items.AZALEA_LEAVES, azaleaIng,
                1.0, BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION, "Bonemeal on moss");

        var flowerAzaleaIng = new Reference2DoubleOpenHashMap<Item>();
        flowerAzaleaIng.put(Items.MOSS_BLOCK, 1.0);
        flowerAzaleaIng.put(Items.BONE_MEAL, 1.0);
        registerComplexSource(Items.FLOWERING_AZALEA_LEAVES, flowerAzaleaIng,
                1.0, BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION, "Bonemeal on moss");
    }

    private void registerBucketInteractions() {
        registerComplexSource(Items.LAVA_BUCKET, Reference2DoubleMaps.singleton(Items.BUCKET, 1.0),
                10.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION, "Collecting lava");

        registerComplexSource(Items.PUFFERFISH_BUCKET, Reference2DoubleMaps.singleton(Items.BUCKET, 1.0),
                15.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION, "Catching pufferfish");
        registerComplexSource(Items.SALMON_BUCKET, Reference2DoubleMaps.singleton(Items.BUCKET, 1.0),
                15.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION, "Catching salmon");
        registerComplexSource(Items.COD_BUCKET, Reference2DoubleMaps.singleton(Items.BUCKET, 1.0),
                15.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION, "Catching cod");
        registerComplexSource(Items.TROPICAL_FISH_BUCKET, Reference2DoubleMaps.singleton(Items.BUCKET, 1.0),
                20.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION, "Catching tropical fish");
        registerComplexSource(Items.AXOLOTL_BUCKET, Reference2DoubleMaps.singleton(Items.BUCKET, 1.0),
                25.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION, "Catching axolotl");
        registerComplexSource(Items.TADPOLE_BUCKET, Reference2DoubleMaps.singleton(Items.BUCKET, 1.0),
                20.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION, "Catching tadpole");
    }

    private void registerSpecialLoot() {
        registerComplexSource(Items.ELYTRA, Reference2DoubleMaps.emptyMap(),
                1000.0, BaseResourceData.ResourceSourceType.SPECIAL_LOOT,
                "End Ship treasure (extremely rare)");

        registerComplexSource(Items.FLOW_POTTERY_SHERD, Reference2DoubleMaps.emptyMap(),
                150.0, BaseResourceData.ResourceSourceType.ARCHAEOLOGY,
                "Trial Chambers archaeology");
        registerComplexSource(Items.GUSTER_POTTERY_SHERD, Reference2DoubleMaps.emptyMap(),
                150.0, BaseResourceData.ResourceSourceType.ARCHAEOLOGY,
                "Trial Chambers archaeology");
        registerComplexSource(Items.SCRAPE_POTTERY_SHERD, Reference2DoubleMaps.emptyMap(),
                150.0, BaseResourceData.ResourceSourceType.ARCHAEOLOGY,
                "Trial Chambers archaeology");

        registerComplexSource(Items.OMINOUS_TRIAL_KEY, Reference2DoubleMaps.singleton(Items.TRIAL_KEY, 1.0),
                200.0, BaseResourceData.ResourceSourceType.SPECIAL_LOOT,
                "Ominous Vault drop");
    }

    private void registerSpecialCrafts() {
        var bundleIng = new Reference2DoubleOpenHashMap<Item>();
        bundleIng.put(Items.STRING, 2.0);
        bundleIng.put(Items.RABBIT_HIDE, 1.0);
        registerComplexSource(Items.BUNDLE, bundleIng,
                1.0, BaseResourceData.ResourceSourceType.CRAFTING, "Bundle crafting");

        registerComplexSource(Items.WRITTEN_BOOK, Reference2DoubleMaps.singleton(Items.WRITABLE_BOOK, 1.0),
                1.0, BaseResourceData.ResourceSourceType.CRAFTING, "Signing a book");

        var starIng = new Reference2DoubleOpenHashMap<Item>();
        starIng.put(Items.GUNPOWDER, 1.0);
        starIng.put(Items.YELLOW_DYE, 1.0);
        registerComplexSource(Items.FIREWORK_STAR, starIng,
                1.0, BaseResourceData.ResourceSourceType.CRAFTING, "Basic firework star");
    }

    private void registerDragonItems() {
        registerComplexSource(Items.DRAGON_BREATH, Reference2DoubleMaps.singleton(Items.GLASS_BOTTLE, 1.0),
                100.0, BaseResourceData.ResourceSourceType.SPECIAL_ACTION,
                "Collecting dragon breath");

        var lingerIng = new Reference2DoubleOpenHashMap<Item>();
        lingerIng.put(Items.DRAGON_BREATH, 1.0);
        lingerIng.put(Items.SPLASH_POTION, 1.0);
        registerComplexSource(Items.LINGERING_POTION, lingerIng,
                1.0, BaseResourceData.ResourceSourceType.CRAFTING, "Lingering potion brewing");
    }

    private void registerDeadCoral(Item alive, Item dead) {
        registerTransformation(dead, alive, null, 1.0, "Coral drying out");
    }

    private void registerStripping(Item normal, Item stripped) {
        registerTransformation(stripped, normal, Reference2DoubleMaps.singleton(Items.WOODEN_AXE, 0.01), 1.0, "Stripping with axe");
    }

    private void registerConcrete(Item powder, Item concrete) {
        registerTransformation(concrete, powder, null, 5.0, "Solidifying in water");
    }

    private void registerOxidation(Item from, Item to) {
        double timeCost = ComplexityConfig.TIME_COST_MULTIPLIER.get() * 40000.0;
        registerTransformation(to, from, null, timeCost, "Natural oxidation");
    }

    private record SourceRule(
            Reference2DoubleMap<Item> ingredients,
            double baseCost,
            BaseResourceData.ResourceSourceType type,
            String description,
            String modId
    ) {
    }
}