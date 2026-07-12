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

package org.complexityanalyzer.geoscan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.analysis.HeuristicAnalyzer;
import org.complexityanalyzer.geoscan.data.BiomeDataMapper;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.storage.GeoDataStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class GeoDatabase {

    private final GeoDataStorage storage;
    private final HeuristicAnalyzer analyzer;
    private final BiomeDataMapper mapper;

    private final ConcurrentHashMap<ResourceLocation, Object2ObjectMap<ResourceLocation, BiomeScanData>> inMemoryData;
    private final ConcurrentHashMap<Block, AtomicLong> globalBlockCountsCache = new ConcurrentHashMap<>();
    private final AtomicLong totalBlocksInCache = new AtomicLong(0);

    public GeoDatabase(MinecraftServer server) {
        this.storage = new GeoDataStorage(server);
        this.analyzer = new HeuristicAnalyzer();
        this.mapper = new BiomeDataMapper();
        this.storage.ensureDirectoriesExist();
        this.inMemoryData = new ConcurrentHashMap<>();
    }

    public ScanMetadata.ScanPhase getScanPhase() {
        return storage.loadMetadata().scanPhase();
    }

    public void setScanPhase(ScanMetadata.ScanPhase phase) {
        storage.saveMetadata(new ScanMetadata(phase));
    }

    public boolean analyzeSnapshotForRecon(ChunkSnapshot snapshot) {
        return analyzer.analyzeSnapshotForRecon(snapshot);
    }

    public void appendReconData(ResourceLocation dimension, ResourceLocation biome, ObjectArrayList<ChunkSnapshot> newSnapshots) {
        storage.appendReconData(dimension, biome, newSnapshots);
    }

    public int countReconChunks(ResourceLocation dimension, ResourceLocation biome) {
        return storage.countReconChunks(dimension, biome);
    }

    public Object2ObjectMap<ResourceLocation, LongOpenHashSet> loadAllReconChunkCoordinates() {
        return storage.loadAllReconChunkCoordinates();
    }

    public Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>> getAllReconFilePaths() {
        return storage.getAllReconFilePaths();
    }

    public void buildHeuristicFromFiles(Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>> reconFilePaths) {
        analyzer.buildHeuristics(reconFilePaths, storage);
    }

    public BiomeScanData refineRawDataFromStream(Stream<ChunkSnapshot> snapshotStream, ResourceLocation dimensionId) {
        return analyzer.refineRawData(snapshotStream, dimensionId);
    }

    public Stream<ChunkSnapshot> streamReconFile(Path path) {
        return storage.streamReconFile(path);
    }

    public void loadAll() {
        if (isLoaded()) return;
        ComplexityAnalyzer.LOGGER.info("Loading all final geo-data from disk...");

        this.inMemoryData.clear();
        var loadedData = storage.loadAllFinalData(mapper);
        this.inMemoryData.putAll(loadedData);
        rebuildGlobalCache();
        ComplexityAnalyzer.LOGGER.info("Finished loading geo-data. Found data for {} dimensions.", inMemoryData.size());
    }

    public void saveBiomeData(ResourceLocation dimension, ResourceLocation biome, BiomeScanData data) {
        if (data == null || data.getChunksScanned() == 0) return;
        storage.saveFinalBiomeData(dimension, biome, data, mapper);

        synchronized (this) {
            var dimData = inMemoryData.computeIfAbsent(dimension, k -> new Object2ObjectOpenHashMap<>());
            var oldData = dimData.put(biome, data);
            updateGlobalCache(oldData, data);
        }
    }

    public void clearFinalData() throws IOException {
        storage.deleteFinalData();
        inMemoryData.clear();
        rebuildGlobalCache();
    }

    public void clearAllData() {
        storage.deleteAllData();
        inMemoryData.clear();
        analyzer.clear();
        rebuildGlobalCache();
        ComplexityAnalyzer.LOGGER.info("Cleared all geo-data files and reset in-memory state.");
    }

    public void clear() {
        clearAllData();
    }

    public boolean isLoaded() {
        return !inMemoryData.isEmpty();
    }

    public Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, BiomeScanData>> getAllDimensionData() {
        var copy = new Object2ObjectOpenHashMap<>(inMemoryData);
        return Object2ObjectMaps.unmodifiable(copy);
    }

    public Optional<BiomeScanData> getBiomeData(ResourceLocation dim, ResourceLocation biome) {
        return Optional.ofNullable(inMemoryData.get(dim)).map(dimData -> dimData.get(biome));
    }

    private void updateGlobalCache(BiomeScanData oldData, BiomeScanData newData) {
        if (oldData != null) {
            var counts = oldData.getInternalBlockCounts();
            var it = counts.reference2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                var block = entry.getKey();
                if (block == null) continue;
                long value = entry.getLongValue();
                globalBlockCountsCache.computeIfPresent(block, (k, v) -> {
                    long newCount = v.addAndGet(-value);
                    return newCount > 0 ? v : null;
                });
                totalBlocksInCache.addAndGet(-value);
            }
        }
        if (newData != null) {
            var counts = newData.getInternalBlockCounts();
            var it = counts.reference2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                var block = entry.getKey();
                if (block == null) continue;
                long value = entry.getLongValue();
                globalBlockCountsCache.computeIfAbsent(block, k -> new AtomicLong(0)).addAndGet(value);
                totalBlocksInCache.addAndGet(value);
            }
        }
    }

    private void rebuildGlobalCache() {
        globalBlockCountsCache.clear();
        totalBlocksInCache.set(0);

        long totalItems = 0;
        for (var dimMap : inMemoryData.values()) totalItems += dimMap.size();

        if (totalItems == 0) {
            ComplexityAnalyzer.LOGGER.info("Global block rarity cache is empty (no data loaded).");
            return;
        }

        ComplexityAnalyzer.LOGGER.debug("Rebuilding global cache from {} biome data entries...", totalItems);

        long processedItems = 0;
        int nextLogPercentage = 10;

        for (var dimMap : inMemoryData.values()) {
            for (var data : dimMap.values()) {
                updateGlobalCache(null, data);
                processedItems++;

                double currentPercentage = ((double) processedItems / totalItems) * 100.0;
                if (currentPercentage >= nextLogPercentage) {
                    ComplexityAnalyzer.LOGGER.debug("Cache rebuild: {}% complete", nextLogPercentage);
                    nextLogPercentage += 10;
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("Global block rarity cache rebuilt. Total blocks counted: {}", totalBlocksInCache.get());
    }
}