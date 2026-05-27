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
import java.util.Iterator;
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

        Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<Path>> pathsByDimension = new Object2ObjectOpenHashMap<>();
        for (Object2ObjectMap.Entry<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>> dimEntry : reconFilePaths.object2ObjectEntrySet()) {
            ObjectArrayList<Path> list = pathsByDimension.computeIfAbsent(dimEntry.getKey(), k -> new ObjectArrayList<>());
            list.addAll(dimEntry.getValue().values());
        }

        for (Object2ObjectMap.Entry<ResourceLocation, ObjectArrayList<Path>> entry : pathsByDimension.object2ObjectEntrySet()) {
            ResourceLocation dimId = entry.getKey();
            ObjectArrayList<Path> paths = entry.getValue();
            ComplexityAnalyzer.LOGGER.debug("Building heuristic for dimension: {}", dimId);

            Object2LongOpenHashMap<Block> totalCounts = new Object2LongOpenHashMap<>();
            totalCounts.defaultReturnValue(0L);

            long totalBlocksInDim = 0;
            for (int i = 0, n = paths.size(); i < n; i++) {
                Path path = paths.get(i);
                try (Stream<ChunkSnapshot> snapshots = storage.streamReconFile(path)) {
                    Iterator<ChunkSnapshot> snapIt = snapshots.iterator();
                    while (snapIt.hasNext()) {
                        ChunkSnapshot snapshot = snapIt.next();
                        ObjectIterator<Object2IntMap.Entry<String>> entryIt = snapshot.blockCounts().object2IntEntrySet().fastIterator();
                        while (entryIt.hasNext()) {
                            Object2IntMap.Entry<String> bcEntry = entryIt.next();
                            Block block = GameRegistryManager.getBlock(ResourceLocation.parse(bcEntry.getKey()));
                            if (block == null) continue;
                            int count = bcEntry.getIntValue();
                            totalCounts.addTo(block, count);
                            totalBlocksInDim += count;
                        }
                    }
                }
            }

            ReferenceOpenHashSet<Block> dimensionHeuristic = new ReferenceOpenHashSet<>();
            if (totalBlocksInDim > 0) {
                ObjectIterator<Object2LongMap.Entry<Block>> it = totalCounts.object2LongEntrySet().fastIterator();
                while (it.hasNext()) {
                    Object2LongMap.Entry<Block> tcEntry = it.next();
                    Block block = tcEntry.getKey();
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
        BiomeScanData finalCleanData = new BiomeScanData();
        Iterator<ChunkSnapshot> it = snapshotStream.iterator();
        while (it.hasNext()) {
            ChunkSnapshot snapshot = it.next();
            if (!isChunkCleanByHeuristic(snapshot, dimensionId)) continue;
            finalCleanData.addScannedChunk(snapshot.chunkX(), snapshot.chunkZ());

            ObjectIterator<Object2IntMap.Entry<String>> bcIt = snapshot.blockCounts().object2IntEntrySet().fastIterator();
            while (bcIt.hasNext()) {
                Object2IntMap.Entry<String> entry = bcIt.next();
                Block block = GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey()));
                if (block != Blocks.AIR && block != Blocks.BEDROCK) {
                    finalCleanData.addBlock(block, entry.getIntValue());
                }
            }
        }
        return finalCleanData;
    }

    public void clear() {
        dimensionalHeuristics.clear();
    }

    private boolean isChunkCleanByHeuristic(ChunkSnapshot snapshot, ResourceLocation dimensionId) {
        if (dimensionalHeuristics.isEmpty()) return true;

        ReferenceSet<Block> heuristic = dimensionalHeuristics.get(dimensionId);
        if (heuristic == null) {
            ComplexityAnalyzer.LOGGER.warn("No heuristic found for dimension {}. Accepting chunk at [{}, {}] " +
                    "without filtering.", dimensionId, snapshot.chunkX(), snapshot.chunkZ());
            return true;
        }

        int unnaturalBlockCount = 0;
        ObjectIterator<Object2IntMap.Entry<String>> it = snapshot.blockCounts().object2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            Object2IntMap.Entry<String> entry = it.next();
            Block block = GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey()));
            if (block != Blocks.AIR && !heuristic.contains(block)) unnaturalBlockCount += entry.getIntValue();
            if (unnaturalBlockCount > REFINE_UNNATURAL_THRESHOLD) return false;
        }
        return true;
    }
}