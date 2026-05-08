/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
 */

package org.complexityanalyzer.analyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.providers.PlantSimulator;
import org.complexityanalyzer.config.ComplexityConfig;
import org.jetbrains.annotations.Nullable;

public class FarmingSource implements IResourceSource, IMultiSourceProvider {

    private static final double RENEWABLE_DISCOUNT = 0.3;
    private static final double TIME_MULTIPLIER = 0.01;
    private static final double BASE_TICKS_PER_STAGE = 1200;

    private final Reference2ObjectMap<Item, ObjectList<FarmingData>> productionMap = new Reference2ObjectOpenHashMap<>();
    private final PlantSimulator simulator = new PlantSimulator();

    private record FarmingData(Item plantItem, Block plantBlock, double avgGrowthTicks, String details) {
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

        int plantCount = 0;
        int processed = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (simulator.isNotPlant(block)) continue;
            plantCount++;
        }
        ComplexityAnalyzer.LOGGER.info("[FarmingSource] Found {} plant candidates to analyze", plantCount);

        for (Block block : BuiltInRegistries.BLOCK) {
            if (simulator.isNotPlant(block)) continue;

            Item plantItem = findPlantItem(block);
            if (plantItem == null) continue;

            processed++;
            long plantStart = System.currentTimeMillis();
            ComplexityAnalyzer.LOGGER.info("[FarmingSource] [{}/{}] Processing: {}",
                    processed, plantCount, BuiltInRegistries.BLOCK.getKey(block));
            PlantSimulator.SimulationResult simResult = simulator.simulate(block, serverLevel);
            long plantElapsed = System.currentTimeMillis() - plantStart;
            if (plantElapsed > 500) {
                ComplexityAnalyzer.LOGGER.warn("[FarmingSource] SLOW: {} took {}ms",
                        BuiltInRegistries.BLOCK.getKey(block), plantElapsed);
            }
            ObjectSet<Item> drops = new ObjectOpenHashSet<>(simResult.drops());

            ObjectSet<Item> lootDrops = simulateMatureLoot(block, serverLevel);
            drops.addAll(lootDrops);
            if (drops.isEmpty()) continue;

            int stages = Math.max(simResult.growthStages(), 1);
            double growthTicks = stages * BASE_TICKS_PER_STAGE;
            String details = String.format("Grown from %s (%d stages)", BuiltInRegistries.BLOCK.getKey(block).getPath(), stages);

            FarmingData data = new FarmingData(plantItem, block, growthTicks, details);

            for (Item drop : drops) {
                if (!isReproductive(drop, block)) {
                    productionMap.computeIfAbsent(drop, k -> new ObjectArrayList<>()).add(data);
                    found++;
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("[FarmingSource] Initialized in {}ms. Found {} products.", System.currentTimeMillis() - startTime, found);

        for (var entry : productionMap.entrySet()) {
            Item product = entry.getKey();
            ObjectList<FarmingData> sources = entry.getValue();
            for (FarmingData source : sources) {
                double cost = calculateCost(source.avgGrowthTicks());
                int stages = (int) (source.avgGrowthTicks() / BASE_TICKS_PER_STAGE);
                ComplexityAnalyzer.LOGGER.error("[FARMING] {} -> from {} | stages={} cost={} | type=RENEWABLE",
                        BuiltInRegistries.ITEM.getKey(product),
                        BuiltInRegistries.BLOCK.getKey(source.plantBlock()), stages, cost);
            }
        }
    }

    private ObjectSet<Item> simulateMatureLoot(Block block, ServerLevel level) {
        ObjectSet<Item> drops = new ObjectOpenHashSet<>();
        try {
            BlockState matureState = block.defaultBlockState();
            for (var prop : matureState.getProperties()) {
                if (prop instanceof net.minecraft.world.level.block.state.properties.IntegerProperty intProp) {
                    int max = intProp.getPossibleValues().stream().max(Integer::compare).orElse(0);
                    matureState = matureState.setValue(intProp, max);
                }
            }

            var builder = new LootParams.Builder(level).withParameter(LootContextParams.BLOCK_STATE, matureState)
                    .withParameter(LootContextParams.TOOL, ItemStack.EMPTY).withParameter(LootContextParams.ORIGIN, Vec3.ZERO);

            var params = builder.create(LootContextParamSets.BLOCK);
            LootTable table = level.getServer().reloadableRegistries().getLootTable(block.getLootTable());
            if (table == LootTable.EMPTY) return drops;

            for (int i = 0; i < 30; i++) {
                var items = table.getRandomItems(params);
                for (ItemStack stack : items) if (!stack.isEmpty()) drops.add(stack.getItem());
            }
        } catch (Exception ignored) {
        }
        return drops;
    }

    @Nullable
    private Item findPlantItem(Block targetBlock) {
        for (Item candidate : BuiltInRegistries.ITEM) {
            if (candidate instanceof BlockItem blockItem && blockItem.getBlock() == targetBlock) return candidate;
        }
        return null;
    }

    private boolean isReproductive(Item item, Block targetBlock) {
        if (item instanceof BlockItem blockItem) return blockItem.getBlock() == targetBlock;
        return false;
    }

    private double calculateCost(double growthTicks) {
        double timeCost = growthTicks * TIME_MULTIPLIER * ComplexityConfig.TIME_COST_MULTIPLIER.get();
        return (timeCost + ComplexityConfig.BASE_ACTION_COST.get()) * RENEWABLE_DISCOUNT;
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
        for (int i = 1; i < lst.size(); i++) if (lst.get(i).avgGrowthTicks() < best.avgGrowthTicks()) best = lst.get(i);

        var sourceItems = new Reference2DoubleOpenHashMap<Item>();
        sourceItems.put(best.plantItem(), 1.0);

        return new BaseResourceData.Builder(item, this)
                .sourceType(getSourceType())
                .baseFactor(calculateCost(best.avgGrowthTicks()))
                .sourceItems(sourceItems)
                .sourceSpecifier(BuiltInRegistries.BLOCK.getKey(best.plantBlock()).getPath())
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
            sourceItems.put(data.plantItem(), 1.0);

            results.add(new BaseResourceData.Builder(item, this)
                    .sourceType(getSourceType())
                    .baseFactor(calculateCost(data.avgGrowthTicks()))
                    .sourceItems(sourceItems)
                    .sourceSpecifier(BuiltInRegistries.BLOCK.getKey(data.plantBlock()).getPath())
                    .details(data.details())
                    .build());
        }
        return results;
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
        return BaseResourceData.ResourceSourceType.RENEWABLE;
    }
}
