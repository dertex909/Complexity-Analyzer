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
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
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
import org.complexityanalyzer.analyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.apache.logging.log4j.Level.WARN;

public class UniversalLootSource implements IResourceSource, IMultiSourceProvider {

    private static final int SIMULATION_COUNT = 500;

    private final Reference2ObjectMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>> allLootData = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    private static class LootFunctionFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null || event.getLevel() != WARN) return Result.NEUTRAL;

            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("net.minecraft.world.level.storage.loot.functions.")) {
                String message = event.getMessage().getFormattedMessage();
                if (message != null && (message.contains("Couldn't set damage")
                        || message.contains("Couldn't smelt")
                        || message.contains("Couldn't find a compatible enchantment")
                )) return Result.DENY;
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

        var server = serverLevel.getServer();
        if (server.isSameThread()) {
            try {
                var allLootTableKeys = getAllLootTableKeys(server);
                processLootTables(serverLevel, allLootTableKeys);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("[ULS] Failed to get loot table keys on server thread. Aborting analysis.", e);
            }
        } else {
            var lootKeysFuture = getCompletableFuture(serverLevel);
            try {
                var allLootTableKeys = lootKeysFuture.join();
                processLootTables(serverLevel, allLootTableKeys);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("[ULS] Failed to get loot table keys from server thread. Aborting analysis.", e);
            }
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
        var server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("[ULS] Auto-scanning ALL loot tables (including mods)...");
        long startTime = System.currentTimeMillis();
        int tablesProcessed = 0;
        int tablesSkipped = 0;

        var filter = new LootFunctionFilter();
        var rootLogger = (Logger) LogManager.getRootLogger();
        filter.start();
        rootLogger.addFilter(filter);

        try {
            var reloadableRegistries = server.reloadableRegistries();
            ComplexityAnalyzer.LOGGER.debug("[ULS] Found {} total loot tables to analyze.", allLootTableKeys.size());

            var sortedKeys = new ObjectArrayList<>(allLootTableKeys);
            sortedKeys.sort(Comparator.comparing(k -> k.location().toString()));

            for (var lootTableKey : sortedKeys) {
                var lootTableId = lootTableKey.location();
                var contextDef = inferContextFromId(lootTableId);
                if (contextDef == null) {
                    tablesSkipped++;
                    continue;
                }

                try {
                    Supplier<Reference2IntOpenHashMap<Item>> countsSupplier = () -> {
                        var lootTable = reloadableRegistries.getLootTable(lootTableKey);
                        if (lootTable == LootTable.EMPTY) return null;

                        var lootParams = contextDef.createLootParams(serverLevel);
                        if (lootParams == null) {
                            ComplexityAnalyzer.LOGGER.debug("[ULS] Failed to create loot params for '{}', skipping.", lootTableId);
                            return null;
                        }

                        var counts = new Reference2IntOpenHashMap<Item>();
                        boolean hasLoggedError = false;
                        long baseSeed = lootTableId.hashCode();

                        for (int i = 0; i < SIMULATION_COUNT; i++) {
                            try {
                                var context = new LootContext.Builder(lootParams)
                                        .withOptionalRandomSource(RandomSource.create(baseSeed + i))
                                        .create(Optional.empty());

                                var items = new ObjectArrayList<ItemStack>();
                                lootTable.getRandomItems(context, items::add);
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
                    };

                    Reference2IntOpenHashMap<Item> catchCounts;
                    if (server.isSameThread()) {
                        catchCounts = countsSupplier.get();
                    } else {
                        catchCounts = CompletableFuture.supplyAsync(countsSupplier, server).join();
                    }

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

                        var typeMap = allLootData.computeIfAbsent(contextDef.sourceType, k -> new Reference2ObjectOpenHashMap<>());
                        var existing = typeMap.get(item);
                        if (existing == null || data.getBaseFactor() < existing.getBaseFactor())
                            typeMap.put(item, data);
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
            return new ObjectOpenHashSet<>();
        }
    }

    @Override
    public boolean canProvide(Item item) {
        for (var map : allLootData.values()) if (map.containsKey(item)) return true;
        return false;
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        BaseResourceData best = null;
        for (var sourceType : BaseResourceData.ResourceSourceType.values()) {
            var map = allLootData.get(sourceType);
            if (map == null) continue;
            var data = map.get(item);
            if (data != null && (best == null || data.getBaseFactor() < best.getBaseFactor())) best = data;
        }
        return best;
    }

    @Override
    public ObjectList<BaseResourceData> findAllSources(Item item) {
        var results = new ObjectArrayList<BaseResourceData>();
        for (var sourceType : BaseResourceData.ResourceSourceType.values()) {
            var map = allLootData.get(sourceType);
            if (map == null) continue;
            var data = map.get(item);
            if (data != null) results.add(data);
        }
        results.sort(Comparator.comparingDouble(BaseResourceData::getBaseFactor).thenComparing(BaseResourceData::getSourceType));
        return results;
    }

    private record LootContextDefinition(BaseResourceData.ResourceSourceType sourceType, double baseActionCost) {
        public LootParams createLootParams(ServerLevel level) {
            var spawnPos = level.getSharedSpawnPos();
            var originVec = new Vec3(spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5);
            var builder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, originVec);

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
                        piglinEntity.setPos(originVec.x, originVec.y, originVec.z);
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