/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.geoscan.task;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Fast parallel chunk analyzer that uses Minecraft's built-in worldgen
 * thread pool with ChunkStatus.FEATURES (no world loading).
 *
 * Generates chunks with full terrain, caves, trees, ores — but never
 * converts them to LevelChunk or adds them to the live world.
 */
public class FastChunkAnalyzer {

    private final MinecraftServer server;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /**
     * Max concurrent chunk generation requests.
     * Higher = faster but more memory/CPU pressure.
     */
    private final int maxConcurrent;
    private final Semaphore semaphore;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger totalCompleted = new AtomicInteger(0);
    private final AtomicInteger totalFailed = new AtomicInteger(0);

    public FastChunkAnalyzer(MinecraftServer server) {
        this(server, Math.max(4, Runtime.getRuntime().availableProcessors() * 4));
    }

    public FastChunkAnalyzer(MinecraftServer server, int maxConcurrent) {
        this.server = server;
        this.maxConcurrent = maxConcurrent;
        this.semaphore = new Semaphore(maxConcurrent);
    }

    /**
     * Asynchronously generates and analyzes a single chunk.
     * Uses ChunkStatus.FEATURES — full generation without world loading.
     *
     * @param dimension      Target dimension
     * @param targetBiomeKey Biome to check for (chunk is discarded if biome not present)
     * @param pos            Chunk position
     * @param onComplete     Callback with (snapshot, success)
     */
    public void analyzeChunk(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> targetBiomeKey,
            ChunkPos pos,
            BiConsumer<Optional<ChunkSnapshot>, Boolean> onComplete
    ) {
        if (shutdownRequested.get()) {
            onComplete.accept(Optional.empty(), false);
            return;
        }

        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            onComplete.accept(Optional.empty(), false);
            return;
        }

        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(targetBiomeKey)) {
            onComplete.accept(Optional.empty(), false);
            return;
        }

        // Acquire permit (non-blocking check)
        if (!semaphore.tryAcquire()) {
            // Too many in-flight, wait in background
            CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    submitChunkGeneration(level, targetBiomeKey, pos, onComplete);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    onComplete.accept(Optional.empty(), false);
                }
            });
            return;
        }

        submitChunkGeneration(level, targetBiomeKey, pos, onComplete);
    }

    /**
     * Blocking version: generates and analyzes a chunk, waiting for result.
     *
     * @param dimension      Target dimension
     * @param targetBiomeKey Biome filter
     * @param pos            Chunk position
     * @param timeoutMs      Max wait time
     * @return Snapshot if successful
     */
    public Optional<ChunkSnapshot> analyzeChunkBlocking(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> targetBiomeKey,
            ChunkPos pos,
            long timeoutMs
    ) {
        if (shutdownRequested.get()) return Optional.empty();

        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Optional.empty();

        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(targetBiomeKey)) {
            return Optional.empty();
        }

        CompletableFuture<Optional<ChunkSnapshot>> future = new CompletableFuture<>();

        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        submitChunkGeneration(level, targetBiomeKey, pos, (snapshot, success) -> {
            future.complete(snapshot);
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Batch analyze multiple chunks with progress callback.
     *
     * @param dimension      Target dimension
     * @param targetBiomeKey Biome filter
     * @param positions      List of chunk positions
     * @param onEachComplete Called for each completed chunk (snapshot, success)
     * @param onAllComplete  Called when all chunks are done
     */
    public void analyzeBatch(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> targetBiomeKey,
            List<ChunkPos> positions,
            BiConsumer<Optional<ChunkSnapshot>, Boolean> onEachComplete,
            Runnable onAllComplete
    ) {
        if (shutdownRequested.get()) {
            onAllComplete.run();
            return;
        }

        AtomicInteger remaining = new AtomicInteger(positions.size());

        for (ChunkPos pos : positions) {
            if (shutdownRequested.get()) {
                // Complete remaining
                int left = remaining.getAndSet(0);
                if (left > 0) onAllComplete.run();
                return;
            }

            analyzeChunk(dimension, targetBiomeKey, pos, (snapshot, success) -> {
                onEachComplete.accept(snapshot, success);
                if (remaining.decrementAndGet() == 0) {
                    onAllComplete.run();
                }
            });
        }
    }

    private void submitChunkGeneration(
            ServerLevel level,
            ResourceKey<Biome> targetBiomeKey,
            ChunkPos pos,
            BiConsumer<Optional<ChunkSnapshot>, Boolean> onComplete
    ) {
        inFlight.incrementAndGet();

        // Step 1: Quick biome check with BIOMES status (lightweight)
        level.getChunkSource()
                .getChunkFuture(pos.x, pos.z, ChunkStatus.BIOMES, true)
                .thenCompose(biomeResult -> {
                    if (shutdownRequested.get()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    ChunkAccess biomeChunk = biomeResult.orElse(null);
                    if (biomeChunk == null || !isBiomePresentInChunk(biomeChunk, targetBiomeKey)) {
                        // Biome not here, skip expensive generation
                        return CompletableFuture.completedFuture(null);
                    }

                    // Step 2: Full generation up to FEATURES (NOT FULL!)
                    // This generates terrain, caves, trees, ores — but keeps it as ProtoChunk
                    return level.getChunkSource()
                            .getChunkFuture(pos.x, pos.z, ChunkStatus.FEATURES, true);
                })
                .thenAcceptAsync(featuresResult -> {
                    try {
                        if (shutdownRequested.get() || featuresResult == null) {
                            onComplete.accept(Optional.empty(), false);
                            totalFailed.incrementAndGet();
                            return;
                        }

                        ChunkAccess chunk = featuresResult.orElse(null);
                        if (chunk == null) {
                            onComplete.accept(Optional.empty(), false);
                            totalFailed.incrementAndGet();
                            return;
                        }

                        // Verify biome is still present after full generation
                        if (!isBiomePresentInChunk(chunk, targetBiomeKey)) {
                            onComplete.accept(Optional.empty(), false);
                            totalFailed.incrementAndGet();
                            return;
                        }

                        ChunkSnapshot snapshot = createSnapshot(chunk);
                        onComplete.accept(Optional.of(snapshot), true);
                        totalCompleted.incrementAndGet();

                    } finally {
                        inFlight.decrementAndGet();
                        semaphore.release();
                    }
                }, server)
                .exceptionally(throwable -> {
                    if (!shutdownRequested.get()) {
                        ComplexityAnalyzer.LOGGER.debug(
                                "Fast chunk analysis failed for [{}, {}]: {}",
                                pos.x, pos.z, throwable.getMessage()
                        );
                    }
                    inFlight.decrementAndGet();
                    semaphore.release();
                    totalFailed.incrementAndGet();
                    onComplete.accept(Optional.empty(), false);
                    return null;
                });
    }

    private boolean isBiomePresentInChunk(ChunkAccess chunk, ResourceKey<Biome> targetBiomeKey) {
        for (int qy = chunk.getMinSection() * 4; qy < chunk.getMaxSection() * 4; ++qy) {
            for (int qx = 0; qx < 4; ++qx) {
                for (int qz = 0; qz < 4; ++qz) {
                    if (chunk.getNoiseBiome(qx, qy, qz).is(targetBiomeKey)) return true;
                }
            }
        }
        return false;
    }

    private ChunkSnapshot createSnapshot(ChunkAccess chunk) {
        Map<String, Integer> counts = new HashMap<>();
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) continue;

            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        var state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            String blockId = BuiltInRegistries.BLOCK
                                    .getKey(state.getBlock()).toString();
                            counts.merge(blockId, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, counts);
    }

    // === Stats ===

    public int getInFlightCount() {
        return inFlight.get();
    }

    public int getTotalCompleted() {
        return totalCompleted.get();
    }

    public int getTotalFailed() {
        return totalFailed.get();
    }

    public void resetStats() {
        totalCompleted.set(0);
        totalFailed.set(0);
    }

    public void shutdown() {
        shutdownRequested.set(true);
    }

    public boolean isShutdown() {
        return shutdownRequested.get();
    }
}