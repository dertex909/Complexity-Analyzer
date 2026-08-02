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

import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;

public class VillagerTradeSource implements IResourceSource {

    private static final Int2DoubleMap LEVEL_COST_MAP = new Int2DoubleOpenHashMap();
    private static final ObjectSet<String> SKIP_TRADE_TYPES = new ObjectOpenHashSet<>(new String[]{
            "TreasureMapForEmeralds",
            "EnchantedItemForEmeralds"
    });

    static {
        LEVEL_COST_MAP.put(1, 1.2);
        LEVEL_COST_MAP.put(2, 1.5);
        LEVEL_COST_MAP.put(3, 2.0);
        LEVEL_COST_MAP.put(4, 3.0);
        LEVEL_COST_MAP.put(5, 5.0);
    }

    private final Reference2ObjectMap<Item, ObjectList<TradeInfo>> tradesByResult = new Reference2ObjectOpenHashMap<>();

    private static String professionId(VillagerProfession profession) {
        var id = GameRegistryManager.getProfessionId(profession);
        return id != null ? id.toString() : "minecraft:none";
    }

    private static long stableSeed(String professionId, int level, int index) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < professionId.length(); i++) h = (h ^ professionId.charAt(i)) * 0x100000001b3L;
        h = (h ^ level) * 0x100000001b3L;
        h = (h ^ index) * 0x100000001b3L;
        return h;
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

        var professions = new ObjectArrayList<>(VillagerTrades.TRADES.entrySet());
        professions.sort(Comparator.comparing(e -> professionId(e.getKey())));

        for (var professionEntry : professions) {
            String profId = professionId(professionEntry.getKey());
            var professionTrades = professionEntry.getValue();

            int[] levels = professionTrades.keySet().toIntArray();
            Arrays.sort(levels);

            for (int tradeLevel : levels) {
                var listings = professionTrades.get(tradeLevel);
                for (int index = 0; index < listings.length; index++) {
                    var listing = listings[index];
                    totalTrades++;
                    String tradeType = listing.getClass().getSimpleName();

                    if (SKIP_TRADE_TYPES.contains(tradeType)) {
                        skippedSlow++;
                        skippedByType.addTo(tradeType, 1);
                        continue;
                    }

                    long seed = stableSeed(profId, tradeLevel, index);
                    try {
                        var offer = listing.getOffer(null, RandomSource.create(seed));

                        if (offer != null && !offer.getResult().isEmpty()) {
                            fastParsed++;
                            addTrade(offer, tradeLevel);
                        } else {
                            failedTrades++;
                        }

                    } catch (NullPointerException e) {
                        pendingTrades.add(new PendingTrade(listing, tradeLevel, tradeType, seed));
                    } catch (Exception e) {
                        failedTrades++;
                    }
                }
            }
        }

        long phase1Time = System.currentTimeMillis() - totalStartTime;
        ComplexityAnalyzer.LOGGER.debug("[VTS] Phase 1 complete in {}ms: {}/{} trades (skipped {} slow types)",
                phase1Time, fastParsed, totalTrades, skippedSlow);

        if (!pendingTrades.isEmpty()) {
            long phase2Start = System.currentTimeMillis();
            ComplexityAnalyzer.LOGGER.debug("[VTS] Phase 2: Processing {} trades with entity...", pendingTrades.size());

            var villager = new Villager(EntityType.VILLAGER, level) {
                @Override
                protected void registerGoals() {
                }

                @Override
                public void tick() {
                }
            };

            for (var pending : pendingTrades) {
                try {
                    var offer = pending.listing.getOffer(villager, RandomSource.create(pending.seed()));

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
            ComplexityAnalyzer.LOGGER.debug("[VTS] Phase 2 complete in {}ms: {}/{} trades",
                    phase2Time, entityParsed, pendingTrades.size());
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;

        ComplexityAnalyzer.LOGGER.debug("[VTS] ===== Initialization complete in {}ms =====", totalTime);
        ComplexityAnalyzer.LOGGER.debug("[VTS] Results: {} items, {}/{} trades ({}% success)",
                tradesByResult.size(), fastParsed + entityParsed, totalTrades,
                String.format("%.1f", (fastParsed + entityParsed) * 100.0 / totalTrades));
        ComplexityAnalyzer.LOGGER.debug("[VTS]   Fast: {}, Entity: {}, Skipped: {}, Failed: {}",
                fastParsed, entityParsed, skippedSlow, failedTrades);

        if (!skippedByType.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("[VTS] Skipped slow trade types:");
            for (var entry : skippedByType.object2IntEntrySet()) {
                ComplexityAnalyzer.LOGGER.info("[VTS]   {} - {} times", entry.getKey(), entry.getIntValue());
            }
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

    private record TradeInfo(ItemStack result, ItemStack costA, ItemStack costB, int level) {
    }

    private record PendingTrade(VillagerTrades.ItemListing listing, int level, String type, long seed) {
    }
}