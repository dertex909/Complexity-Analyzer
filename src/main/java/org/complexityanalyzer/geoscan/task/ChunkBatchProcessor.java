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

package org.complexityanalyzer.geoscan.task;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.scan.ScanSession;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkBatchProcessor {

    private final MinecraftServer server;
    private final ChunkAnalyzer analyzer;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private volatile Semaphore generationPermits;

    public record ScanResult(ChunkSnapshot snapshot, ResourceLocation actualBiome) {
    }

    public ChunkBatchProcessor(MinecraftServer server) {
        this.server = server;
        this.analyzer = new ChunkAnalyzer();
        this.generationPermits = new Semaphore(4);
    }

    public void setProfile(ScanProfile profile) {
        this.generationPermits = new Semaphore(profile.maxParallelChunks);
    }

    public CompletableFuture<List<ScanResult>> processBatchAsync(
            ResourceKey<Level> dimension,
            List<ChunkPos> positions,
            ScanSession session
    ) {
        if (shutdown.get() || positions.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyList());

        ServerLevel level = server.getLevel(dimension);
        if (level == null) return CompletableFuture.completedFuture(Collections.emptyList());

        ResourceLocation dimId = dimension.location();
        AtomicInteger remaining = new AtomicInteger(positions.size());
        List<ScanResult> out = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<List<ScanResult>> allDone = new CompletableFuture<>();

        for (ChunkPos pos : positions) {
            if (shutdown.get() || !session.isValid()) {
                if (remaining.decrementAndGet() == 0) allDone.complete(out);
                continue;
            }

            submitOne(level, dimId, pos, session, out, remaining, allDone);
        }

        return allDone;
    }

    private void submitOne(
            ServerLevel level,
            ResourceLocation dimId,
            ChunkPos pos,
            ScanSession session,
            List<ScanResult> out,
            AtomicInteger remaining,
            CompletableFuture<List<ScanResult>> allDone
    ) {
        level.getChunkSource()
                .getChunkFuture(pos.x, pos.z, ChunkStatus.BIOMES, true)
                .thenCompose(biomeResult -> {
                    if (shutdown.get() || !session.isValid()) return CompletableFuture.completedFuture(null);

                    ChunkAccess biomeChunk = biomeResult.orElse(null);
                    if (biomeChunk == null) return CompletableFuture.completedFuture(null);

                    Set<ResourceLocation> allBiomes = getAllBiomesInChunk(biomeChunk);

                    boolean hasNeededBiome = false;
                    for (ResourceLocation biome : allBiomes) {
                        if (session.hasBiomeNeed(dimId, biome)) {
                            hasNeededBiome = true;
                            break;
                        }
                    }

                    if (!hasNeededBiome) return CompletableFuture.completedFuture(null);

                    CompletableFuture<ChunkAccess> future = new CompletableFuture<>();

                    CompletableFuture.runAsync(() -> {
                        try {
                            generationPermits.acquire();

                            if (shutdown.get() || !session.isValid()) {
                                generationPermits.release();
                                future.complete(null);
                                return;
                            }

                            server.execute(() -> {
                                try {
                                    ChunkAccess chunk = level.getChunk(pos.x, pos.z, ChunkStatus.FEATURES, true);
                                    future.complete(chunk);
                                } catch (Exception e) {
                                    future.complete(null);
                                } finally {
                                    generationPermits.release();
                                }
                            });
                        } catch (InterruptedException e) {
                            future.complete(null);
                            Thread.currentThread().interrupt();
                        }
                    });

                    return future;
                })
                .thenAcceptAsync(chunk -> {
                    try {
                        if (shutdown.get() || !session.isValid() || chunk == null) return;

                        Set<ResourceLocation> allBiomes = getAllBiomesInChunk(chunk);

                        ResourceLocation matchedBiome = null;
                        for (ResourceLocation biome : allBiomes) {
                            if (session.hasBiomeNeed(dimId, biome)) {
                                matchedBiome = biome;
                                break;
                            }
                        }

                        if (matchedBiome == null) return;
                        ChunkSnapshot snapshot = analyzer.createSnapshot(chunk);
                        if (snapshot == null) return;
                        out.add(new ScanResult(snapshot, matchedBiome));
                    } finally {
                        if (remaining.decrementAndGet() == 0) allDone.complete(out);
                    }
                }, server)
                .exceptionally(t -> {
                    if (remaining.decrementAndGet() == 0) allDone.complete(out);
                    return null;
                });
    }

    private Set<ResourceLocation> getAllBiomesInChunk(ChunkAccess chunk) {
        Set<ResourceLocation> biomes = new HashSet<>();

        try {
            int minSection = chunk.getMinSection();
            int maxSection = chunk.getMaxSection();

            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                int quartY = sectionY * 4 + 2;
                for (int qx = 0; qx < 4; qx++) {
                    for (int qz = 0; qz < 4; qz++) {
                        try {
                            chunk.getNoiseBiome(qx, quartY, qz)
                                    .unwrapKey()
                                    .map(ResourceKey::location)
                                    .ifPresent(biomes::add);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return biomes;
    }

    public void shutdown() {
        shutdown.set(true);
    }
}