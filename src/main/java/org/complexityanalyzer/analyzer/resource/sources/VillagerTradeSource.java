package org.complexityanalyzer.analyzer.resource.sources;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VillagerTradeSource implements IResourceSource {

    private static final Map<Integer, Double> LEVEL_COST_MAP = Map.of(
            1, 1.2,
            2, 1.5,
            3, 2.0,
            4, 3.0,
            5, 5.0
    );

    private final Map<Item, List<TradeInfo>> tradesByResult = new HashMap<>();

    private net.minecraft.world.entity.npc.Villager cachedFakeVillager = null;


    private record TradeInfo(ItemStack result, ItemStack costA, ItemStack costB, int level) {}

    @Override
    public void initialize(Level level) {
        ComplexityAnalyzer.LOGGER.debug("Initializing VillagerTradeSource by analyzing all trades via reflection...");

        cachedFakeVillager = new net.minecraft.world.entity.npc.Villager(
                net.minecraft.world.entity.EntityType.VILLAGER,
                level
        );

        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> entry : VillagerTrades.TRADES.entrySet()) {
            for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> levelEntry : entry.getValue().int2ObjectEntrySet()) {
                int tradeLevel = levelEntry.getIntKey();
                for (VillagerTrades.ItemListing trade : levelEntry.getValue()) {
                    getTradeInfoFromReflection(trade, tradeLevel).ifPresent(tradeInfo ->
                            tradesByResult.computeIfAbsent(tradeInfo.result.getItem(), k -> new ArrayList<>()).add(tradeInfo)
                    );
                }
            }
        }
        ComplexityAnalyzer.LOGGER.info("VillagerTradeSource initialized. Found trades for {} unique items.", tradesByResult.size());
    }

    private Optional<TradeInfo> getTradeInfoFromReflection(VillagerTrades.ItemListing trade, int level) {
        try {
            net.minecraft.util.RandomSource randomSource = net.minecraft.util.RandomSource.create();

            MerchantOffer offer = trade.getOffer(cachedFakeVillager, randomSource);
            if (offer == null) {
                return Optional.empty();
            }

            ItemStack result = offer.getResult();
            ItemStack costA = offer.getBaseCostA();
            ItemStack costB = offer.getCostB();

            if (result.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new TradeInfo(result, costA, costB, level));

        } catch (Exception e) {
            // Если getOffer() требует реального жителя, пропускаем
            return Optional.empty();
        }
    }

    private ItemStack getField(Object target, String... fieldNames) throws IllegalAccessException {
        for (String fieldName : fieldNames) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof ItemStack) {
                    return (ItemStack) value;
                }
            } catch (NoSuchFieldException e) {
                //
            }
        }
        return null;
    }

    @Override
    public boolean canProvide(Item item) {
        return tradesByResult.containsKey(item);
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        if (!canProvide(item)) {
            return Optional.empty();
        }

        List<TradeInfo> possibleTrades = tradesByResult.get(item);
        if (possibleTrades.isEmpty()) {
            return Optional.empty();
        }

        TradeInfo bestTrade = possibleTrades.getFirst();

        Map<Item, Double> sourceItems = new HashMap<>();

        if (!bestTrade.costA().isEmpty()) {
            double amountNeeded = (double) bestTrade.costA().getCount() / bestTrade.result().getCount();
            sourceItems.put(bestTrade.costA().getItem(), amountNeeded);
        }
        if (!bestTrade.costB().isEmpty()) {
            double amountNeeded = (double) bestTrade.costB().getCount() / bestTrade.result().getCount();
            sourceItems.put(bestTrade.costB().getItem(), amountNeeded);
        }

        double tradeCost = LEVEL_COST_MAP.getOrDefault(bestTrade.level(), 1.0);
        String details = String.format("Trade with Lvl %d Villager (1 of %d options)", bestTrade.level(), possibleTrades.size());

        // ДИАГНОСТИКА
        String itemId = item.toString();
        if (itemId.contains("legging")) {
            ComplexityAnalyzer.LOGGER.warn("VILLAGER TRADE ANALYSIS: {} -> baseFactor={}, sourceItems={}, costA={}, costB={}",
                    itemId, tradeCost, sourceItems, bestTrade.costA(), bestTrade.costB());
        }

        return Optional.of(new BaseResourceData.Builder(item, this)
                .sourceType(getSourceType())
                .baseFactor(tradeCost)
                .sourceItems(sourceItems)
                .details(details)
                .build());
    }

    @Override
    public int getPriority() { return 35; }

    @Override
    public String getName() { return "VillagerTradeSource"; }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() { return BaseResourceData.ResourceSourceType.VILLAGER_TRADE; }
}