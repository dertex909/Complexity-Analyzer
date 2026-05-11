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

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.Nullable;

public class VillagerTradeSource implements IResourceSource {

    private static final Int2DoubleMap LEVEL_COST_MAP = new Int2DoubleOpenHashMap();

    static {
        LEVEL_COST_MAP.put(1, 1.2);
        LEVEL_COST_MAP.put(2, 1.5);
        LEVEL_COST_MAP.put(3, 2.0);
        LEVEL_COST_MAP.put(4, 3.0);
        LEVEL_COST_MAP.put(5, 5.0);
    }

    private static final ObjectSet<String> SKIP_TRADE_TYPES = new ObjectOpenHashSet<>(new String[]{
            "TreasureMapForEmeralds",
            "EnchantedItemForEmeralds"
    });

    private final Reference2ObjectMap<Item, ObjectList<TradeInfo>> tradesByResult = new Reference2ObjectOpenHashMap<>();

    private record TradeInfo(ItemStack result, ItemStack costA, ItemStack costB, int level) {
    }

    private record PendingTrade(VillagerTrades.ItemListing listing, int level, String type) {
    }

    @Override
    public void initialize(Level level) {
        ComplexityAnalyzer.LOGGER.info("[VTS] Starting VillagerTradeSource initialization...");

        long totalStartTime = System.currentTimeMillis();

        int totalTrades = 0;
        int fastParsed = 0;
        int entityParsed = 0;
        int skippedSlow = 0;
        int failedTrades = 0;

        var pendingTrades = new ObjectArrayList<PendingTrade>();
        var skippedByType = new Object2IntOpenHashMap<String>();

        for (Int2ObjectMap<VillagerTrades.ItemListing[]> professionTrades : VillagerTrades.TRADES.values()) {
            for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> levelEntry : professionTrades.int2ObjectEntrySet()) {
                int tradeLevel = levelEntry.getIntKey();

                for (VillagerTrades.ItemListing listing : levelEntry.getValue()) {
                    totalTrades++;
                    String tradeType = listing.getClass().getSimpleName();

                    if (SKIP_TRADE_TYPES.contains(tradeType)) {
                        skippedSlow++;
                        skippedByType.addTo(tradeType, 1);
                        continue;
                    }

                    try {
                        var randomSource = RandomSource.create();
                        var offer = listing.getOffer(null, randomSource);

                        if (offer != null && !offer.getResult().isEmpty()) {
                            fastParsed++;
                            addTrade(offer, tradeLevel);
                        } else {
                            failedTrades++;
                        }

                    } catch (NullPointerException e) {
                        pendingTrades.add(new PendingTrade(listing, tradeLevel, tradeType));
                    } catch (Exception e) {
                        failedTrades++;
                    }
                }
            }
        }

        long phase1Time = System.currentTimeMillis() - totalStartTime;
        ComplexityAnalyzer.LOGGER.info("[VTS] Phase 1 complete in {}ms: {}/{} trades (skipped {} slow types)",
                phase1Time, fastParsed, totalTrades, skippedSlow);

        if (!pendingTrades.isEmpty()) {
            long phase2Start = System.currentTimeMillis();
            ComplexityAnalyzer.LOGGER.info("[VTS] Phase 2: Processing {} trades with entity...", pendingTrades.size());

            Villager villager = new Villager(EntityType.VILLAGER, level) {
                @Override
                protected void registerGoals() {
                }

                @Override
                public void tick() {
                }
            };

            int logInterval = Math.max(1, pendingTrades.size() / 10);

            for (int i = 0; i < pendingTrades.size(); i++) {
                PendingTrade pending = pendingTrades.get(i);

                if ((i + 1) % logInterval == 0 || (i + 1) == pendingTrades.size()) {
                    ComplexityAnalyzer.LOGGER.debug("[VTS] Phase 2 progress: {}/{}", i + 1, pendingTrades.size());
                }

                try {
                    var randomSource = RandomSource.create();
                    var offer = pending.listing.getOffer(villager, randomSource);

                    if (offer != null && !offer.getResult().isEmpty()) {
                        entityParsed++;
                        addTrade(offer, pending.level);
                    } else {
                        failedTrades++;
                    }

                } catch (Exception e) {
                    failedTrades++;
                    ComplexityAnalyzer.LOGGER.trace("[VTS] Entity trade failed ({}): {}", pending.type, e.getMessage());
                }
            }

            villager.discard();

            long phase2Time = System.currentTimeMillis() - phase2Start;
            ComplexityAnalyzer.LOGGER.info("[VTS] Phase 2 complete in {}ms: {}/{} trades",
                    phase2Time, entityParsed, pendingTrades.size());
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;

        ComplexityAnalyzer.LOGGER.info("[VTS] ===== Initialization complete in {}ms =====", totalTime);
        ComplexityAnalyzer.LOGGER.info("[VTS] Results: {} items, {}/{} trades ({}% success)",
                tradesByResult.size(), fastParsed + entityParsed, totalTrades,
                String.format("%.1f", (fastParsed + entityParsed) * 100.0 / totalTrades));
        ComplexityAnalyzer.LOGGER.info("[VTS]   Fast: {}, Entity: {}, Skipped: {}, Failed: {}",
                fastParsed, entityParsed, skippedSlow, failedTrades);

        if (!skippedByType.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("[VTS] Skipped slow trade types:");
            skippedByType.forEach((type, count) ->
                    ComplexityAnalyzer.LOGGER.info("[VTS]   {} - {} times", type, count));
        }
    }

    private void addTrade(MerchantOffer offer, int level) {
        var resultItem = offer.getResult().getItem();

        tradesByResult.computeIfAbsent(resultItem, k -> new ObjectArrayList<>(2))
                .add(new TradeInfo(offer.getResult().copy(), offer.getBaseCostA().copy(), offer.getCostB().copy(), level));
    }

    @Override
    public boolean canProvide(Item item) {
        return tradesByResult.containsKey(item);
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        var trades = tradesByResult.get(item);
        if (trades == null || trades.isEmpty()) return null;

        var bestTrade = trades.getFirst();
        for (int i = 1; i < trades.size(); i++) {
            if (trades.get(i).level() < bestTrade.level()) bestTrade = trades.get(i);
        }

        var sourceItems = new Reference2DoubleOpenHashMap<Item>(2);

        if (!bestTrade.costA().isEmpty()) sourceItems.put(bestTrade.costA().getItem(),
                (double) bestTrade.costA().getCount() / bestTrade.result().getCount());

        if (!bestTrade.costB().isEmpty()) sourceItems.put(bestTrade.costB().getItem(),
                (double) bestTrade.costB().getCount() / bestTrade.result().getCount());

        return new BaseResourceData.Builder(item, this)
                .sourceType(getSourceType())
                .baseFactor(LEVEL_COST_MAP.getOrDefault(bestTrade.level(), 1.0))
                .sourceItems(sourceItems)
                .sourceSpecifier("Lvl " + bestTrade.level())
                .details(String.format("Trade with Lvl %d Villager", bestTrade.level()))
                .build();
    }

    @Override
    public int getPriority() {
        return 35;
    }

    @Override
    public String getName() {
        return "VillagerTradeSource";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.VILLAGER_TRADE;
    }
}