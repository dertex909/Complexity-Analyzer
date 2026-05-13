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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.storage.GeoDataStorage;
import org.complexityanalyzer.core.GameRegistryManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HeuristicAnalyzer {

    private static final int RECON_DIVERSITY_THRESHOLD = 100;
    private static final int REFINE_UNNATURAL_THRESHOLD = 64;
    private static final double NATURAL_BLOCK_RARITY_THRESHOLD = 0.00005;

    private final Map<ResourceLocation, Set<Block>> dimensionalHeuristics = new ConcurrentHashMap<>();

    public boolean analyzeSnapshotForRecon(ChunkSnapshot snapshot) {
        int uniqueBlockTypes = snapshot.blockCounts().size();
        if (uniqueBlockTypes > RECON_DIVERSITY_THRESHOLD) {
            ComplexityAnalyzer.LOGGER.debug("Recon Pass: Skipping chunk [{}, {}]. Found {} unique block types (Threshold: {})",
                    snapshot.chunkX(), snapshot.chunkZ(), uniqueBlockTypes, RECON_DIVERSITY_THRESHOLD);
            return false;
        }
        return true;
    }

    public void buildHeuristics(Map<ResourceLocation, Map<ResourceLocation, Path>> reconFilePaths, GeoDataStorage storage) {
        dimensionalHeuristics.clear();

        Map<ResourceLocation, List<Path>> pathsByDimension = new ConcurrentHashMap<>();
        reconFilePaths.forEach((dimId, biomeMap) ->
                pathsByDimension.computeIfAbsent(dimId, k -> new ArrayList<>()).addAll(biomeMap.values()));

        pathsByDimension.forEach((dimId, paths) -> {
            ComplexityAnalyzer.LOGGER.debug("Building heuristic for dimension: {}", dimId);

            try (Stream<ChunkSnapshot> allSnapshotsInDim = paths.parallelStream().flatMap(storage::streamReconFile)) {

                Map<Block, Long> totalCounts = allSnapshotsInDim
                        .flatMap(snapshot -> snapshot.blockCounts().entrySet().stream())
                        .collect(Collectors.groupingBy(
                                entry -> GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey())),
                                Collectors.summingLong(Map.Entry::getValue)
                        ));

                long totalBlocksInDim = totalCounts.values().stream().mapToLong(Long::longValue).sum();

                Set<Block> dimensionHeuristic = ConcurrentHashMap.newKeySet();
                if (totalBlocksInDim > 0) {
                    totalCounts.forEach((block, count) -> {
                        if (block != null && block != Blocks.AIR && (double) count / totalBlocksInDim >
                                NATURAL_BLOCK_RARITY_THRESHOLD) dimensionHeuristic.add(block);
                    });
                }

                dimensionalHeuristics.put(dimId, dimensionHeuristic);
                ComplexityAnalyzer.LOGGER.info("Heuristic for {} built. Found {} common 'natural' blocks.",
                        dimId, dimensionHeuristic.size());
            }
        });
    }

    public BiomeScanData refineRawData(Stream<ChunkSnapshot> snapshotStream, ResourceLocation dimensionId) {
        BiomeScanData finalCleanData = new BiomeScanData();
        snapshotStream
                .filter(snapshot -> isChunkCleanByHeuristic(snapshot, dimensionId))
                .forEach(snapshot -> {
                    finalCleanData.addScannedChunk(snapshot.chunkX(), snapshot.chunkZ());
                    snapshot.blockCounts().forEach((blockId, count) -> {
                        Block block = GameRegistryManager.getBlock(ResourceLocation.parse(blockId));
                        if (block != Blocks.AIR && block != Blocks.BEDROCK)
                            finalCleanData.addBlock(block, count.longValue());
                    });
                });
        return finalCleanData;
    }

    public void clear() {
        dimensionalHeuristics.clear();
    }

    private boolean isChunkCleanByHeuristic(ChunkSnapshot snapshot, ResourceLocation dimensionId) {
        if (dimensionalHeuristics.isEmpty()) return true;

        Set<Block> heuristic = dimensionalHeuristics.get(dimensionId);
        if (heuristic == null) {
            ComplexityAnalyzer.LOGGER.warn("No heuristic found for dimension {}. Accepting chunk at [{}, {}] " +
                    "without filtering.", dimensionId, snapshot.chunkX(), snapshot.chunkZ());
            return true;
        }

        int unnaturalBlockCount = 0;
        for (Map.Entry<String, Integer> entry : snapshot.blockCounts().entrySet()) {
            Block block = GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey()));
            if (block != Blocks.AIR && !heuristic.contains(block)) unnaturalBlockCount += entry.getValue();
            if (unnaturalBlockCount > REFINE_UNNATURAL_THRESHOLD) return false;
        }
        return true;
    }
}