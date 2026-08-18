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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.util.Fingerprints;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.providers.PlantSimulator;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

import static org.complexityanalyzer.cache.ResourceCache.FARMING;

public class FarmingSource implements IResourceSource, IMultiSourceProvider {

    private static final double RENEWABLE_DISCOUNT = 0.3;
    private static final double BASE_TICKS_PER_STAGE = 1200;
    private static final int LOGIC_VERSION = 1;

    private final Reference2ObjectMap<Item, ObjectList<FarmingData>> productionMap = new Reference2ObjectOpenHashMap<>();
    private final PlantSimulator simulator = new PlantSimulator();

    private static void writeData(FriendlyByteBuf buf, FarmingData data) {
        var plantId = GameRegistryManager.getItemId(data.plantItem());
        var blockIdRl = GameRegistryManager.getBlockId(data.plantBlock());
        buf.writeResourceLocation(plantId != null ? plantId : ResourceLocation.withDefaultNamespace("air"));
        buf.writeResourceLocation(blockIdRl != null ? blockIdRl : ResourceLocation.withDefaultNamespace("air"));
        buf.writeDouble(data.avgGrowthTicks());
        buf.writeDouble(data.outputAmount());
        buf.writeUtf(data.dropsSummary());
        buf.writeUtf(data.details());
    }

    @Nullable
    private static FarmingData readData(FriendlyByteBuf buf, Item dropItem) {
        var plantItem = GameRegistryManager.getItem(buf.readResourceLocation());
        var plantBlock = GameRegistryManager.getBlock(buf.readResourceLocation());
        double growthTicks = buf.readDouble();
        double outputAmount = buf.readDouble();
        String dropsSummary = buf.readUtf();
        String details = buf.readUtf();
        if (plantItem == null || plantItem == Items.AIR || plantBlock == null) return null;
        return new FarmingData(plantItem, plantBlock, growthTicks, outputAmount, dropsSummary, details);
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.warn("[FarmingSource] requires a ServerLevel. Skipping.");
            return;
        }

        productionMap.clear();
        ComplexityAnalyzer.LOGGER.info("[FarmingSource] Initializing...");
        long startTime = System.currentTimeMillis();
        int found = 0;

        var server = serverLevel.getServer();
        boolean cacheEnabled = ComplexityConfig.ENABLE_CACHE.get();
        var cacheFile = cacheEnabled ? FARMING.file(server) : null;
        long[] fingerprint = cacheFile != null ? computeFingerprint(serverLevel.getSeed()) : null;
        if (cacheFile != null) {
            int restored = FARMING.load(cacheFile, fingerprint, FarmingSource::readData, productionMap);
            if (restored >= 0) {
                ComplexityAnalyzer.LOGGER.info("[FarmingSource] Loaded {} products from cache (plant simulation skipped).", restored);
                return;
            }
        }

        var candidates = new ObjectArrayList<Block>();
        for (var block : GameRegistryManager.getAllBlocks()) if (simulator.isPlant(block)) candidates.add(block);

        Object2ObjectMap<Block, PlantSimulator.SimulationResult> results;
        if (server.isSameThread()) {
            results = simulator.simulateAll(candidates, serverLevel);
        } else {
            results = server.submit(() -> simulator.simulateAll(candidates, serverLevel)).join();
        }
        if (results == null) return;

        for (var entry : results.object2ObjectEntrySet()) {
            var block = entry.getKey();
            var simResult = entry.getValue();

            Item plantItem = null;
            for (var drop : simResult.drops().keySet()) {
                if (itemPlacesBlock(drop, block)) {
                    plantItem = drop;
                    break;
                }
            }

            if (plantItem == null) plantItem = findPlantItem(block);
            if (plantItem == null) continue;

            int stages = simResult.growthStages();
            double growthTicks = stages * BASE_TICKS_PER_STAGE;
            String dropsSummary = formatDrops(simResult.drops());

            for (var dropEntry : simResult.drops().reference2DoubleEntrySet()) {
                var drop = dropEntry.getKey();
                double outputAmount = dropEntry.getDoubleValue();
                if (outputAmount > 0.0) {
                    String plantItemId = itemId(plantItem);
                    String blockIdStr = blockId(block);
                    String details;
                    if (plantItemId.equals(blockIdStr)) {
                        details = String.format("Plant %s → harvest for %s", plantItemId, itemId(drop));
                    } else {
                        details = String.format("Plant %s (becomes %s) → harvest for %s", plantItemId, blockIdStr, itemId(drop));
                    }

                    FarmingData data = new FarmingData(plantItem, block, growthTicks, outputAmount, dropsSummary, details);
                    productionMap.computeIfAbsent(drop, k -> new ObjectArrayList<>()).add(data);
                    found++;
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("[FarmingSource] Initialized in {}ms. Found {} products.", System.currentTimeMillis() - startTime, found);
        if (cacheFile != null) FARMING.save(cacheFile, fingerprint, FarmingSource::writeData, productionMap);
    }

    private long[] computeFingerprint(long worldSeed) {
        return new long[]{
                Fingerprints.fnvLong(Fingerprints.FNV_OFFSET, LOGIC_VERSION),
                Fingerprints.hashAllBlocks(),
                Fingerprints.hashAllItems(),
                Fingerprints.hashMods(),
                worldSeed
        };
    }

    private boolean itemPlacesBlock(Item item, Block targetBlock) {
        return item instanceof BlockItem blockItem && blockItem.getBlock() == targetBlock;
    }

    @Nullable
    private Item findPlantItem(Block targetBlock) {
        var direct = targetBlock.asItem();
        if (direct != Items.AIR) return direct;

        for (var candidate : GameRegistryManager.getAllItems()) {
            if (candidate instanceof BlockItem blockItem && blockItem.getBlock() == targetBlock) return candidate;
        }
        return null;
    }

    private double calculateCost(double growthTicks) {
        double timeCost = growthTicks * ComplexityConfig.FARMING_TIME_COST_MULTIPLIER.get();
        return (timeCost + ComplexityConfig.BASE_ACTION_COST.get()) * RENEWABLE_DISCOUNT;
    }

    private double calculateCost(double growthTicks, double outputAmount) {
        return calculateCost(growthTicks) / outputAmount;
    }

    private String itemId(Item item) {
        var id = GameRegistryManager.getItemId(item);
        return id != null ? id.toString() : "minecraft:air";
    }

    private String blockId(Block block) {
        var id = GameRegistryManager.getBlockId(block);
        return id != null ? id.toString() : "minecraft:air";
    }

    private String formatDrops(Reference2DoubleMap<Item> drops) {
        if (drops.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        boolean first = true;
        var sorted = new ObjectArrayList<>(drops.reference2DoubleEntrySet());
        sorted.sort(Comparator.comparing(e -> GameRegistryManager.getItemId(e.getKey()).toString()));
        for (var entry : sorted) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(GameRegistryManager.getItemId(entry.getKey())).append(" x").append(entry.getDoubleValue());
        }
        return sb.append(']').toString();
    }

    @Override
    public boolean canProvide(Item item) {
        return productionMap.containsKey(item);
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        var lst = productionMap.get(item);
        if (lst == null || lst.isEmpty()) return null;

        var best = lst.getFirst();
        double bestCost = calculateCost(best.avgGrowthTicks(), best.outputAmount());
        for (int i = 1; i < lst.size(); i++) {
            var current = lst.get(i);
            double currentCost = calculateCost(current.avgGrowthTicks(), current.outputAmount());
            if (currentCost < bestCost) {
                best = current;
                bestCost = currentCost;
            }
        }

        var sourceItems = new Reference2DoubleOpenHashMap<Item>();
        sourceItems.put(best.plantItem(), 1.0 / best.outputAmount());

        return new BaseResourceData.Builder(item, this)
                .sourceType(getSourceType())
                .baseFactor(bestCost)
                .sourceItems(sourceItems)
                .sourceSpecifier(best.plantItem().getDescriptionId())
                .details(best.details())
                .build();
    }

    @Override
    public ObjectList<BaseResourceData> findAllSources(Item item) {
        var list = productionMap.get(item);
        if (list == null || list.isEmpty()) return ObjectLists.emptyList();

        var results = new ObjectArrayList<BaseResourceData>();
        for (var data : list) {
            var sourceItems = new Reference2DoubleOpenHashMap<Item>();
            sourceItems.put(data.plantItem(), 1.0 / data.outputAmount());

            results.add(new BaseResourceData.Builder(item, this)
                    .sourceType(getSourceType())
                    .baseFactor(calculateCost(data.avgGrowthTicks(), data.outputAmount()))
                    .sourceItems(sourceItems)
                    .sourceSpecifier(data.plantItem().getDescriptionId())
                    .details(data.details())
                    .build());
        }
        return results;
    }

    @Override
    public boolean requiresServerThread() {
        return true;
    }

    @Override
    public int getPriority() {
        return 28;
    }

    @Override
    public String getName() {
        return "FarmingSource";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.FARMING;
    }

    private record FarmingData(Item plantItem, Block plantBlock, double avgGrowthTicks, double outputAmount,
                               String dropsSummary, String details) {
    }
}