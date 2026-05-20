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

package org.complexityanalyzer.analyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.mixin.LootContextAccessor;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class BlockBreakAsRecipeSource implements IResourceSource, IMultiSourceProvider {

    private static final int SAMPLE_COUNT = 50;
    private static final double TIME_COST_MULTIPLIER = 1.0;

    private final Reference2ObjectMap<Item, ObjectList<BaseResourceData>> allPaths = new Reference2ObjectOpenHashMap<>();
    private final GeoDatabase geoDatabase;

    public BlockBreakAsRecipeSource(GeoDatabase geoDatabase) {
        this.geoDatabase = geoDatabase;
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.warn("[{}] requires a ServerLevel. Skipping.", getName());
            return;
        }

        this.allPaths.clear();

        ComplexityAnalyzer.LOGGER.info("[{}] Initializing... Analyzing all block drop recipes.", getName());
        long startTime = System.currentTimeMillis();
        int pathsFound = 0;
        int blocksSkipped = 0;

        var server = serverLevel.getServer();
        var toolsToTest = createTestTools(serverLevel);

        for (Block blockToMine : GameRegistryManager.getAllBlocks()) {
            if (blockToMine == Blocks.AIR || blockToMine == Blocks.CAVE_AIR || blockToMine == Blocks.VOID_AIR) {
                continue;
            }

            float hardness = blockToMine.defaultDestroyTime();
            if (hardness < 0) {
                blocksSkipped++;
                continue;
            }

            BlockState defaultState = blockToMine.defaultBlockState();
            ObjectList<ItemStack> candidates = new ObjectArrayList<>();
            boolean requiresTool = defaultState.requiresCorrectToolForDrops();

            if (!requiresTool) candidates.add(ItemStack.EMPTY);

            for (ItemStack tool : toolsToTest) {
                if (tool.isEmpty()) continue;
                boolean isCorrect = tool.isCorrectToolForDrops(defaultState);
                if (requiresTool && !isCorrect) continue;
                float speed = tool.getDestroySpeed(defaultState);
                if (!requiresTool && speed <= 1.0f) continue;

                candidates.add(tool);
            }

            for (ItemStack toolStack : candidates) {
                try {
                    LootTable lootTable = server.reloadableRegistries().getLootTable(blockToMine.getLootTable());
                    if (lootTable == LootTable.EMPTY) continue;

                    long stableSeed = generateStableSeed(serverLevel.getSeed(), blockToMine, toolStack);
                    var averageDrop = getStableDrop(lootTable, serverLevel, defaultState, toolStack, stableSeed);
                    if (averageDrop.isEmpty()) continue;

                    float speed = toolStack.getDestroySpeed(defaultState);
                    boolean isCorrect = toolStack.isCorrectToolForDrops(defaultState);

                    double timeTaken = (hardness * (isCorrect ? 1.5 : 5.0)) / speed;
                    RarityInfo rarityInfo = calculateRarityFactor(blockToMine);
                    double rarityFactor = rarityInfo.factor();

                    double enchantCost = 0;
                    var enchants = toolStack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                    if (!enchants.isEmpty()) enchantCost = enchants.size() * 20.0;

                    double miningBaseFactor = rarityFactor + (timeTaken * TIME_COST_MULTIPLIER) + enchantCost;
                    if (miningBaseFactor >= Double.POSITIVE_INFINITY) continue;

                    for (var entry : averageDrop.reference2DoubleEntrySet()) {
                        var droppedItem = entry.getKey();
                        var itemsPerAction = entry.getDoubleValue();
                        if (itemsPerAction <= 0) continue;

                        Reference2DoubleMap<Item> sourceItems = calculateSourceItems(toolStack, itemsPerAction);

                        StringBuilder details = new StringBuilder();
                        details.append("Mined from ").append(blockToMine.getName().getString());
                        if (!rarityInfo.location().isEmpty()) details.append(" ").append(rarityInfo.location());
                        if (toolStack.isEmpty()) {
                            details.append(" with Hand");
                        } else {
                            details.append(" with ").append(toolStack.getHoverName().getString());
                            var enchs = toolStack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                            if (!enchs.isEmpty()) details.append(" (Enchanted)");
                        }
                        details.append(String.format(" (avg: %s)", formatAverage(itemsPerAction)));

                        double actionCost = (timeTaken * TIME_COST_MULTIPLIER) + enchantCost;
                        if (rarityFactor < Double.POSITIVE_INFINITY) {
                            details.append(String.format(" | Cost: Rarity ≈ %s, Action ≈ %s", formatAverage(rarityFactor), formatAverage(actionCost)));
                        }

                        boolean isSelfDrop = droppedItem == blockToMine.asItem();

                        BaseResourceData data = new BaseResourceData.Builder(droppedItem, this)
                                .sourceType(getSourceType())
                                .sourceSpecifier(blockToMine.getName().getString())
                                .details(details.toString())
                                .baseFactor(miningBaseFactor)
                                .sourceItems(isSelfDrop ? new Reference2DoubleOpenHashMap<>() : sourceItems)
                                .build();

                        allPaths.computeIfAbsent(droppedItem, k -> new ObjectArrayList<>()).add(data);
                        pathsFound++;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        for (var paths : allPaths.values()) {
            paths.sort((a, b) -> {
                int typeCompare = Double.compare(a.getSourceType().getBaseMultiplier(), b.getSourceType().getBaseMultiplier());
                if (typeCompare != 0) return typeCompare;

                int factorCompare = Double.compare(a.getBaseFactor(), b.getBaseFactor());
                if (factorCompare != 0) return factorCompare;

                int sizeCompare = Integer.compare(a.getSourceItems().size(), b.getSourceItems().size());
                if (sizeCompare != 0) return sizeCompare;

                var sumA = a.getSourceItems().values().doubleStream().sum();
                var sumB = b.getSourceItems().values().doubleStream().sum();
                int sumCompare = Double.compare(sumA, sumB);
                if (sumCompare != 0) return sumCompare;

                return a.getDetails().compareTo(b.getDetails());
            });
        }

        ComplexityAnalyzer.LOGGER.info("[{}] Initialization complete in {}ms. Found {} block drop paths for {} unique items. Skipped {} indestructible blocks.",
                getName(), (System.currentTimeMillis() - startTime), pathsFound, allPaths.size(), blocksSkipped);
    }

    private ObjectList<ItemStack> createTestTools(ServerLevel serverLevel) {
        var tools = new ObjectArrayList<ItemStack>();
        tools.add(ItemStack.EMPTY);
        tools.add(new ItemStack(Items.WOODEN_PICKAXE));
        tools.add(new ItemStack(Items.WOODEN_AXE));
        tools.add(new ItemStack(Items.WOODEN_SHOVEL));
        tools.add(new ItemStack(Items.STONE_PICKAXE));
        tools.add(new ItemStack(Items.STONE_AXE));
        tools.add(new ItemStack(Items.STONE_SHOVEL));
        tools.add(new ItemStack(Items.IRON_PICKAXE));
        tools.add(new ItemStack(Items.IRON_AXE));
        tools.add(new ItemStack(Items.IRON_SHOVEL));
        tools.add(new ItemStack(Items.DIAMOND_PICKAXE));
        tools.add(new ItemStack(Items.DIAMOND_AXE));
        tools.add(new ItemStack(Items.DIAMOND_SHOVEL));
        tools.add(new ItemStack(Items.NETHERITE_PICKAXE));
        tools.add(new ItemStack(Items.NETHERITE_AXE));
        tools.add(new ItemStack(Items.NETHERITE_SHOVEL));
        tools.add(new ItemStack(Items.NETHERITE_HOE));
        tools.add(new ItemStack(Items.SHEARS));

        var registry = serverLevel.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        var silkTouch = registry.getHolder(Enchantments.SILK_TOUCH);
        var fortune = registry.getHolder(Enchantments.FORTUNE);

        Item[] bases = {
                Items.NETHERITE_PICKAXE,
                Items.NETHERITE_SHOVEL,
                Items.NETHERITE_AXE,
                Items.NETHERITE_HOE
        };

        for (Item base : bases) {
            var stTool = new ItemStack(base);
            silkTouch.ifPresent(h -> stTool.enchant(h, 1));
            tools.add(stTool);

            var fortuneTool = new ItemStack(base);
            fortune.ifPresent(h -> fortuneTool.enchant(h, 3));
            tools.add(fortuneTool);
        }

        var silkShears = new ItemStack(Items.SHEARS);
        silkTouch.ifPresent(h -> silkShears.enchant(h, 1));
        tools.add(silkShears);

        return tools;
    }

    private Reference2DoubleMap<Item> getStableDrop(LootTable lootTable, ServerLevel level,
                                                    BlockState blockState, ItemStack tool, long baseSeed) {
        Reference2LongMap<Item> totalCounts = new Reference2LongOpenHashMap<>();
        boolean injectionWorked = false;

        Reference2LongMap<Item> firstSample = null;

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            RandomSource deterministicRandom = RandomSource.create(baseSeed + i);
            ObjectArrayList<ItemStack> drops = new ObjectArrayList<>();

            var params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.BLOCK_STATE, blockState)
                    .withParameter(LootContextParams.TOOL, tool)
                    .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                    .create(LootContextParamSets.BLOCK);

            var context = new LootContext.Builder(params).create(Optional.empty());
            if (i == 0 || injectionWorked) injectionWorked = injectRandomIntoContext(context, deterministicRandom);

            lootTable.getRandomItems(context, drops::add);

            Reference2LongMap<Item> currentSample = new Reference2LongOpenHashMap<>();
            for (var stack : drops) {
                if (!stack.isEmpty()) {
                    currentSample.put(stack.getItem(), currentSample.getLong(stack.getItem()) + stack.getCount());
                }
            }

            if (i == 0) {
                firstSample = currentSample;
            } else if (i == 10) {
                if (isSampleConsistent(firstSample, currentSample)) return scaleAverages(firstSample);
            }

            for (var entry : currentSample.reference2LongEntrySet()) {
                totalCounts.put(entry.getKey(), totalCounts.getLong(entry.getKey()) + entry.getLongValue());
            }
        }

        Reference2DoubleMap<Item> averages = new Reference2DoubleOpenHashMap<>();
        for (var entry : totalCounts.reference2LongEntrySet()) {
            averages.put(entry.getKey(), (double) entry.getLongValue() / SAMPLE_COUNT);
        }

        return averages;
    }

    private boolean isSampleConsistent(Reference2LongMap<Item> a, Reference2LongMap<Item> b) {
        if (a.size() != b.size()) return false;
        for (var entry : a.reference2LongEntrySet()) {
            if (b.getLong(entry.getKey()) != entry.getLongValue()) return false;
        }
        return true;
    }

    private Reference2DoubleMap<Item> scaleAverages(Reference2LongMap<Item> sample) {
        Reference2DoubleMap<Item> averages = new Reference2DoubleOpenHashMap<>();
        for (var entry : sample.reference2LongEntrySet()) averages.put(entry.getKey(), (double) entry.getLongValue());
        return averages;
    }

    private boolean injectRandomIntoContext(LootContext context, RandomSource random) {
        try {
            ((LootContextAccessor) context).setRandom(random);
            return true;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[{}] Failed to inject random into LootContext: {}", getName(), e.getMessage());
            return false;
        }
    }

    private Reference2DoubleMap<Item> calculateSourceItems(ItemStack toolStack, double itemsPerAction) {
        Reference2DoubleMap<Item> sourceItems = new Reference2DoubleOpenHashMap<>();
        if (itemsPerAction <= 0) return sourceItems;

        double invYield = 1.0 / itemsPerAction;
        Item toolItem = toolStack.getItem();

        if (toolItem != Items.AIR) {
            double durability = toolStack.getMaxDamage();
            if (durability > 0) {
                double invDurability = 1.0 / durability;
                double toolUsagePerDrop = invDurability * invYield;
                sourceItems.put(toolItem, toolUsagePerDrop);
            }
        }

        return sourceItems;
    }

    private record RarityInfo(double factor, String location) {
    }

    private RarityInfo calculateRarityFactor(Block block) {
        boolean isGeoLoaded = this.geoDatabase != null && this.geoDatabase.isLoaded();
        if (isGeoLoaded) {
            double bestRarity = Double.POSITIVE_INFINITY;
            String bestLocation = "";
            for (var dimEntry : this.geoDatabase.getAllDimensionData().entrySet()) {
                for (var biomeEntry : dimEntry.getValue().entrySet()) {
                    long total = biomeEntry.getValue().getTotalBlocks();
                    long count = biomeEntry.getValue().getBlockCount(block);
                    if (total > 0 && count > 0) {
                        double factor = Math.pow((double) total / count, 0.85) * 0.15;
                        if (factor < bestRarity) {
                            bestRarity = factor;
                            double chance = (double) count / total * 100.0;
                            String chanceStr = chance < 0.01 ? String.format("%.4f%%", chance) : String.format("%.2f%%", chance);
                            bestLocation = String.format("in %s (%s)", biomeEntry.getKey().getPath(), chanceStr);
                        }
                    }
                }
            }
            if (bestRarity != Double.POSITIVE_INFINITY) return new RarityInfo(bestRarity, bestLocation);
        }

        return new RarityInfo(Double.POSITIVE_INFINITY, "");
    }

    private long generateStableSeed(long worldSeed, Block block, ItemStack tool) {
        long seed = worldSeed;
        seed = seed * 31L + GameRegistryManager.getBlockId(block).toString().hashCode();

        if (!tool.isEmpty()) {
            seed = seed * 31L + GameRegistryManager.getItemId(tool.getItem()).toString().hashCode();
            ItemEnchantments enchantments = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (!enchantments.isEmpty()) seed = seed * 31L + enchantments.hashCode();
        } else {
            seed = seed * 31L + "empty_hand".hashCode();
        }

        return seed;
    }

    private String formatAverage(double value) {
        if (value < 0.0001) return String.format("%.6f", value);
        if (value < 0.01) return String.format("%.4f", value);
        return String.format("%.2f", value);
    }

    @Override
    public boolean canProvide(Item item) {
        return allPaths.containsKey(item);
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        var paths = allPaths.get(item);
        if (paths == null || paths.isEmpty()) return null;
        return paths.getFirst();
    }

    @Override
    public ObjectList<BaseResourceData> findAllSources(Item item) {
        return allPaths.getOrDefault(item, ObjectLists.emptyList());
    }

    @Override
    public int getPriority() {
        return 25;
    }

    @Override
    public String getName() {
        return "Block Break as Recipe";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION;
    }
}