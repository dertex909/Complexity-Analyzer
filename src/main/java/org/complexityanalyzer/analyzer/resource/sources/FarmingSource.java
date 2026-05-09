package org.complexityanalyzer.analyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.providers.PlantSimulator;
import org.complexityanalyzer.config.ComplexityConfig;
import org.jetbrains.annotations.Nullable;

public class FarmingSource implements IResourceSource, IMultiSourceProvider {

    private static final double RENEWABLE_DISCOUNT = 0.3;
    private static final double BASE_TICKS_PER_STAGE = 1200;

    private final Reference2ObjectMap<Item, ObjectList<FarmingData>> productionMap = new Reference2ObjectOpenHashMap<>();
    private final PlantSimulator simulator = new PlantSimulator();

    private record FarmingData(Item plantItem, Block plantBlock, double avgGrowthTicks, double outputAmount,
                               String dropsSummary, String details) {
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

        ObjectList<Block> candidates = new ObjectArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) if (!simulator.isNotPlant(block)) candidates.add(block);

        int plantCount = candidates.size();
        ComplexityAnalyzer.LOGGER.info("[FarmingSource] Found {} plant candidates to analyze", plantCount);

        Object2ObjectMap<Block, PlantSimulator.SimulationResult> results = serverLevel.getServer().submit(() ->
                simulator.simulateAll(candidates, serverLevel)).join();
        if (results == null) return;

        for (var entry : results.object2ObjectEntrySet()) {
            Block block = entry.getKey();
            PlantSimulator.SimulationResult simResult = entry.getValue();

            Item plantItem = null;
            for (Item drop : simResult.drops().keySet()) {
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
                Item drop = dropEntry.getKey();
                double outputAmount = dropEntry.getDoubleValue();
                if (outputAmount > 0.0) {
                    String details = String.format("Plant %s (becomes %s) → harvest for %s",
                            itemId(plantItem), blockId(block), itemId(drop));
                    FarmingData data = new FarmingData(plantItem, block, growthTicks, outputAmount, dropsSummary, details);
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
                double cost = calculateCost(source.avgGrowthTicks(), source.outputAmount());
                int stages = (int) (source.avgGrowthTicks() / BASE_TICKS_PER_STAGE);
                ComplexityAnalyzer.LOGGER.debug("[FARMING] {} -> from {} as {} | stages={} yield={} cost={} drops={} | type=FARMING",
                        itemId(product), itemId(source.plantItem()), blockId(source.plantBlock()), stages, source.outputAmount(), cost,
                        source.dropsSummary());
            }
        }
    }

    private boolean itemPlacesBlock(Item item, Block targetBlock) {
        if (item instanceof BlockItem blockItem) return blockItem.getBlock() == targetBlock;
        return false;
    }

    @Nullable
    private Item findPlantItem(Block targetBlock) {
        Item direct = targetBlock.asItem();
        if (direct != Items.AIR) return direct;

        for (Item candidate : BuiltInRegistries.ITEM) {
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
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private String formatDrops(Reference2DoubleMap<Item> drops) {
        if (drops.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var entry : drops.reference2DoubleEntrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(BuiltInRegistries.ITEM.getKey(entry.getKey())).append(" x").append(entry.getDoubleValue());
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
        for (int i = 1; i < lst.size(); i++)
            if (calculateCost(lst.get(i).avgGrowthTicks(), lst.get(i).outputAmount())
                    < calculateCost(best.avgGrowthTicks(), best.outputAmount()))
                best = lst.get(i);

        var sourceItems = new Reference2DoubleOpenHashMap<Item>();
        sourceItems.put(best.plantItem(), 1.0 / best.outputAmount());

        return new BaseResourceData.Builder(item, this)
                .sourceType(getSourceType())
                .baseFactor(calculateCost(best.avgGrowthTicks(), best.outputAmount()))
                .sourceItems(sourceItems)
                .sourceSpecifier(BuiltInRegistries.ITEM.getKey(best.plantItem()).getPath())
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
                    .sourceSpecifier(BuiltInRegistries.ITEM.getKey(data.plantItem()).getPath())
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
        return BaseResourceData.ResourceSourceType.FARMING;
    }
}