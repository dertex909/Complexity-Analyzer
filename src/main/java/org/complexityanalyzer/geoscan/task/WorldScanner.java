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

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WorldScanner {

    private final MinecraftServer server;
    private final Random random = new Random();

    private static final List<BlockPos> SEARCH_ORIGINS = List.of(
            BlockPos.ZERO, new BlockPos(5000, 64, 5000), new BlockPos(-5000, 64, 5000),
            new BlockPos(5000, 64, -5000), new BlockPos(-5000, 64, -5000)
    );
    private static final int SEARCH_RADIUS = 10000;
    private static final int RELOCATION_ATTEMPTS = 10;

    public WorldScanner(MinecraftServer server) {
        this.server = server;
    }

    public Optional<ChunkPos> findBiomeLocation(ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey, boolean isRelocation) {
        if (!server.isSameThread()) {
            CompletableFuture<Optional<ChunkPos>> future = new CompletableFuture<>();

            server.execute(() -> {
                try {
                    Optional<ChunkPos> result = findBiomeLocationInternal(dimension, biomeKey, isRelocation);
                    future.complete(result);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.error("Error finding biome location for {} in {}",
                            biomeKey.location(), dimension.location(), e);
                    future.complete(Optional.empty());
                }
            });

            try {
                return future.join();
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Failed to get biome location from main thread", e);
                return Optional.empty();
            }
        }

        return findBiomeLocationInternal(dimension, biomeKey, isRelocation);
    }

    private Optional<ChunkPos> findBiomeLocationInternal(ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey, boolean isRelocation) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot find biome location, level {} is not loaded.", dimension.location());
            return Optional.empty();
        }

        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(biomeKey)) {
            ComplexityAnalyzer.LOGGER.error("Cannot find biome location, biome {} is not registered.", biomeKey.location());
            return Optional.empty();
        }

        List<BlockPos> originsToTry = isRelocation ? generateRandomOrigins() : SEARCH_ORIGINS;

        for (BlockPos origin : originsToTry) {
            Pair<BlockPos, Holder<Biome>> foundResult = level.findClosestBiome3d(
                    holder -> holder.is(biomeKey), origin, SEARCH_RADIUS, 32, 64
            );
            if (foundResult != null) {
                BlockPos blockPos = foundResult.getFirst();
                return Optional.of(new ChunkPos(blockPos));
            }
        }

        return Optional.empty();
    }

    public void processChunk(ResourceKey<Level> dimension, ResourceKey<Biome> targetBiomeKey, ChunkPos pos,
                             BiConsumer<Optional<ChunkSnapshot>, Boolean> onComplete) {

        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            server.execute(() -> onComplete.accept(Optional.empty(), false));
            return;
        }
        ServerChunkCache chunkCache = level.getChunkSource();

        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(targetBiomeKey)) {
            server.execute(() -> onComplete.accept(Optional.empty(), false));
            return;
        }

        chunkCache.getChunkFuture(pos.x, pos.z, ChunkStatus.BIOMES, true)
                .thenCompose(either -> {
                    ChunkAccess chunk = either.orElse(null);
                    if (chunk == null || !isBiomePresentInChunk(chunk, targetBiomeKey)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return chunkCache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
                })
                .thenAccept(either -> {
                    if (either == null) {
                        server.execute(() -> onComplete.accept(Optional.empty(), false));
                        return;
                    }

                    ChunkAccess chunk = either.orElse(null);
                    if (chunk instanceof LevelChunk levelChunk) {
                        ChunkSnapshot snapshot = createSnapshot(levelChunk);
                        server.execute(() -> onComplete.accept(Optional.of(snapshot), true));
                    } else {
                        server.execute(() -> onComplete.accept(Optional.empty(), false));
                    }
                });
    }

    private boolean isBiomePresentInChunk(ChunkAccess chunk, ResourceKey<Biome> targetBiomeKey) {
        for (int qy = chunk.getMinSection() * 4; qy < chunk.getMaxSection() * 4; ++qy) {
            for (int qx = 0; qx < 4; ++qx) {
                for (int qz = 0; qz < 4; ++qz) {
                    if (chunk.getNoiseBiome(qx, qy, qz).is(targetBiomeKey)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ChunkSnapshot createSnapshot(LevelChunk chunk) {
        Map<String, Integer> counts = new HashMap<>();
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) continue;

            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        var state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            counts.merge(blockId, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, counts);
    }

    private List<BlockPos> generateRandomOrigins() {
        int searchDiameter = SEARCH_RADIUS * 2;
        return Stream.generate(() -> {
            int x = random.nextInt(searchDiameter * 2) - searchDiameter;
            int z = random.nextInt(searchDiameter * 2) - searchDiameter;
            return new BlockPos(x, 64, z);
        }).limit(RELOCATION_ATTEMPTS).collect(Collectors.toList());
    }
}