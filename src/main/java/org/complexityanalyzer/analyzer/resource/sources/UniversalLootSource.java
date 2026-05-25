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

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class UniversalLootSource implements IResourceSource {

    private static final int SIMULATION_COUNT = 500;
    private static final int SIMULATION_TIMEOUT_MS = 1000;

    private final Reference2ObjectMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>> allLootData = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    private static class LootFunctionFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null || event.getLevel() != org.apache.logging.log4j.Level.WARN) {
                return Result.NEUTRAL;
            }

            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("net.minecraft.world.level.storage.loot.functions.")) {
                String message = event.getMessage().getFormattedMessage();
                if (message != null && (message.contains("Couldn't set damage") || message.contains("Couldn't smelt")
                        || message.contains("Couldn't find a compatible enchantment"))) return Result.DENY;
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

        var lootKeysFuture = getCompletableFuture(serverLevel);

        try {
            var allLootTableKeys = lootKeysFuture.join();
            processLootTables(serverLevel, allLootTableKeys);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Failed to get loot table keys from server thread. Aborting analysis.", e);
        }
    }

    private @NotNull CompletableFuture<ObjectSet<ResourceKey<LootTable>>> getCompletableFuture(ServerLevel serverLevel) {
        var server = serverLevel.getServer();
        var lootKeysFuture = new CompletableFuture<ObjectSet<ResourceKey<LootTable>>>();

        server.execute(() -> {
            try {
                var keys = getAllLootTableKeys(server);
                lootKeysFuture.complete(keys);
            } catch (Exception e) {
                lootKeysFuture.completeExceptionally(e);
            }
        });
        return lootKeysFuture;
    }

    private void processLootTables(ServerLevel serverLevel, ObjectSet<ResourceKey<LootTable>> allLootTableKeys) {
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

            for (var lootTableKey : allLootTableKeys) {
                var lootTableId = lootTableKey.location();
                var contextDef = inferContextFromId(lootTableId);
                if (contextDef == null) {
                    tablesSkipped++;
                    continue;
                }

                try {
                    var catchCounts = CompletableFuture.supplyAsync(() -> {
                        LootTable lootTable = reloadableRegistries.getLootTable(lootTableKey);
                        if (lootTable == LootTable.EMPTY) return null;

                        var lootParams = contextDef.createLootParams(serverLevel);
                        if (lootParams == null) {
                            ComplexityAnalyzer.LOGGER.debug("[ULS] Failed to create loot params for '{}', skipping.", lootTableId);
                            return null;
                        }

                        var counts = new Reference2IntOpenHashMap<Item>();
                        long simulationStart = System.currentTimeMillis();
                        boolean hasLoggedError = false;

                        for (int i = 0; i < SIMULATION_COUNT; i++) {
                            if (System.currentTimeMillis() - simulationStart > SIMULATION_TIMEOUT_MS) {
                                ComplexityAnalyzer.LOGGER.warn("[ULS] Simulation timeout for '{}' after {} iterations. Skipping.", lootTableId, i);
                                counts.clear();
                                break;
                            }

                            try {
                                var items = lootTable.getRandomItems(lootParams);
                                if (items.isEmpty()) continue;

                                for (var stack : items) {
                                    if (!stack.isEmpty()) counts.addTo(stack.getItem(), stack.getCount());
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

                    if (catchCounts == null || catchCounts.isEmpty()) {
                        tablesSkipped++;
                        continue;
                    }
                    tablesProcessed++;

                    for (var itemEntry : catchCounts.reference2IntEntrySet()) {
                        var item = itemEntry.getKey();
                        var itemsPerAttempt = (double) itemEntry.getIntValue() / SIMULATION_COUNT;
                        if (itemsPerAttempt <= 0) continue;

                        var baseFactor = (contextDef.baseActionCost / itemsPerAttempt) * contextDef.sourceType.getBaseMultiplier();
                        var details = String.format("From loot table '%s', Chance: %.3f%%", lootTableId, itemsPerAttempt * 100);

                        var builder = new BaseResourceData.Builder(item, this)
                                .sourceType(contextDef.sourceType)
                                .baseFactor(baseFactor)
                                .sourceSpecifier(lootTableId.toString())
                                .details(details)
                                .addMetadata("chance", String.format(Locale.ROOT, "%.6f", itemsPerAttempt * 100));

                        if (contextDef.sourceType == BaseResourceData.ResourceSourceType.PIGLIN_BARTERING) {
                            builder.baseFactor(contextDef.baseActionCost);
                            var piglinIng = new Reference2DoubleOpenHashMap<Item>();
                            piglinIng.put(Items.GOLD_INGOT, 1.0 / itemsPerAttempt);
                            builder.sourceItems(piglinIng);
                        }

                        var data = builder.build();

                        allLootData.computeIfAbsent(contextDef.sourceType, k -> new Reference2ObjectOpenHashMap<>()).put(item, data);
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
        var totalItemsFound = 0;
        for (var map : allLootData.values()) totalItemsFound += map.size();

        ComplexityAnalyzer.LOGGER.info("[ULS] Auto-scan complete in {}ms. Processed {} loot tables ({} skipped), found {} unique items.",
                duration, tablesProcessed, tablesSkipped, totalItemsFound);

        for (var entry : allLootData.reference2ObjectEntrySet()) {
            ComplexityAnalyzer.LOGGER.debug("[ULS]   {} -> {} items", entry.getKey().getDisplayName(), entry.getValue().size());
        }
    }

    private ObjectSet<ResourceKey<LootTable>> getAllLootTableKeys(MinecraftServer server) {
        try {
            var registries = server.reloadableRegistries().get();
            var lootRegistry = registries.registry(Registries.LOOT_TABLE).orElseThrow();

            var keys = new ObjectOpenHashSet<>(lootRegistry.registryKeySet());

            ComplexityAnalyzer.LOGGER.debug("[ULS] Found {} loot tables via reloadableRegistries.", keys.size());
            return keys;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Failed to access loot table registry:", e);
            return getFallbackLootTables();
        }
    }

    private ObjectSet<ResourceKey<LootTable>> getFallbackLootTables() {
        var keys = new ObjectOpenHashSet<ResourceKey<LootTable>>();

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

        for (var path : knownTables) {
            var id = ResourceLocation.withDefaultNamespace(path);
            keys.add(ResourceKey.create(Registries.LOOT_TABLE, id));
        }

        ComplexityAnalyzer.LOGGER.debug("[ULS] Loaded {} fallback loot tables.", keys.size());
        return keys;
    }

    @Override
    public boolean canProvide(Item item) {
        for (var map : allLootData.values()) if (map.containsKey(item)) return true;
        return false;
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        for (var map : allLootData.values()) {
            var data = map.get(item);
            if (data != null) return data;
        }
        return null;
    }

    private record LootContextDefinition(BaseResourceData.ResourceSourceType sourceType, double baseActionCost) {
        public LootParams createLootParams(ServerLevel level) {
            var builder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, new Vec3(0, 0, 0));

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
                    var piglinEntity = EntityType.PIGLIN.create(level);
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

    @Nullable
    private LootContextDefinition inferContextFromId(ResourceLocation id) {
        var path = id.getPath();

        if (path.startsWith("shearing/") || path.contains("shearing")) {
            return new LootContextDefinition(BaseResourceData.ResourceSourceType.SHEARING, 5.0);
        }

        if (path.contains("fishing")) {
            return new LootContextDefinition(BaseResourceData.ResourceSourceType.FISHING, 25.0);
        }

        if (path.contains("piglin_bartering") || path.contains("bartering")) {
            return new LootContextDefinition(BaseResourceData.ResourceSourceType.PIGLIN_BARTERING, 0.1);
        }

        if (path.startsWith("chests/") || path.contains("chest")) {
            return new LootContextDefinition(BaseResourceData.ResourceSourceType.CHEST_LOOT, 100.0);
        }

        if (path.startsWith("archaeology/") || path.contains("archaeology")) {
            return new LootContextDefinition(BaseResourceData.ResourceSourceType.ARCHAEOLOGY, 15.0);
        }

        if (path.startsWith("gameplay/")) {
            return new LootContextDefinition(BaseResourceData.ResourceSourceType.GENERIC_LOOT, 50.0);
        }

        return null;
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

    public Reference2ObjectMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>> getAllLootData() {
        return allLootData;
    }
}