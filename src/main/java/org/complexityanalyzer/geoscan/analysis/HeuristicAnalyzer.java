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

package org.complexityanalyzer.geoscan.analysis;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.storage.GeoDataStorage;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class HeuristicAnalyzer {

    private static final int RECON_DIVERSITY_THRESHOLD = 200;
    private static final int REFINE_UNNATURAL_THRESHOLD = 64;
    private static final double NATURAL_BLOCK_RARITY_THRESHOLD = 0.00005;

    private final ConcurrentHashMap<ResourceLocation, ReferenceSet<Block>> dimensionalHeuristics = new ConcurrentHashMap<>();

    public boolean analyzeSnapshotForRecon(ChunkSnapshot snapshot) {
        int uniqueBlockTypes = snapshot.blockCounts().size();
        if (uniqueBlockTypes > RECON_DIVERSITY_THRESHOLD) {
            ComplexityAnalyzer.LOGGER.debug("Recon Pass: Skipping chunk [{}, {}]. Found {} unique block types (Threshold: {})",
                    snapshot.chunkX(), snapshot.chunkZ(), uniqueBlockTypes, RECON_DIVERSITY_THRESHOLD);
            return false;
        }
        return true;
    }

    public void buildHeuristics(Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>> reconFilePaths, GeoDataStorage storage) {
        dimensionalHeuristics.clear();

        var pathsByDimension = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<Path>>();
        for (var dimEntry : reconFilePaths.object2ObjectEntrySet()) {
            var list = pathsByDimension.computeIfAbsent(dimEntry.getKey(), k -> new ObjectArrayList<>());
            list.addAll(dimEntry.getValue().values());
        }

        for (var entry : pathsByDimension.object2ObjectEntrySet()) {
            var dimId = entry.getKey();
            var paths = entry.getValue();
            ComplexityAnalyzer.LOGGER.debug("Building heuristic for dimension: {}", dimId);

            var totalCounts = new Object2LongOpenHashMap<Block>();
            totalCounts.defaultReturnValue(0L);

            long totalBlocksInDim = 0;
            for (int i = 0, n = paths.size(); i < n; i++) {
                var path = paths.get(i);
                try (var snapshots = storage.streamReconFile(path)) {
                    var snapIt = snapshots.iterator();
                    while (snapIt.hasNext()) {
                        var snapshot = snapIt.next();
                        var entryIt = snapshot.blockCounts().object2IntEntrySet().fastIterator();
                        while (entryIt.hasNext()) {
                            var bcEntry = entryIt.next();
                            var block = GameRegistryManager.getBlock(ResourceLocation.parse(bcEntry.getKey()));
                            if (block == null) continue;
                            int count = bcEntry.getIntValue();
                            totalCounts.addTo(block, count);
                            totalBlocksInDim += count;
                        }
                    }
                }
            }

            var dimensionHeuristic = new ReferenceOpenHashSet<Block>();
            if (totalBlocksInDim > 0) {
                var it = totalCounts.object2LongEntrySet().fastIterator();
                while (it.hasNext()) {
                    var tcEntry = it.next();
                    var block = tcEntry.getKey();
                    long count = tcEntry.getLongValue();
                    if (block != Blocks.AIR && (double) count / totalBlocksInDim > NATURAL_BLOCK_RARITY_THRESHOLD) {
                        dimensionHeuristic.add(block);
                    }
                }
            }

            dimensionalHeuristics.put(dimId, dimensionHeuristic);
            ComplexityAnalyzer.LOGGER.info("Heuristic for {} built. Found {} common 'natural' blocks.",
                    dimId, dimensionHeuristic.size());
        }
    }

    public BiomeScanData refineRawData(Stream<ChunkSnapshot> snapshotStream, ResourceLocation dimensionId) {
        var finalCleanData = new BiomeScanData();
        var it = snapshotStream.iterator();
        while (it.hasNext()) {
            var snapshot = it.next();
            if (!isChunkCleanByHeuristic(snapshot, dimensionId)) continue;
            finalCleanData.addScannedChunk(snapshot.chunkX(), snapshot.chunkZ());

            var bcIt = snapshot.blockCounts().object2IntEntrySet().fastIterator();
            while (bcIt.hasNext()) {
                var entry = bcIt.next();
                var block = GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey()));
                if (block != null && block != Blocks.AIR && block != Blocks.BEDROCK) finalCleanData.addBlock(block, entry.getIntValue());
            }
        }
        return finalCleanData;
    }

    public void clear() {
        dimensionalHeuristics.clear();
    }

    private boolean isChunkCleanByHeuristic(ChunkSnapshot snapshot, ResourceLocation dimensionId) {
        if (dimensionalHeuristics.isEmpty()) return true;

        var heuristic = dimensionalHeuristics.get(dimensionId);
        if (heuristic == null) {
            ComplexityAnalyzer.LOGGER.warn("No heuristic found for dimension {}. Accepting chunk at [{}, {}] " +
                    "without filtering.", dimensionId, snapshot.chunkX(), snapshot.chunkZ());
            return true;
        }

        int unnaturalBlockCount = 0;
        var it = snapshot.blockCounts().object2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            var entry = it.next();
            var block = GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey()));
            if (block != null && block != Blocks.AIR && !heuristic.contains(block)) unnaturalBlockCount += entry.getIntValue();
            if (unnaturalBlockCount > REFINE_UNNATURAL_THRESHOLD) return false;
        }
        return true;
    }
}