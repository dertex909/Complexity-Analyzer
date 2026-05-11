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

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.util.ArrayList;
import java.util.List;

public class VanillaChunkGeneratorService {

    private final ServerLevel level;

    public VanillaChunkGeneratorService(ServerLevel level) {
        this.level = level;
    }

    public List<ChunkAccess> generateBatch(List<ChunkPos> positions) {
        List<ChunkAccess> results = new ArrayList<>(positions.size());
        for (ChunkPos pos : positions) {
            if (Thread.currentThread().isInterrupted()) {
                results.add(null);
                continue;
            }
            results.add(generateChunkForAnalysis(pos));
        }
        return results;
    }

    public ChunkAccess generateChunkForAnalysis(ChunkPos pos) {
        try {
            ChunkAccess chunk = level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FEATURES, true);
            if (chunk != null) chunk.setUnsaved(false);
            return chunk;
        } catch (IllegalStateException e) {
            ComplexityAnalyzer.LOGGER.debug("[VanillaGen] No chunk available for {}: {}", pos, e.getMessage());
            return null;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[VanillaGen] Failed {}: {}", pos, e.getMessage());
            return null;
        }
    }
}
