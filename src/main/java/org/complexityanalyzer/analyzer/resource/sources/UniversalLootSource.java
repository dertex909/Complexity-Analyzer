/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class UniversalLootSource implements IResourceSource {

    private static final int SIMULATION_COUNT = 500;
    private static final int SIMULATION_TIMEOUT_MS = 1000;

    private final Map<BaseResourceData.ResourceSourceType, Map<Item, BaseResourceData>> allLootData = new ConcurrentHashMap<>();

    private static class LootFunctionFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null || event.getLevel() != org.apache.logging.log4j.Level.WARN) {
                return Result.NEUTRAL;
            }

            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("net.minecraft.world.level.storage.loot.functions.")) {
                String message = event.getMessage().getFormattedMessage();
                if (message != null && (
                        message.contains("Couldn't set damage") ||
                                message.contains("Couldn't smelt") ||
                                message.contains("Couldn't find a compatible enchantment")
                )) {
                    return Result.DENY;
                }
            }

            return Result.NEUTRAL;
        }
    }

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Initialize called with non-server level. Aborting.");
            return;
        }

        CompletableFuture<Set<ResourceKey<LootTable>>> lootKeysFuture = getCompletableFuture(serverLevel);

        try {
            Set<ResourceKey<LootTable>> allLootTableKeys = lootKeysFuture.join();

            processLootTables(serverLevel, allLootTableKeys);

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Failed to get loot table keys from server thread. Aborting analysis.", e);
        }
    }

    private @NotNull CompletableFuture<Set<ResourceKey<LootTable>>> getCompletableFuture(ServerLevel serverLevel) {
        MinecraftServer server = serverLevel.getServer();

        CompletableFuture<Set<ResourceKey<LootTable>>> lootKeysFuture = new CompletableFuture<>();

        server.execute(() -> {
            try {
                Set<ResourceKey<LootTable>> keys = getAllLootTableKeys(server);
                lootKeysFuture.complete(keys);
            } catch (Exception e) {
                lootKeysFuture.completeExceptionally(e);
            }
        });
        return lootKeysFuture;
    }

    private void processLootTables(ServerLevel serverLevel, Set<ResourceKey<LootTable>> allLootTableKeys) {
        MinecraftServer server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("[ULS] Auto-scanning ALL loot tables (including mods)...");
        long startTime = System.currentTimeMillis();
        int tablesProcessed = 0;
        int tablesSkipped = 0;

        LootFunctionFilter filter = new LootFunctionFilter();
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        filter.start();
        rootLogger.addFilter(filter);

        try {
            var reloadableRegistries = server.reloadableRegistries();
            ComplexityAnalyzer.LOGGER.debug("[ULS] Found {} total loot tables to analyze.", allLootTableKeys.size());

            for (ResourceKey<LootTable> lootTableKey : allLootTableKeys) {
                ResourceLocation lootTableId = lootTableKey.location();
                Optional<LootContextDefinition> contextDefOpt = inferContextFromId(lootTableId);
                if (contextDefOpt.isEmpty()) {
                    tablesSkipped++;
                    continue;
                }

                LootContextDefinition contextDef = contextDefOpt.get();

                try {
                    LootTable lootTable = CompletableFuture.supplyAsync(() ->
                            reloadableRegistries.getLootTable(lootTableKey), server
                    ).join();

                    if (lootTable == LootTable.EMPTY) {
                        tablesSkipped++;
                        continue;
                    }
                    tablesProcessed++;

                    LootParams lootParams = contextDef.createLootParams(serverLevel);
                    if (lootParams == null) {
                        ComplexityAnalyzer.LOGGER.debug("[ULS] Failed to create loot params for '{}', skipping.", lootTableId);
                        continue;
                    }

                    Map<Item, Integer> catchCounts = CompletableFuture.supplyAsync(() -> {
                        Map<Item, Integer> counts = new HashMap<>();
                        long simulationStart = System.currentTimeMillis();

                        boolean hasLoggedError = false;
                        for (int i = 0; i < SIMULATION_COUNT; i++) {
                            if (System.currentTimeMillis() - simulationStart > SIMULATION_TIMEOUT_MS) {
                                ComplexityAnalyzer.LOGGER.warn("[ULS] Simulation timeout for '{}' after {} iterations. Skipping.", lootTableId, i);
                                counts.clear();
                                break;
                            }

                            try {
                                List<ItemStack> items = lootTable.getRandomItems(lootParams);
                                if (items.isEmpty()) continue;

                                for (ItemStack stack : items) {
                                    if (!stack.isEmpty()) {
                                        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                                    }
                                }
                            } catch (Exception e) {
                                if (!hasLoggedError) {
                                    ComplexityAnalyzer.LOGGER.debug("[ULS] Error processing '{}': {} (suppressing further errors)", lootTableId, e.getMessage());
                                    hasLoggedError = true;
                                }
                            }
                        }
                        return counts;
                    }, server).join();

                    if (catchCounts.isEmpty()) {
                        continue;
                    }

                    for (Map.Entry<Item, Integer> itemEntry : catchCounts.entrySet()) {
                        Item item = itemEntry.getKey();
                        double itemsPerAttempt = (double) itemEntry.getValue() / SIMULATION_COUNT;
                        if (itemsPerAttempt <= 0) continue;

                        double baseFactor = (contextDef.baseActionCost / itemsPerAttempt) * contextDef.sourceType.getBaseMultiplier();
                        String details = String.format("From loot table '%s', Chance: %.3f%%", lootTableId, itemsPerAttempt * 100);

                        BaseResourceData.Builder builder = new BaseResourceData.Builder(item, this)
                                .sourceType(contextDef.sourceType)
                                .baseFactor(baseFactor)
                                .sourceSpecifier(lootTableId.toString())
                                .details(details);

                        if (contextDef.sourceType == BaseResourceData.ResourceSourceType.PIGLIN_BARTERING) {
                            builder.baseFactor(contextDef.baseActionCost);
                            builder.sourceItems(Map.of(Items.GOLD_INGOT, 1.0 / itemsPerAttempt));
                        }

                        BaseResourceData data = builder.build();

                        allLootData.computeIfAbsent(contextDef.sourceType, k -> new HashMap<>()).put(item, data);
                    }

                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.debug("[ULS] Error processing '{}': {}", lootTableId, e.getMessage());
                }
            }

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Critical error during auto-scan: ", e);
        } finally {
            try {
                rootLogger.get().removeFilter(filter);
                filter.stop();
            } catch (Exception ignored) {
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        int totalItemsFound = allLootData.values().stream().mapToInt(Map::size).sum();

        ComplexityAnalyzer.LOGGER.info("[ULS] Auto-scan complete in {}ms. Processed {} loot tables ({} skipped), found {} unique items.",
                duration, tablesProcessed, tablesSkipped, totalItemsFound);

        for (var entry : allLootData.entrySet()) {
            ComplexityAnalyzer.LOGGER.debug("[ULS]   {} -> {} items", entry.getKey().getDisplayName(), entry.getValue().size());
        }
    }

    private Set<ResourceKey<LootTable>> getAllLootTableKeys(MinecraftServer server) {
        try {
            var registries = server.reloadableRegistries().get();
            var lootRegistry = registries.registry(Registries.LOOT_TABLE).orElseThrow();

            Set<ResourceKey<LootTable>> keys = new HashSet<>(lootRegistry.registryKeySet());

            ComplexityAnalyzer.LOGGER.debug("[ULS] Found {} loot tables via reloadableRegistries.", keys.size());
            return keys;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Failed to access loot table registry:", e);
            return getFallbackLootTables();
        }
    }

    private Set<ResourceKey<LootTable>> getFallbackLootTables() {
        Set<ResourceKey<LootTable>> keys = new HashSet<>();

        String[] knownTables = {
                "gameplay/fishing", "gameplay/fishing/fish", "gameplay/fishing/treasure", "gameplay/fishing/junk",
                "gameplay/piglin_bartering",
                "chests/abandoned_mineshaft", "chests/ancient_city", "chests/bastion_treasure",
                "chests/bastion_bridge", "chests/buried_treasure", "chests/desert_pyramid",
                "chests/end_city_treasure", "chests/igloo_chest", "chests/jungle_temple",
                "chests/nether_bridge", "chests/pillager_outpost", "chests/shipwreck_treasure",
                "chests/simple_dungeon", "chests/stronghold_corridor", "chests/stronghold_library",
                "chests/village/village_armorer", "chests/village/village_weaponsmith",
                "chests/woodland_mansion",
                "archaeology/desert_pyramid", "archaeology/desert_well",
                "archaeology/ocean_ruin_cold", "archaeology/ocean_ruin_warm",
                "archaeology/trail_ruins_common", "archaeology/trail_ruins_rare",
                "shearing/beehive", "shearing/bee_nest"
        };

        for (String path : knownTables) {
            ResourceLocation id = ResourceLocation.withDefaultNamespace(path);
            keys.add(ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, id));
        }

        ComplexityAnalyzer.LOGGER.debug("[ULS] Loaded {} fallback loot tables.", keys.size());
        return keys;
    }

    @Override
    public boolean canProvide(Item item) {
        return allLootData.values().stream().anyMatch(map -> map.containsKey(item));
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        for (BaseResourceData.ResourceSourceType type : BaseResourceData.ResourceSourceType.values()) {
            Map<Item, BaseResourceData> map = allLootData.get(type);
            if (map != null && map.containsKey(item)) {
                return Optional.of(map.get(item));
            }
        }
        return Optional.empty();
    }

    private record LootContextDefinition(BaseResourceData.ResourceSourceType sourceType, double baseActionCost) {
        public LootParams createLootParams(ServerLevel level) {
            LootParams.Builder builder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, new net.minecraft.world.phys.Vec3(0, 0, 0));

            if (sourceType == BaseResourceData.ResourceSourceType.FISHING) {
                builder.withParameter(LootContextParams.TOOL, new ItemStack(Items.FISHING_ROD));
                return builder.create(LootContextParamSets.FISHING);
            }

            if (sourceType == BaseResourceData.ResourceSourceType.SHEARING) {
                builder.withParameter(LootContextParams.TOOL, new ItemStack(Items.SHEARS));
                return builder.create(LootContextParamSets.SHEARING);
            }

            if (sourceType == BaseResourceData.ResourceSourceType.PIGLIN_BARTERING) {
                try {
                    var piglinEntity = net.minecraft.world.entity.EntityType.PIGLIN.create(level);
                    if (piglinEntity != null) {
                        builder.withParameter(LootContextParams.THIS_ENTITY, piglinEntity);
                        return builder.create(LootContextParamSets.PIGLIN_BARTER);
                    }
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.warn("[ULS] Failed to create piglin entity: {}", e.getMessage());
                }
                return null;
            }

            return builder.create(LootContextParamSets.CHEST);
        }
    }

    private Optional<LootContextDefinition> inferContextFromId(ResourceLocation id) {
        String path = id.getPath();

        if (path.startsWith("shearing/") || path.contains("shearing")) {
            return Optional.of(new LootContextDefinition(BaseResourceData.ResourceSourceType.SHEARING, 5.0));
        }

        if (path.contains("fishing")) {
            return Optional.of(new LootContextDefinition(BaseResourceData.ResourceSourceType.FISHING, 25.0));
        }

        if (path.contains("piglin_bartering") || path.contains("bartering")) {
            return Optional.of(new LootContextDefinition(BaseResourceData.ResourceSourceType.PIGLIN_BARTERING, 0.1));
        }

        if (path.startsWith("chests/") || path.contains("chest")) {
            return Optional.of(new LootContextDefinition(BaseResourceData.ResourceSourceType.CHEST_LOOT, 100.0));
        }

        if (path.startsWith("archaeology/") || path.contains("archaeology")) {
            return Optional.of(new LootContextDefinition(BaseResourceData.ResourceSourceType.ARCHAEOLOGY, 15.0));
        }

        if (path.startsWith("gameplay/")) {
            return Optional.of(new LootContextDefinition(BaseResourceData.ResourceSourceType.GENERIC_LOOT, 50.0));
        }

        return Optional.empty();
    }

    @Override
    public int getPriority() {
        return 25;
    }

    @Override
    public String getName() {
        return "UniversalLootSource";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.GENERIC_LOOT;
    }

    public Map<BaseResourceData.ResourceSourceType, Map<Item, BaseResourceData>> getAllLootData() {
        return allLootData;
    }
}