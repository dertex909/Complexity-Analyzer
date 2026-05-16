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

package org.complexityanalyzer.geoscan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
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
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class GeoDatabase {

    private final GeoDataStorage storage;
    private final HeuristicAnalyzer analyzer;
    private final BiomeDataMapper mapper;

    private final ConcurrentHashMap<ResourceLocation, Map<ResourceLocation, BiomeScanData>> inMemoryData;
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

    public Map<ResourceLocation, LongOpenHashSet> loadAllReconChunkCoordinates() {
        return storage.loadAllReconChunkCoordinates();
    }

    public Map<ResourceLocation, Map<ResourceLocation, Path>> getAllReconFilePaths() {
        return storage.getAllReconFilePaths();
    }

    public void buildHeuristicFromFiles(Map<ResourceLocation, Map<ResourceLocation, Path>> reconFilePaths) {
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
        Map<ResourceLocation, Map<ResourceLocation, BiomeScanData>> loadedData = storage.loadAllFinalData(mapper);
        this.inMemoryData.putAll(loadedData);

        rebuildGlobalCache();
        ComplexityAnalyzer.LOGGER.info("Finished loading geo-data. Found data for {} dimensions.", inMemoryData.size());
    }

    public void saveBiomeData(ResourceLocation dimension, ResourceLocation biome, BiomeScanData data) {
        if (data == null || data.getChunksScanned() == 0) return;

        storage.saveFinalBiomeData(dimension, biome, data, mapper);

        Map<ResourceLocation, BiomeScanData> dimData = inMemoryData.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        BiomeScanData oldData = dimData.put(biome, data);
        updateGlobalCache(oldData, data);
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

    public Map<ResourceLocation, Map<ResourceLocation, BiomeScanData>> getAllDimensionData() {
        return Collections.unmodifiableMap(inMemoryData);
    }

    public Optional<BiomeScanData> getBiomeData(ResourceLocation dim, ResourceLocation biome) {
        return Optional.ofNullable(inMemoryData.get(dim)).map(dimData -> dimData.get(biome));
    }

    private void updateGlobalCache(BiomeScanData oldData, BiomeScanData newData) {
        if (oldData != null) {
            Reference2LongOpenHashMap<Block> counts = oldData.getInternalBlockCounts();
            ObjectIterator<Reference2LongMap.Entry<Block>> it = counts.reference2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                Reference2LongMap.Entry<Block> entry = it.next();
                Block block = entry.getKey();
                long value = entry.getLongValue();
                globalBlockCountsCache.computeIfPresent(block, (k, v) -> {
                    long newCount = v.addAndGet(-value);
                    return newCount > 0 ? v : null;
                });
                totalBlocksInCache.addAndGet(-value);
            }
        }
        if (newData != null) {
            Reference2LongOpenHashMap<Block> counts = newData.getInternalBlockCounts();
            ObjectIterator<Reference2LongMap.Entry<Block>> it = counts.reference2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                Reference2LongMap.Entry<Block> entry = it.next();
                Block block = entry.getKey();
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
        for (Map<ResourceLocation, BiomeScanData> dimMap : inMemoryData.values()) totalItems += dimMap.size();

        if (totalItems == 0) {
            ComplexityAnalyzer.LOGGER.info("Global block rarity cache is empty (no data loaded).");
            return;
        }

        ComplexityAnalyzer.LOGGER.debug("Rebuilding global cache from {} biome data entries...", totalItems);

        long processedItems = 0;
        int nextLogPercentage = 10;

        for (Map<ResourceLocation, BiomeScanData> dimMap : inMemoryData.values()) {
            for (BiomeScanData data : dimMap.values()) {
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
