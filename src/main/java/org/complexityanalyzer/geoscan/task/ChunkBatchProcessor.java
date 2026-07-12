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

package org.complexityanalyzer.geoscan.task;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.scan.ScanSession;
import org.complexityanalyzer.geoscan.worldgen.ChunkGeneratorService;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public class ChunkBatchProcessor {
    private static final int BIOME_CACHE_MAX_SIZE = 100_000;
    private final MinecraftServer server;
    private final ChunkAnalyzer analyzer;
    private final ConcurrentHashMap<ResourceKey<Level>, BiomeContext> biomeContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceKey<Level>, ChunkGeneratorService> generators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ResourceLocation> biomeCache = new ConcurrentHashMap<>();

    public ChunkBatchProcessor(MinecraftServer server) {
        this.server = server;
        this.analyzer = new ChunkAnalyzer();
    }

    private BiomeContext getBiomeContext(ResourceKey<Level> dimension) {
        return biomeContexts.computeIfAbsent(dimension, dim -> {
            var level = server.getLevel(dim);
            if (level == null) throw new IllegalStateException("Level not found: " + dim);
            return new BiomeContext(
                    level.getChunkSource().getGenerator().getBiomeSource(),
                    level.getChunkSource().randomState().sampler(),
                    level.getSeaLevel()
            );
        });
    }

    private ChunkGeneratorService getGenerator(ResourceKey<Level> dimension) {
        return generators.computeIfAbsent(dimension, dim -> {
            var level = server.getLevel(dim);
            if (level == null) throw new IllegalStateException("Level not found: " + dim);
            return new ChunkGeneratorService(level);
        });
    }

    private long cacheKey(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        int dimHash = dim.location().hashCode() & 0xFFFF;
        return ((long) dimHash << 48) | ((long) (chunkX & 0xFFFFFF) << 24) | (chunkZ & 0xFFFFFF);
    }

    private ResourceLocation checkBiomeCached(BiomeContext ctx, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        long key = cacheKey(dim, chunkX, chunkZ);

        var cached = biomeCache.get(key);
        if (cached != null) return cached;

        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;

        try {
            var biome = ctx.biomeSource.getNoiseBiome(blockX >> 2, ctx.seaLevel >> 2, blockZ >> 2, ctx.sampler);
            var result = biome.unwrapKey().map(ResourceKey::location).orElse(null);
            if (result != null && biomeCache.size() < BIOME_CACHE_MAX_SIZE) biomeCache.put(key, result);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private ResourceLocation resolveBiome(BiomeContext ctx, ResourceKey<Level> dimension, long packedPos,
                                          @Nullable Long2ObjectMap<ResourceLocation> transientBiomeCache) {
        int chunkX = ChunkPos.getX(packedPos);
        int chunkZ = ChunkPos.getZ(packedPos);
        long key = cacheKey(dimension, chunkX, chunkZ);

        if (transientBiomeCache != null) {
            var cached = transientBiomeCache.get(key);
            if (cached != null) return cached;
        }

        var biome = checkBiomeCached(ctx, dimension, chunkX, chunkZ);
        if (biome != null && transientBiomeCache != null) transientBiomeCache.put(key, biome);
        return biome;
    }

    public ObjectArrayList<LoadedChunk> loadBatch(ResourceKey<Level> dimension, LongArrayList packedPositions,
                                                  ScanSession session, @Nullable Long2ObjectMap<ResourceLocation> transientBiomeCache) {
        int size = packedPositions.size();
        if (size == 0) return ObjectArrayList.of();
        var level = server.getLevel(dimension);
        if (level == null) return ObjectArrayList.of();
        var dimId = dimension.location();
        var ctx = getBiomeContext(dimension);

        var toGenerate = new LongArrayList(size);
        var biomeMap = new Long2ObjectOpenHashMap<ResourceLocation>(size * 2);

        for (int i = 0; i < size; i++) {
            if (!session.isValid()) break;
            long packed = packedPositions.getLong(i);
            var biome = resolveBiome(ctx, dimension, packed, transientBiomeCache);
            if (biome == null) continue;
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            toGenerate.add(packed);
            biomeMap.put(packed, biome);
        }

        if (toGenerate.isEmpty()) return ObjectArrayList.of();

        var generator = getGenerator(dimension);
        var chunks = generator.generateBatch(toGenerate);

        int chunksSize = chunks.size();
        var loadedChunks = new ObjectArrayList<LoadedChunk>(chunksSize);

        for (int i = 0; i < chunksSize; i++) {
            var chunk = chunks.get(i);
            if (chunk == null || !session.isValid()) continue;
            long packed = toGenerate.getLong(i);
            var biome = biomeMap.get(packed);
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            loadedChunks.add(new LoadedChunk(chunk, packed, biome));
        }

        return loadedChunks;
    }

    public ObjectArrayList<ScanResult> analyzeLoadedBatch(ObjectArrayList<LoadedChunk> loadedChunks, ScanSession session) {
        int size = loadedChunks.size();
        if (size == 0 || !session.isValid()) return ObjectArrayList.of();

        var results = new ObjectArrayList<ScanResult>(size);

        for (int i = 0; i < size; i++) {
            if (!session.isValid()) break;
            var loadedChunk = loadedChunks.get(i);
            var snapshot = analyzer.createSnapshot(loadedChunk.chunk());
            if (snapshot == null) continue;
            results.add(new ScanResult(snapshot, loadedChunk.biome()));
        }

        return results;
    }

    public void shutdown() {
        biomeContexts.clear();
        generators.clear();
        biomeCache.clear();
    }

    public void resetForNewSession() {
        generators.clear();
    }

    private record BiomeContext(BiomeSource biomeSource, Climate.Sampler sampler, int seaLevel) {
    }

    public record ScanResult(ChunkSnapshot snapshot, ResourceLocation biome) {
    }

    public record LoadedChunk(ChunkAccess chunk, long packedPos, ResourceLocation biome) {
    }
}