package org.complexityanalyzer.geoscan.task;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChunkBatchProcessor {

    private final MinecraftServer server;
    private final ChunkAnalyzer analyzer;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public record ScanResult(ChunkSnapshot snapshot, ResourceLocation actualBiome) {
    }

    public ChunkBatchProcessor(MinecraftServer server) {
        this.server = server;
        this.analyzer = new ChunkAnalyzer();
    }

    public List<ScanResult> processBatchSync(
            ResourceKey<Level> dimension,
            List<ChunkPos> positions
    ) {
        if (shutdown.get() || positions.isEmpty()) return Collections.emptyList();

        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Collections.emptyList();

        List<ScanResult> results = new ArrayList<>(positions.size());

        for (ChunkPos pos : positions) {
            if (shutdown.get()) break;

            try {
                ChunkAccess chunk = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
                if (shutdown.get()) break;
                if (chunk == null) continue;
                ChunkSnapshot snapshot = analyzer.createSnapshot(chunk);
                if (snapshot == null) continue;
                ResourceLocation actualBiome = analyzer.getDominantBiome(chunk);
                results.add(new ScanResult(snapshot, actualBiome));
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("[BatchProcessor] Error processing chunk [{}, {}]", pos.x, pos.z, e);
            }
        }

        return results;
    }

    public void shutdown() {
        shutdown.set(true);
    }
}