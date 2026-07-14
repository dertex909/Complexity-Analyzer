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
import org.complexityanalyzer.cache.Fingerprints;
import org.complexityanalyzer.cache.ResourceCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Future;

import static org.apache.logging.log4j.Level.WARN;

public class UniversalLootSource implements IResourceSource, IMultiSourceProvider {

    private static final int SIMULATION_COUNT = 500;
    private volatile Reference2ObjectMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>> allLootData = Reference2ObjectMaps.emptyMap();

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Initialize called with non-server level. Aborting.");
            return;
        }

        var server = serverLevel.getServer();
        try {
            var allLootTableKeys = getAllLootTableKeys(server);
            var localLootData = new Reference2ObjectOpenHashMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>>();

            boolean cacheEnabled = ComplexityConfig.ENABLE_CACHE.get();
            var cacheFile = cacheEnabled ? ResourceCache.UNIVERSAL_LOOT.file(server) : null;
            long[] fingerprint = cacheFile != null ? computeFingerprint(allLootTableKeys) : null;

            if (cacheFile != null && tryLoadCache(cacheFile, fingerprint, localLootData)) {
                this.allLootData = localLootData;
                return;
            }

            processLootTables(serverLevel, allLootTableKeys, localLootData);

            if (cacheFile != null) ResourceCache.UNIVERSAL_LOOT.save(
                    cacheFile, fingerprint, ResourceCache::writeResourceData, flattenLootData(localLootData));

            this.allLootData = localLootData;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[ULS] Failed to get loot table keys. Aborting analysis.", e);
        }
    }

    private long[] computeFingerprint(ObjectSet<ResourceKey<LootTable>> keys) {
        var ids = new ObjectArrayList<String>(keys.size());
        for (var k : keys) ids.add(k.location().toString());
        ids.sort(null);
        long tables = Fingerprints.FNV_OFFSET;
        for (var id : ids) tables = Fingerprints.fnv(tables, id);
        return new long[]{
                Fingerprints.fnvLong(Fingerprints.FNV_OFFSET, SIMULATION_COUNT),
                Fingerprints.hashMods(),
                Fingerprints.hashAllItems(),
                tables
        };
    }

    private boolean tryLoadCache(java.nio.file.Path cacheFile, long[] fingerprint, Reference2ObjectMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>> targetMap) {
        var flat = new Reference2ObjectOpenHashMap<Item, ObjectList<BaseResourceData>>();
        int restored = ResourceCache.UNIVERSAL_LOOT.load(cacheFile, fingerprint, (buf, item) ->
                ResourceCache.readResourceData(buf, item, this), flat);
        if (restored < 0) return false;

        targetMap.clear();
        for (var entry : flat.reference2ObjectEntrySet()) {
            for (var data : entry.getValue()) {
                targetMap.computeIfAbsent(data.getSourceType(), k -> new Reference2ObjectOpenHashMap<>()).put(entry.getKey(), data);
            }
        }
        int items = 0;
        for (var map : targetMap.values()) items += map.size();
        ComplexityAnalyzer.LOGGER.info("[ULS] Loaded {} loot paths for {} items from cache (loot-table scan skipped).", restored, items);
        return true;
    }

    private Reference2ObjectMap<Item, ObjectList<BaseResourceData>> flattenLootData(Reference2ObjectMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>> targetMap) {
        var flat = new Reference2ObjectOpenHashMap<Item, ObjectList<BaseResourceData>>();
        for (var typeMap : targetMap.values()) {
            for (var e : typeMap.reference2ObjectEntrySet()) {
                flat.computeIfAbsent(e.getKey(), k -> new ObjectArrayList<>()).add(e.getValue());
            }
        }
        return flat;
    }

    private void processLootTables(ServerLevel serverLevel, ObjectSet<ResourceKey<LootTable>> allLootTableKeys, Reference2ObjectMap<BaseResourceData.ResourceSourceType, Reference2ObjectMap<Item, BaseResourceData>> targetMap) {
        var server = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.debug("[ULS] Auto-scanning ALL loot tables (including mods)...");
        long startTime = System.currentTimeMillis();
        int tablesProcessed = 0;
        int tablesSkipped = 0;
        long sampleWallMs = 0L;
        var pool = ThreadPoolManager.getInstance();
        int threads = pool.getParallelism();

        var filter = new LootFunctionFilter();
        var rootLogger = (Logger) LogManager.getRootLogger();
        filter.start();
        rootLogger.addFilter(filter);

        try {
            ComplexityAnalyzer.LOGGER.debug("[ULS] Found {} total loot tables to analyze.", allLootTableKeys.size());

            var sortedKeys = new ObjectArrayList<>(allLootTableKeys);
            sortedKeys.sort(Comparator.comparing(k -> k.location().toString()));

            var parallelTasks = new ObjectArrayList<TableTask>();
            var piglinTasks = new ObjectArrayList<TableTask>();
            for (var lootTableKey : sortedKeys) {
                var lootTableId = lootTableKey.location();
                var cDef = inferContextFromId(lootTableId);
                if (cDef == null) {
                    tablesSkipped++;
                    continue;
                }
                var task = new TableTask(lootTableKey, lootTableId, cDef);
                if (cDef.sourceType == BaseResourceData.ResourceSourceType.PIGLIN_BARTERING) piglinTasks.add(task);
                else parallelTasks.add(task);
            }

            long sampleStart = System.currentTimeMillis();
            var results = new ObjectArrayList<TableResult>();
            var computePool = pool.getComputePool();
            var futures = new ObjectArrayList<Future<TableResult>>(parallelTasks.size());
            for (var t : parallelTasks) {
                futures.add(computePool.submit(() -> {
                    var counts = sampleTable(serverLevel, server, t);
                    return counts == null ? null : new TableResult(t.id(), t.def(), counts);
                }));
            }
            for (var f : futures) {
                try {
                    var r = f.get();
                    if (r != null) results.add(r);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.warn("[ULS] Sampling task failed: {}", e.toString());
                }
            }
            for (var t : piglinTasks) {
                var counts = sampleTable(serverLevel, server, t);
                if (counts != null) results.add(new TableResult(t.id(), t.def(), counts));
            }
            sampleWallMs = System.currentTimeMillis() - sampleStart;

            int totalTasks = parallelTasks.size() + piglinTasks.size();
            tablesProcessed = results.size();
            tablesSkipped += totalTasks - results.size();

            for (var r : results) {
                var lootTableId = r.id();
                var contextDef = r.def();
                for (var itemEntry : r.counts().reference2IntEntrySet()) {
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

                    var typeMap = targetMap.computeIfAbsent(contextDef.sourceType, k -> new Reference2ObjectOpenHashMap<>());
                    var existing = typeMap.get(item);
                    if (existing == null || data.getBaseFactor() < existing.getBaseFactor()) typeMap.put(item, data);
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
        for (var map : targetMap.values()) totalItemsFound += map.size();

        ComplexityAnalyzer.LOGGER.info("[ULS] Auto-scan complete in {}ms. Processed {} loot tables ({} skipped), found {} unique items.",
                duration, tablesProcessed, tablesSkipped, totalItemsFound);
        ComplexityAnalyzer.LOGGER.info("[ULS] PROFILE: {} tables sampled on {} threads × {} sims = {} rolls; sampling wall {}ms (of {}ms total).",
                tablesProcessed, threads, SIMULATION_COUNT, (long) tablesProcessed * SIMULATION_COUNT, sampleWallMs, duration);

        for (var entry : targetMap.reference2ObjectEntrySet()) {
            ComplexityAnalyzer.LOGGER.debug("[ULS]   {} -> {} items", entry.getKey().getDisplayName(), entry.getValue().size());
        }
    }

    @Nullable
    private Reference2IntOpenHashMap<Item> sampleTable(ServerLevel serverLevel, MinecraftServer server, TableTask task) {
        try {
            var lootTable = server.reloadableRegistries().getLootTable(task.key());
            if (lootTable == LootTable.EMPTY) return null;

            var lootParams = task.def().createLootParams(serverLevel);
            if (lootParams == null) return null;

            var counts = new Reference2IntOpenHashMap<Item>();
            boolean hasLoggedError = false;
            long baseSeed = task.id().hashCode();

            for (int i = 0; i < SIMULATION_COUNT; i++) {
                try {
                    var context = new LootContext.Builder(lootParams)
                            .withOptionalRandomSource(RandomSource.create(baseSeed + i)).create(Optional.empty());

                    var items = new ObjectArrayList<ItemStack>();
                    lootTable.getRandomItems(context, items::add);
                    for (var stack : items) if (!stack.isEmpty()) counts.addTo(stack.getItem(), stack.getCount());
                } catch (Exception e) {
                    if (!hasLoggedError) {
                        ComplexityAnalyzer.LOGGER.debug("[ULS] Error processing '{}': {} (suppressing further errors)", task.id(), e.getMessage());
                        hasLoggedError = true;
                    }
                }
            }
            return counts.isEmpty() ? null : counts;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[ULS] Sampling failed for '{}': {}", task.id(), e.toString());
            return null;
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
        var currentMap = this.allLootData;
        for (var map : currentMap.values()) if (map.containsKey(item)) return true;
        return false;
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        BaseResourceData best = null;
        var currentMap = this.allLootData;
        for (var sourceType : BaseResourceData.ResourceSourceType.values()) {
            var map = currentMap.get(sourceType);
            if (map == null) continue;
            var data = map.get(item);
            if (data != null && (best == null || data.getBaseFactor() < best.getBaseFactor())) best = data;
        }
        return best;
    }

    @Override
    public ObjectList<BaseResourceData> findAllSources(Item item) {
        var results = new ObjectArrayList<BaseResourceData>();
        var currentMap = this.allLootData;
        for (var sourceType : BaseResourceData.ResourceSourceType.values()) {
            var map = currentMap.get(sourceType);
            if (map == null) continue;
            var data = map.get(item);
            if (data != null) results.add(data);
        }
        results.sort(Comparator.comparingDouble(BaseResourceData::getBaseFactor).thenComparing(BaseResourceData::getSourceType));
        return results;
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

    private record TableTask(ResourceKey<LootTable> key, ResourceLocation id, LootContextDefinition def) {
    }

    private record TableResult(ResourceLocation id, LootContextDefinition def, Reference2IntOpenHashMap<Item> counts) {
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
}