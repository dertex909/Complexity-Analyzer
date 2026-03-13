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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.config.ScanConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorldScanner {

    private final MinecraftServer server;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile boolean fullWorldMode = false;

    private final ConcurrentHashMap<String, List<BlockPos>> biomeLocationCache = new ConcurrentHashMap<>();

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
        if (shouldStop()) return Optional.empty();
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Optional.empty();
        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(biomeKey)) return Optional.empty();
        String cacheKey = dimension.location() + "|" + biomeKey.location();
        if (isRelocation) return findNewLocation(level, biomeKey, cacheKey);
        List<BlockPos> cached = biomeLocationCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            BlockPos pos = cached.get(ThreadLocalRandom.current().nextInt(cached.size()));
            ComplexityAnalyzer.LOGGER.debug("Using cached location for {}: [{}, {}]",
                    biomeKey.location().getPath(), pos.getX(), pos.getZ());
            return Optional.of(new ChunkPos(pos));
        }

        return findNewLocation(level, biomeKey, cacheKey);
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
            ComplexityAnalyzer.LOGGER.debug("[WorldScanner] ✓ Found {} at [{}, {}]",
                    biomeKey.location(), result.getX(), result.getZ());
            cacheLocation(cacheKey, result);
            return Optional.of(new ChunkPos(result));
        }

        ComplexityAnalyzer.LOGGER.warn("[WorldScanner] ✗ Could not find {} within {} blocks",
                biomeKey.location(), radius);
        return Optional.empty();
    }

    private void cacheLocation(String cacheKey, BlockPos pos) {
        if (shouldStop()) return;
        biomeLocationCache
                .computeIfAbsent(cacheKey, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(pos);
    }

    public void shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return;
        stopRequested.set(true);
        biomeLocationCache.clear();
    }
}