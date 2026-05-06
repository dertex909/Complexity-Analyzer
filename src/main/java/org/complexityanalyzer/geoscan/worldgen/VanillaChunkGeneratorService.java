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
