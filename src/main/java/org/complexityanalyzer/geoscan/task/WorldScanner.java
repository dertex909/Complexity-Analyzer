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

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.config.ScanConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class WorldScanner {
    private static final int MAX_CACHED_LOCATIONS_PER_BIOME = 24;
    private static final int MIN_CACHED_LOCATION_DISTANCE_BLOCKS = 192;

    private final MinecraftServer server;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile boolean fullWorldMode = false;

    private final ConcurrentHashMap<String, List<BlockPos>> biomeLocationCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dimensionBiomeCache = new ConcurrentHashMap<>();

    public WorldScanner(MinecraftServer server) {
        this.server = server;
    }

    private boolean shouldStop() {
        return shutdownRequested.get() || stopRequested.get() || Thread.currentThread().isInterrupted();
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public void clearStopRequest() {
        stopRequested.set(false);
    }

    public void configureForScan(int chunksPerBiome) {
        this.fullWorldMode = chunksPerBiome > ScanConfig.FULL_WORLD_THRESHOLD;
        this.stopRequested.set(false);
    }

    public Optional<ChunkPos> findBiomeLocation(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> biomeKey,
            boolean isRelocation
    ) {
        return findBiomeLocation(dimension, biomeKey, isRelocation, true);
    }

    public Optional<ChunkPos> findBiomeLocation(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> biomeKey,
            boolean isRelocation,
            boolean allowCachedLocation
    ) {
        if (shouldStop()) return Optional.empty();
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Optional.empty();
        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(biomeKey)) return Optional.empty();

        if (!canBiomeGenerateInDimension(level, biomeKey)) {
            ComplexityAnalyzer.LOGGER.debug("[WorldScanner] Skipping {} - cannot generate in {}",
                    biomeKey.location(), dimension.location());
            return Optional.empty();
        }

        String cacheKey = dimension.location() + "|" + biomeKey.location();

        if (allowCachedLocation) {
            BlockPos cachedPos = getCachedLocation(cacheKey, isRelocation);
            if (cachedPos != null) {
                ComplexityAnalyzer.LOGGER.trace("Using cached location for {}: [{}, {}]",
                        biomeKey.location().getPath(), cachedPos.getX(), cachedPos.getZ());
                return Optional.of(new ChunkPos(cachedPos));
            }
        }

        return findNewLocation(level, biomeKey, cacheKey);
    }

    private boolean canBiomeGenerateInDimension(ServerLevel level, ResourceKey<Biome> biomeKey) {
        String dimensionId = level.dimension().location().toString();
        String biomeId = biomeKey.location().toString();

        Set<String> possibleBiomes = dimensionBiomeCache.computeIfAbsent(dimensionId, key -> {
            try {
                BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();

                Set<String> biomes = biomeSource.possibleBiomes().stream()
                        .map(holder -> holder.unwrapKey()
                                .map(k -> k.location().toString())
                                .orElse(null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (ComplexityAnalyzer.LOGGER.isDebugEnabled()) {
                    ComplexityAnalyzer.LOGGER.debug("[WorldScanner] Dimension {} has {} possible biomes",
                            dimensionId, biomes.size());
                }

                return biomes;
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("[WorldScanner] Failed to get biomes for {}: {}",
                        dimensionId, e.getMessage());
                return Collections.emptySet();
            }
        });

        return possibleBiomes.contains(biomeId);
    }

    public void recordDiscoveredBiomeChunk(ResourceKey<Level> dimension, ResourceLocation biome, ChunkPos pos) {
        if (shouldStop()) return;
        String cacheKey = dimension.location() + "|" + biome;
        BlockPos center = new BlockPos(pos.getMiddleBlockX(), 64, pos.getMiddleBlockZ());
        cacheLocation(cacheKey, center);
    }

    private Optional<ChunkPos> findNewLocation(ServerLevel level, ResourceKey<Biome> biomeKey, String cacheKey) {
        if (shouldStop()) return Optional.empty();

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int range = fullWorldMode ? 50000 : 20000;
        BlockPos origin = new BlockPos(random.nextInt(-range, range), 64, random.nextInt(-range, range));
        int radius = fullWorldMode ? ScanConfig.RADIUS_FULL_WORLD : ScanConfig.RADIUS_MAX;

        ComplexityAnalyzer.LOGGER.debug("[WorldScanner] Searching for {} from [{}, {}], radius={}",
                biomeKey.location(), origin.getX(), origin.getZ(), radius);

        BlockPos result = FastBiomeFinder.findBiome(level, holder -> holder.is(biomeKey), origin, radius);

        if (shouldStop()) return Optional.empty();

        if (result != null) {
            ComplexityAnalyzer.LOGGER.debug("[WorldScanner] Found {} at [{}, {}]",
                    biomeKey.location(), result.getX(), result.getZ());
            cacheLocation(cacheKey, result);
            return Optional.of(new ChunkPos(result));
        }

        ComplexityAnalyzer.LOGGER.warn("[WorldScanner] Could not find {} within {} blocks",
                biomeKey.location(), radius);
        return Optional.empty();
    }

    private BlockPos getCachedLocation(String cacheKey, boolean isRelocation) {
        List<BlockPos> cached = biomeLocationCache.get(cacheKey);
        if (cached == null) return null;

        synchronized (cached) {
            int size = cached.size();
            if (size == 0) return null;
            if (isRelocation && size < 2) return null;
            return cached.get(ThreadLocalRandom.current().nextInt(size));
        }
    }

    private void cacheLocation(String cacheKey, BlockPos pos) {
        if (shouldStop()) return;
        List<BlockPos> cached = biomeLocationCache
                .computeIfAbsent(cacheKey, k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (cached) {
            for (BlockPos existing : cached) {
                if (isNear(existing, pos)) {
                    return;
                }
                if (existing.getX() == pos.getX() && existing.getZ() == pos.getZ()) {
                    return;
                }
            }

            BlockPos immutable = pos.immutable();
            if (cached.size() >= MAX_CACHED_LOCATIONS_PER_BIOME) {
                cached.set(ThreadLocalRandom.current().nextInt(cached.size()), immutable);
            } else {
                cached.add(immutable);
            }
        }
    }

    private boolean isNear(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) < MIN_CACHED_LOCATION_DISTANCE_BLOCKS
                && Math.abs(a.getZ() - b.getZ()) < MIN_CACHED_LOCATION_DISTANCE_BLOCKS;
    }

    public void shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return;
        stopRequested.set(true);
        biomeLocationCache.clear();
        dimensionBiomeCache.clear();
    }
}
