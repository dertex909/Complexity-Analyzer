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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.ResourceCache;
import org.complexityanalyzer.cache.util.Fingerprints;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockBreakAsRecipeSource implements IResourceSource, IMultiSourceProvider {

    private static final int SAMPLE_COUNT = 50;
    private static final double TIME_COST_MULTIPLIER = 1.0;
    private static final int LOGIC_VERSION = 1;
    private static final int EMPTY_HAND_HASH = "empty_hand".hashCode();

    private static final Comparator<BaseResourceData> PATH_COMPARATOR = (a, b) -> {
        int typeCompare = Double.compare(a.getSourceType().getBaseMultiplier(), b.getSourceType().getBaseMultiplier());
        if (typeCompare != 0) return typeCompare;

        int factorCompare = Double.compare(a.getBaseFactor(), b.getBaseFactor());
        if (factorCompare != 0) return factorCompare;

        int sizeCompare = Integer.compare(a.getSourceItems().size(), b.getSourceItems().size());
        if (sizeCompare != 0) return sizeCompare;

        double sumA = sumValues(a.getSourceItems());
        double sumB = sumValues(b.getSourceItems());
        int sumCompare = Double.compare(sumA, sumB);
        if (sumCompare != 0) return sumCompare;

        return a.getDetails().compareTo(b.getDetails());
    };

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

        if (this.geoDatabase == null || !this.geoDatabase.isLoaded()) {
            ComplexityAnalyzer.LOGGER.info("[{}] No geo-data loaded yet — skipping block-drop analysis (will run after a geo-scan).", getName());
            return;
        }

        boolean cacheEnabled = ComplexityConfig.ENABLE_CACHE.get();
        var cacheFile = cacheEnabled ? ResourceCache.BLOCK_BREAK.file(serverLevel.getServer()) : null;
        long[] fingerprint = null;
        if (cacheFile != null) {
            fingerprint = computeFingerprint(serverLevel.getSeed());
            int restored = ResourceCache.BLOCK_BREAK.load(cacheFile, fingerprint, (buf, item) ->
                    ResourceCache.readResourceData(buf, item, this), allPaths);
            if (restored >= 0) {
                ComplexityAnalyzer.LOGGER.info("[{}] Loaded {} block-drop paths for {} items from cache (block scan skipped).",
                        getName(), restored, allPaths.size());
                return;
            }
        }

        ComplexityAnalyzer.LOGGER.info("[{}] Initializing... Analyzing all block drop recipes concurrently.", getName());
        long startTime = System.currentTimeMillis();
        var pathsFound = new AtomicInteger(0);
        var blocksSkipped = new AtomicInteger(0);

        var server = serverLevel.getServer();
        var toolsToTest = createTestTools(serverLevel);
        var computePool = ThreadPoolManager.getInstance().getComputePool();

        var localPaths = new ConcurrentHashMap<Item, ConcurrentLinkedQueue<BaseResourceData>>();
        var futures = new ObjectArrayList<CompletableFuture<Void>>();

        var blocksToProcess = new ObjectArrayList<Block>();
        for (var blockToMine : GameRegistryManager.getAllBlocks()) {
            if (blockToMine == Blocks.AIR || blockToMine == Blocks.CAVE_AIR || blockToMine == Blocks.VOID_AIR) {
                continue;
            }

            float hardness = blockToMine.defaultDestroyTime();
            if (hardness < 0) {
                blocksSkipped.incrementAndGet();
                continue;
            }
            blocksToProcess.add(blockToMine);
        }

        int threadCount = Math.max(1, ThreadPoolManager.getInstance().getParallelism());
        int numBatches = threadCount * 4;
        int totalBlocks = blocksToProcess.size();
        int batchSize = (int) Math.ceil((double) totalBlocks / numBatches);
        if (batchSize <= 0) batchSize = 1;

        for (int i = 0; i < totalBlocks; i += batchSize) {
            final int start = i;
            final int end = Math.min(totalBlocks, i + batchSize);
            futures.add(CompletableFuture.runAsync(() -> {
                for (int j = start; j < end; j++) {
                    var blockToMine = blocksToProcess.get(j);
                    float hardness = blockToMine.defaultDestroyTime();
                    try {
                        var defaultState = blockToMine.defaultBlockState();
                        var candidates = new ObjectArrayList<ItemStack>();
                        boolean requiresTool = defaultState.requiresCorrectToolForDrops();

                        if (!requiresTool) candidates.add(ItemStack.EMPTY);

                        for (var tool : toolsToTest) {
                            if (tool.isEmpty()) continue;
                            boolean isCorrect = tool.isCorrectToolForDrops(defaultState);
                            if (requiresTool && !isCorrect) continue;
                            float speed = tool.getDestroySpeed(defaultState);
                            if (!requiresTool && speed <= 1.0f) continue;

                            candidates.add(tool);
                        }

                        var lootTable = server.reloadableRegistries().getLootTable(blockToMine.getLootTable());
                        if (lootTable == LootTable.EMPTY) continue;
                        RarityInfo rInfo = null;

                        for (var toolStack : candidates) {
                            try {
                                long stableSeed = generateStableSeed(serverLevel.getSeed(), blockToMine, toolStack);
                                var averageDrop = getStableDrop(lootTable, serverLevel, defaultState, toolStack, stableSeed);
                                if (averageDrop.isEmpty()) continue;

                                float speed = toolStack.getDestroySpeed(defaultState);
                                boolean isCorrect = toolStack.isCorrectToolForDrops(defaultState);

                                double timeTaken = (hardness * (isCorrect ? 1.5 : 5.0)) / speed;
                                if (rInfo == null) rInfo = calculateRarityFactor(blockToMine);
                                double rarityFactor = rInfo.factor();

                                double enchantCost = 0;
                                var enchants = toolStack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                                if (!enchants.isEmpty()) enchantCost = enchants.size() * 20.0;

                                double miningBaseFactor = rarityFactor + (timeTaken * TIME_COST_MULTIPLIER) + enchantCost;
                                if (miningBaseFactor >= Double.POSITIVE_INFINITY) continue;

                                for (var entry : Reference2DoubleMaps.fastIterable(averageDrop)) {
                                    var droppedItem = entry.getKey();
                                    var itemsPerAction = entry.getDoubleValue();
                                    if (itemsPerAction <= 0) continue;

                                    var sourceItems = calculateSourceItems(toolStack, itemsPerAction);

                                    var details = new StringBuilder();
                                    details.append("Mined from ").append(blockToMine.getName().getString());
                                    if (!rInfo.location().isEmpty()) details.append(" ").append(rInfo.location());
                                    if (toolStack.isEmpty()) {
                                        details.append(" with Hand");
                                    } else {
                                        details.append(" with ").append(toolStack.getHoverName().getString());
                                        var enchs = toolStack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                                        if (!enchs.isEmpty()) details.append(" (Enchanted)");
                                    }
                                    details.append(" (avg: ").append(formatAverage(itemsPerAction)).append(")");

                                    double actionCost = (timeTaken * TIME_COST_MULTIPLIER) + enchantCost;
                                    if (rarityFactor < Double.POSITIVE_INFINITY) {
                                        details.append(" | Cost: Rarity ≈ ").append(formatAverage(rarityFactor))
                                                .append(", Action ≈ ").append(formatAverage(actionCost));
                                    }

                                    boolean isSelfDrop = droppedItem == blockToMine.asItem();

                                    var data = new BaseResourceData.Builder(droppedItem, this)
                                            .sourceType(getSourceType())
                                            .sourceSpecifier(blockToMine.getName().getString())
                                            .details(details.toString())
                                            .baseFactor(miningBaseFactor)
                                            .sourceItems(isSelfDrop ? Reference2DoubleMaps.emptyMap() : sourceItems)
                                            .build();

                                    localPaths.computeIfAbsent(droppedItem, k2 -> new ConcurrentLinkedQueue<>()).add(data);
                                    pathsFound.incrementAndGet();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, computePool));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (var entry : localPaths.entrySet()) {
            this.allPaths.put(entry.getKey(), new ObjectArrayList<>(entry.getValue()));
        }

        for (var paths : allPaths.values()) paths.sort(PATH_COMPARATOR);

        ComplexityAnalyzer.LOGGER.info("[{}] Initialization complete in {}ms. Found {} block drop paths for {} unique items. Skipped {} indestructible blocks.",
                getName(), (System.currentTimeMillis() - startTime), pathsFound, allPaths.size(), blocksSkipped);
        if (cacheFile != null) ResourceCache.BLOCK_BREAK.save(cacheFile, fingerprint,
                ResourceCache::writeResourceData, allPaths);
    }

    private static double sumValues(Reference2DoubleMap<Item> map) {
        if (map.isEmpty()) return 0;
        double sum = 0;
        var it = map.values().iterator();
        while (it.hasNext()) sum += it.nextDouble();
        return sum;
    }

    private long[] computeFingerprint(long worldSeed) {
        long config = Fingerprints.FNV_OFFSET;
        config = Fingerprints.fnvLong(config, LOGIC_VERSION);
        config = Fingerprints.fnvLong(config, SAMPLE_COUNT);
        config = Fingerprints.fnvLong(config, Double.doubleToLongBits(TIME_COST_MULTIPLIER));
        return new long[]{
                Fingerprints.hashAllBlocks(),
                Fingerprints.hashMods(),
                Fingerprints.hashGeo(geoDatabase),
                worldSeed,
                config
        };
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

        for (var base : bases) {
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
        var totalCounts = new Reference2LongOpenHashMap<Item>();
        Reference2LongMap<Item> firstSample = null;

        var spawnPos = level.getSharedSpawnPos();
        var originVec = new Vec3(spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5);

        var params = new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, blockState)
                .withParameter(LootContextParams.TOOL, tool)
                .withParameter(LootContextParams.ORIGIN, originVec)
                .create(LootContextParamSets.BLOCK);

        var drops = new ObjectArrayList<ItemStack>();

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            var deterministicRandom = RandomSource.create(baseSeed + i);
            drops.clear();

            var context = new LootContext.Builder(params).withOptionalRandomSource(deterministicRandom).create(Optional.empty());

            lootTable.getRandomItems(context, drops::add);

            var currentSample = new Reference2LongOpenHashMap<Item>();
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

            for (var entry : Reference2LongMaps.fastIterable(currentSample)) {
                totalCounts.put(entry.getKey(), totalCounts.getLong(entry.getKey()) + entry.getLongValue());
            }
        }

        var averages = new Reference2DoubleOpenHashMap<Item>();
        for (var entry : Reference2LongMaps.fastIterable(totalCounts)) {
            averages.put(entry.getKey(), (double) entry.getLongValue() / SAMPLE_COUNT);
        }

        return averages;
    }

    private boolean isSampleConsistent(Reference2LongMap<Item> a, Reference2LongMap<Item> b) {
        if (a.size() != b.size()) return false;
        for (var entry : Reference2LongMaps.fastIterable(a)) {
            if (b.getLong(entry.getKey()) != entry.getLongValue()) return false;
        }
        return true;
    }

    private Reference2DoubleMap<Item> scaleAverages(Reference2LongMap<Item> sample) {
        var averages = new Reference2DoubleOpenHashMap<Item>();
        for (var entry : Reference2LongMaps.fastIterable(sample)) {
            averages.put(entry.getKey(), (double) entry.getLongValue());
        }
        return averages;
    }

    private Reference2DoubleMap<Item> calculateSourceItems(ItemStack toolStack, double itemsPerAction) {
        var sourceItems = new Reference2DoubleOpenHashMap<Item>();
        if (itemsPerAction <= 0) return sourceItems;

        double invYield = 1.0 / itemsPerAction;
        var toolItem = toolStack.getItem();

        if (toolItem != Items.AIR) {
            double durability = toolStack.getMaxDamage();
            if (durability > 0) {
                double invYieldDurability = (1.0 / durability) * invYield;
                sourceItems.put(toolItem, invYieldDurability);
            }
        }

        return sourceItems;
    }

    private RarityInfo calculateRarityFactor(Block block) {
        boolean isGeoLoaded = this.geoDatabase != null && this.geoDatabase.isLoaded();
        if (isGeoLoaded) {
            double bestRarity = Double.POSITIVE_INFINITY;
            String bestLocation = "";
            for (var dimEntry : Object2ObjectMaps.fastIterable(this.geoDatabase.getAllDimensionData())) {
                for (var biomeEntry : Object2ObjectMaps.fastIterable(dimEntry.getValue())) {
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
        var blockId = GameRegistryManager.getBlockId(block);
        if (blockId != null) seed = seed * 31L + blockId.hashCode();

        if (!tool.isEmpty()) {
            var itemId = GameRegistryManager.getItemId(tool.getItem());
            if (itemId != null) seed = seed * 31L + itemId.hashCode();
            var enchantments = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (!enchantments.isEmpty()) seed = seed * 31L + enchantments.hashCode();
        } else {
            seed = seed * 31L + EMPTY_HAND_HASH;
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

    private record RarityInfo(double factor, String location) {
    }
}