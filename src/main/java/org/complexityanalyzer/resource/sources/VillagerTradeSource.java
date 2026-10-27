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
import org.complexityanalyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.util.ProbeScope;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;

import static org.complexityanalyzer.cache.util.Fingerprints.FNV_OFFSET;
import static org.complexityanalyzer.cache.util.Fingerprints.FNV_PRIME;

public class VillagerTradeSource implements IResourceSource, IMultiSourceProvider {

    private static final Int2DoubleMap LEVEL_COST_MAP = new Int2DoubleOpenHashMap();

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
        long h = FNV_OFFSET;
        for (int i = 0; i < professionId.length(); i++) h = (h ^ professionId.charAt(i)) * FNV_PRIME;
        h = (h ^ level) * FNV_PRIME;
        h = (h ^ index) * FNV_PRIME;
        return h;
    }

    private static void addNormalizedCost(Reference2DoubleOpenHashMap<Item> sourceItems, ItemStack cost, double outputCount) {
        if (!cost.isEmpty()) sourceItems.addTo(cost.getItem(), cost.getCount() / outputCount);
    }

    private static String formatStack(ItemStack stack) {
        return stack.isEmpty() ? "none" : "%dx %s".formatted(stack.getCount(), stack.getItem());
    }

    @Override
    public void initialize(Level level) {
        try (var ignored = ProbeScope.open()) {
            initializeInProbeScope(level);
        }
    }

    private void initializeInProbeScope(Level level) {
        ComplexityAnalyzer.LOGGER.info("[VTS] Starting VillagerTradeSource initialization...");

        long totalStartTime = System.currentTimeMillis();

        int totalTrades = 0;
        int fastParsed = 0;
        int entityParsed = 0;
        int failedTrades = 0;

        var pendingTrades = new ObjectArrayList<PendingTrade>();

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

                    long seed = stableSeed(profId, tradeLevel, index);
                    try {
                        var offer = listing.getOffer(null, RandomSource.create(seed));

                        if (offer != null && !offer.getResult().isEmpty()) {
                            fastParsed++;
                            addTrade(offer, profId, tradeLevel, index, tradeType);
                        } else {
                            failedTrades++;
                            ComplexityAnalyzer.LOGGER.trace("[VTS] Trade returned no offer without entity ({} {} L{} #{})",
                                    tradeType, profId, tradeLevel, index + 1);
                        }

                    } catch (NullPointerException e) {
                        pendingTrades.add(new PendingTrade(listing, profId, tradeLevel, index, tradeType, seed));
                    } catch (Exception e) {
                        failedTrades++;
                    }
                }
            }
        }

        long phase1Time = System.currentTimeMillis() - totalStartTime;
        ComplexityAnalyzer.LOGGER.debug("[VTS] Phase 1 complete in {}ms: {} parsed, {} queued for entity, {} failed out of {} trades",
                phase1Time, fastParsed, pendingTrades.size(), failedTrades, totalTrades);

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
                        addTrade(offer, pending.professionId, pending.level, pending.index, pending.type);
                    } else {
                        failedTrades++;
                        ComplexityAnalyzer.LOGGER.trace("[VTS] Entity trade returned no offer in probe scope ({} {} L{} #{})",
                                pending.type, pending.professionId, pending.level, pending.index + 1);
                    }

                } catch (Exception e) {
                    failedTrades++;
                    ComplexityAnalyzer.LOGGER.trace("[VTS] Entity trade failed ({}): {}", pending.type, e.getMessage());
                }
            }

            villager.discard();

            long phase2Time = System.currentTimeMillis() - phase2Start;
            ComplexityAnalyzer.LOGGER.debug("[VTS] Phase 2 complete in {}ms: {}/{} trades", phase2Time, entityParsed, pendingTrades.size());
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        String successPercent = "%.1f".formatted((fastParsed + entityParsed) * 100.0 / totalTrades);

        ComplexityAnalyzer.LOGGER.debug("[VTS] ===== Initialization complete in {}ms =====", totalTime);
        ComplexityAnalyzer.LOGGER.debug("[VTS] Results: {} items, {}/{} trades ({}% success)", tradesByResult.size(), fastParsed + entityParsed, totalTrades, successPercent);
        ComplexityAnalyzer.LOGGER.debug("[VTS]   Fast: {}, Entity: {}, Failed: {}", fastParsed, entityParsed, failedTrades);
    }

    private void addTrade(MerchantOffer offer, String professionId, int level, int index, String tradeType) {
        var resultItem = offer.getResult().getItem();

        tradesByResult.computeIfAbsent(resultItem, k -> new ObjectArrayList<>(2))
                .add(new TradeInfo(offer.getResult().copy(), offer.getBaseCostA().copy(), offer.getCostB().copy(),
                        professionId, level, index, tradeType));
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
        return toResourceData(item, trades.getFirst());
    }

    @Override
    public ObjectList<BaseResourceData> findAllSources(Item item) {
        var trades = tradesByResult.get(item);
        if (trades == null || trades.isEmpty()) return ObjectLists.emptyList();

        var sources = new ObjectArrayList<BaseResourceData>(trades.size());
        for (var trade : trades) sources.add(toResourceData(item, trade));
        return sources;
    }

    private BaseResourceData toResourceData(Item item, TradeInfo trade) {
        var sourceItems = new Reference2DoubleOpenHashMap<Item>(2);
        double outputCount = Math.max(1, trade.result().getCount());

        addNormalizedCost(sourceItems, trade.costA(), outputCount);
        addNormalizedCost(sourceItems, trade.costB(), outputCount);

        String specifier = "%s L%d #%d (%s)".formatted(
                trade.professionId(), trade.level(), trade.index() + 1, trade.type());
        String details = "Villager trade: %s + %s -> %s".formatted(
                formatStack(trade.costA()), formatStack(trade.costB()), formatStack(trade.result()));

        return new BaseResourceData.Builder(item, this)
                .sourceType(getSourceType())
                .baseFactor(LEVEL_COST_MAP.getOrDefault(trade.level(), 1.0))
                .sourceItems(sourceItems)
                .sourceSpecifier(specifier)
                .details(details)
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

    private record TradeInfo(ItemStack result, ItemStack costA, ItemStack costB, String professionId,
                             int level, int index, String type) {
    }

    private record PendingTrade(VillagerTrades.ItemListing listing, String professionId, int level,
                                int index, String type, long seed) {
    }
}
