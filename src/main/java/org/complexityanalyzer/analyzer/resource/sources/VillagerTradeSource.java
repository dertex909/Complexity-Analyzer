package org.complexityanalyzer.analyzer.resource.sources;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

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

    // Используем fastutil - быстрее стандартных HashMap/ArrayList
    private final Map<Item, List<TradeInfo>> tradesByResult = new Object2ObjectOpenHashMap<>();

    private net.minecraft.world.entity.npc.Villager cachedFakeVillager;
    private net.minecraft.util.RandomSource cachedRandomSource;

    private record TradeInfo(ItemStack result, ItemStack costA, ItemStack costB, int level) {}

    @Override
    public void initialize(Level level) {
        ComplexityAnalyzer.LOGGER.debug("Initializing VillagerTradeSource...");

        // Кэшируем villager и RandomSource - не создаем каждый раз
        cachedFakeVillager = new net.minecraft.world.entity.npc.Villager(
                net.minecraft.world.entity.EntityType.VILLAGER,
                level
        );
        cachedRandomSource = net.minecraft.util.RandomSource.create();

        // Проходим по всем трейдам
        for (Int2ObjectMap<VillagerTrades.ItemListing[]> professionTrades : VillagerTrades.TRADES.values()) {
            for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> levelEntry : professionTrades.int2ObjectEntrySet()) {
                int tradeLevel = levelEntry.getIntKey();
                VillagerTrades.ItemListing[] listings = levelEntry.getValue();

                for (VillagerTrades.ItemListing trade : listings) {
                    TradeInfo tradeInfo = extractTradeInfo(trade, tradeLevel);
                    if (tradeInfo != null) {
                        // computeIfAbsent создает лямбду каждый раз - избегаем этого
                        List<TradeInfo> trades = tradesByResult.computeIfAbsent(tradeInfo.result.getItem(), k -> new ObjectArrayList<>(2));
                        trades.add(tradeInfo);
                    }
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("VillagerTradeSource initialized. Found trades for {} unique items.", tradesByResult.size());

        // Освобождаем память после инициализации
        cachedFakeVillager = null;
        cachedRandomSource = null;
    }

    // Убрали Optional - возвращаем null напрямую (быстрее)
    private TradeInfo extractTradeInfo(VillagerTrades.ItemListing trade, int level) {
        try {
            MerchantOffer offer = trade.getOffer(cachedFakeVillager, cachedRandomSource);
            if (offer == null) return null;

            ItemStack result = offer.getResult();
            if (result.isEmpty()) return null;

            return new TradeInfo(result, offer.getBaseCostA(), offer.getCostB(), level);

        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean canProvide(Item item) {
        return tradesByResult.containsKey(item);
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        List<TradeInfo> possibleTrades = tradesByResult.get(item);
        if (possibleTrades == null || possibleTrades.isEmpty()) {
            return Optional.empty();
        }

        // Находим лучший трейд (с минимальным уровнем)
        TradeInfo bestTrade = possibleTrades.getFirst();
        int minLevel = bestTrade.level();

        for (int i = 1; i < possibleTrades.size(); i++) {
            TradeInfo currentTrade = possibleTrades.get(i);
            if (currentTrade.level() < minLevel) {
                minLevel = currentTrade.level();
                bestTrade = currentTrade;
            }
        }

        // Используем fastutil и указываем начальную емкость
        Map<Item, Double> sourceItems = new Object2ObjectOpenHashMap<>(2);

        ItemStack costA = bestTrade.costA();
        if (!costA.isEmpty()) {
            sourceItems.put(costA.getItem(), (double) costA.getCount() / bestTrade.result().getCount());
        }

        ItemStack costB = bestTrade.costB();
        if (!costB.isEmpty()) {
            sourceItems.put(costB.getItem(), (double) costB.getCount() / bestTrade.result().getCount());
        }

        double tradeCost = LEVEL_COST_MAP.getOrDefault(bestTrade.level(), 1.0);
        String details = String.format("Trade with Lvl %d Villager (best of %d options)",
                bestTrade.level(), possibleTrades.size());

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
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.VILLAGER_TRADE;
    }
}