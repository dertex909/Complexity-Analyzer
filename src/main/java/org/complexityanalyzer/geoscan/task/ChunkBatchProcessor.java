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
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.scan.ScanSession;
import org.complexityanalyzer.geoscan.worldgen.VanillaChunkGeneratorService;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public class ChunkBatchProcessor {
    private final MinecraftServer server;
    private final ChunkAnalyzer analyzer;

    private final ConcurrentHashMap<ResourceKey<Level>, BiomeContext> biomeContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceKey<Level>, VanillaChunkGeneratorService> generators = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, ResourceLocation> biomeCache = new ConcurrentHashMap<>();
    private static final int BIOME_CACHE_MAX_SIZE = 100_000;

    private record BiomeContext(BiomeSource biomeSource, Climate.Sampler sampler, int seaLevel) {
    }

    public record ScanResult(ChunkSnapshot snapshot, ResourceLocation biome) {
    }

    public record LoadedChunk(ChunkAccess chunk, long packedPos, ResourceLocation biome) {
    }

    public ChunkBatchProcessor(MinecraftServer server) {
        this.server = server;
        this.analyzer = new ChunkAnalyzer();
    }

    private BiomeContext getBiomeContext(ResourceKey<Level> dimension) {
        return biomeContexts.computeIfAbsent(dimension, dim -> {
            ServerLevel level = server.getLevel(dim);
            if (level == null) throw new IllegalStateException("Level not found: " + dim);
            return new BiomeContext(
                    level.getChunkSource().getGenerator().getBiomeSource(),
                    level.getChunkSource().randomState().sampler(),
                    level.getSeaLevel()
            );
        });
    }

    private VanillaChunkGeneratorService getGenerator(ResourceKey<Level> dimension) {
        return generators.computeIfAbsent(dimension, dim -> {
            ServerLevel level = server.getLevel(dim);
            if (level == null) throw new IllegalStateException("Level not found: " + dim);
            return new VanillaChunkGeneratorService(level);
        });
    }

    private long cacheKey(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        int dimHash = dim.location().hashCode() & 0xFFFF;
        return ((long) dimHash << 48) | ((long) (chunkX & 0xFFFFFF) << 24) | (chunkZ & 0xFFFFFF);
    }

    private ResourceLocation checkBiomeCached(BiomeContext ctx, ResourceKey<Level> dim, int chunkX, int chunkZ) {
        long key = cacheKey(dim, chunkX, chunkZ);

        ResourceLocation cached = biomeCache.get(key);
        if (cached != null) return cached;

        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;

        try {
            Holder<Biome> biome = ctx.biomeSource.getNoiseBiome(blockX >> 2, ctx.seaLevel >> 2, blockZ >> 2, ctx.sampler);
            ResourceLocation result = biome.unwrapKey().map(ResourceKey::location).orElse(null);
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
            ResourceLocation cached = transientBiomeCache.get(key);
            if (cached != null) return cached;
        }

        ResourceLocation biome = checkBiomeCached(ctx, dimension, chunkX, chunkZ);
        if (biome != null && transientBiomeCache != null) transientBiomeCache.put(key, biome);
        return biome;
    }

    public ObjectArrayList<LoadedChunk> loadBatch(
            ResourceKey<Level> dimension,
            LongArrayList packedPositions,
            ScanSession session,
            @Nullable Long2ObjectMap<ResourceLocation> transientBiomeCache
    ) {
        int size = packedPositions.size();
        if (size == 0) return ObjectArrayList.of();
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return ObjectArrayList.of();
        ResourceLocation dimId = dimension.location();
        BiomeContext ctx = getBiomeContext(dimension);

        LongArrayList toGenerate = new LongArrayList(size);
        Long2ObjectOpenHashMap<ResourceLocation> biomeMap = new Long2ObjectOpenHashMap<>(size * 2);

        for (int i = 0; i < size; i++) {
            if (!session.isValid()) break;
            long packed = packedPositions.getLong(i);
            ResourceLocation biome = resolveBiome(ctx, dimension, packed, transientBiomeCache);
            if (biome == null) continue;
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            toGenerate.add(packed);
            biomeMap.put(packed, biome);
        }

        if (toGenerate.isEmpty()) return ObjectArrayList.of();

        VanillaChunkGeneratorService generator = getGenerator(dimension);
        ObjectArrayList<ChunkAccess> chunks = generator.generateBatch(toGenerate);

        int chunksSize = chunks.size();
        ObjectArrayList<LoadedChunk> loadedChunks = new ObjectArrayList<>(chunksSize);

        for (int i = 0; i < chunksSize; i++) {
            ChunkAccess chunk = chunks.get(i);
            if (chunk == null || !session.isValid()) continue;
            long packed = toGenerate.getLong(i);
            ResourceLocation biome = biomeMap.get(packed);
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            loadedChunks.add(new LoadedChunk(chunk, packed, biome));
        }

        return loadedChunks;
    }

    public ObjectArrayList<ScanResult> analyzeLoadedBatch(ObjectArrayList<LoadedChunk> loadedChunks, ScanSession session) {
        int size = loadedChunks.size();
        if (size == 0 || !session.isValid()) return ObjectArrayList.of();

        ObjectArrayList<ScanResult> results = new ObjectArrayList<>(size);

        for (int i = 0; i < size; i++) {
            if (!session.isValid()) break;
            LoadedChunk loadedChunk = loadedChunks.get(i);
            ChunkSnapshot snapshot = analyzer.createSnapshot(loadedChunk.chunk());
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
}