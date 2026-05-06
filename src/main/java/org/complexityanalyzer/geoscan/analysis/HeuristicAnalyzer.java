/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.storage.GeoDataStorage;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class HeuristicAnalyzer {

    private static final int RECON_DIVERSITY_THRESHOLD = 100;
    private static final int REFINE_UNNATURAL_THRESHOLD = 64;
    private static final double NATURAL_BLOCK_RARITY_THRESHOLD = 0.00005;

    private final Map<ResourceLocation, ReferenceSet<Block>> dimensionalHeuristics = new ConcurrentHashMap<>();
    private final Map<String, Block> blockCache = new ConcurrentHashMap<>();

    private Block getBlock(String id) {
        return blockCache.computeIfAbsent(id, k -> BuiltInRegistries.BLOCK.get(ResourceLocation.parse(k)));
    }

    public boolean analyzeSnapshotForRecon(ChunkSnapshot snapshot) {
        int uniqueBlockTypes = snapshot.blockCounts().size();
        if (uniqueBlockTypes > RECON_DIVERSITY_THRESHOLD) {
            ComplexityAnalyzer.LOGGER.debug("Recon Pass: Skipping chunk [{}, {}]. Found {} unique block types (Threshold: {})",
                    snapshot.chunkX(), snapshot.chunkZ(), uniqueBlockTypes, RECON_DIVERSITY_THRESHOLD);
            return false;
        }
        return true;
    }

    public void buildHeuristics(Object2ObjectMap<ResourceLocation,
            Object2ObjectOpenHashMap<ResourceLocation, Path>> reconFilePaths, GeoDataStorage storage) {
        dimensionalHeuristics.clear();

        Reference2ObjectMap<ResourceLocation, ObjectList<Path>> pathsByDimension = new Reference2ObjectOpenHashMap<>();
        reconFilePaths.object2ObjectEntrySet().forEach(entry -> {
            ResourceLocation dimId = entry.getKey();
            Object2ObjectOpenHashMap<ResourceLocation, Path> biomeMap = entry.getValue();
            pathsByDimension.computeIfAbsent(dimId, k -> new ObjectArrayList<>()).addAll(biomeMap.values());
        });

        pathsByDimension.forEach((dimId, paths) -> {
            ComplexityAnalyzer.LOGGER.debug("Building heuristic for dimension: {}", dimId);

            Reference2LongOpenHashMap<Block> totalCounts = new Reference2LongOpenHashMap<>();
            totalCounts.defaultReturnValue(0L);

            try (Stream<ChunkSnapshot> allSnapshotsInDim = paths.parallelStream().flatMap(storage::streamReconFile)) {
                allSnapshotsInDim.forEach(snapshot -> snapshot.blockCounts().forEach((blockId, count) -> {
                    Block block = getBlock(blockId);
                    if (block != Blocks.AIR) totalCounts.addTo(block, count);
                }));

                long totalBlocksInDim = 0;
                for (long count : totalCounts.values()) totalBlocksInDim += count;

                ReferenceSet<Block> dimensionHeuristic = new ReferenceOpenHashSet<>();
                if (totalBlocksInDim > 0) for (var entry : Reference2LongMaps.fastIterable(totalCounts)) {
                    if ((double) entry.getLongValue() / totalBlocksInDim > NATURAL_BLOCK_RARITY_THRESHOLD) {
                        dimensionHeuristic.add(entry.getKey());
                    }
                }

                dimensionalHeuristics.put(dimId, ReferenceSets.synchronize(dimensionHeuristic));
                ComplexityAnalyzer.LOGGER.info("Heuristic for {} built. Found {} common 'natural' blocks.",
                        dimId, dimensionHeuristic.size());
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Failed to build heuristics for dimension {}", dimId, e);
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
                        Block block = getBlock(blockId);
                        if (block != Blocks.AIR && block != Blocks.BEDROCK)
                            finalCleanData.addBlock(block, count);
                    });
                });
        return finalCleanData;
    }

    public void clear() {
        dimensionalHeuristics.clear();
    }

    public boolean hasHeuristics() {
        return !dimensionalHeuristics.isEmpty();
    }

    private boolean isChunkCleanByHeuristic(ChunkSnapshot snapshot, ResourceLocation dimensionId) {
        if (!hasHeuristics()) return true;

        ReferenceSet<Block> heuristic = dimensionalHeuristics.get(dimensionId);
        if (heuristic == null) {
            ComplexityAnalyzer.LOGGER.warn("No heuristic found for dimension {}. Accepting chunk at [{}, {}] " +
                    "without filtering.", dimensionId, snapshot.chunkX(), snapshot.chunkZ());
            return true;
        }

        int unnaturalBlockCount = 0;
        for (var entry : snapshot.blockCounts().entrySet()) {
            Block block = getBlock(entry.getKey());
            if (block != Blocks.AIR && !heuristic.contains(block)) unnaturalBlockCount += entry.getValue();
            if (unnaturalBlockCount > REFINE_UNNATURAL_THRESHOLD) return false;
        }
        return true;
    }
}