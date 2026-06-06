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

package org.complexityanalyzer.geoscan.worldgen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.ComplexityAnalyzer;

public class ChunkGeneratorService {

    private final ServerLevel level;

    public ChunkGeneratorService(ServerLevel level) {
        this.level = level;
    }

    public ObjectArrayList<ChunkAccess> generateBatch(LongArrayList packedPositions) {
        int size = packedPositions.size();
        ObjectArrayList<ChunkAccess> results = new ObjectArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (Thread.currentThread().isInterrupted()) {
                results.add(null);
                continue;
            }
            long packed = packedPositions.getLong(i);
            results.add(generateChunkForAnalysis(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
        }
        return results;
    }

    public ChunkAccess generateChunkForAnalysis(int chunkX, int chunkZ) {
        try {
            var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FEATURES, true);
            if (chunk != null) chunk.setUnsaved(false);
            return chunk;
        } catch (IllegalStateException e) {
            ComplexityAnalyzer.LOGGER.debug("[VanillaGen] No chunk available for [{}, {}]: {}", chunkX, chunkZ, e.getMessage());
            return null;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[VanillaGen] Failed [{}, {}]: {}", chunkX, chunkZ, e.getMessage());
            return null;
        }
    }
}