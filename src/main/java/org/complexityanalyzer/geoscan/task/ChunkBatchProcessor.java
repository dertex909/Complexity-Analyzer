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

import java.util.*;
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

    public record LoadedChunk(ChunkAccess chunk, ChunkPos pos, ResourceLocation biome) {
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

    private long chunkKey(ResourceKey<Level> dim, ChunkPos pos) {
        int dimHash = dim.location().hashCode() & 0xFFFF;
        return ((long) dimHash << 48) | ((long) (pos.x & 0xFFFFFF) << 24) | (pos.z & 0xFFFFFF);
    }

    private ResourceLocation checkBiomeCached(BiomeContext ctx, ResourceKey<Level> dim, ChunkPos pos) {
        long key = chunkKey(dim, pos);

        ResourceLocation cached = biomeCache.get(key);
        if (cached != null) return cached;

        int blockX = (pos.x << 4) + 8;
        int blockZ = (pos.z << 4) + 8;

        try {
            Holder<Biome> biome = ctx.biomeSource.getNoiseBiome(blockX >> 2, ctx.seaLevel >> 2, blockZ >> 2, ctx.sampler);
            ResourceLocation result = biome.unwrapKey().map(ResourceKey::location).orElse(null);
            if (result != null && biomeCache.size() < BIOME_CACHE_MAX_SIZE) biomeCache.put(key, result);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private ResourceLocation resolveBiome(
            BiomeContext ctx,
            ResourceKey<Level> dimension,
            ChunkPos pos,
            @Nullable Map<Long, ResourceLocation> transientBiomeCache
    ) {
        long key = chunkKey(dimension, pos);

        if (transientBiomeCache != null) {
            ResourceLocation cached = transientBiomeCache.get(key);
            if (cached != null) return cached;
        }

        ResourceLocation biome = checkBiomeCached(ctx, dimension, pos);
        if (biome != null && transientBiomeCache != null) transientBiomeCache.put(key, biome);
        return biome;
    }

    public List<LoadedChunk> loadBatch(
            ResourceKey<Level> dimension,
            List<ChunkPos> positions,
            ScanSession session,
            @Nullable Map<Long, ResourceLocation> transientBiomeCache
    ) {
        if (positions.isEmpty()) return Collections.emptyList();
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Collections.emptyList();
        ResourceLocation dimId = dimension.location();
        BiomeContext ctx = getBiomeContext(dimension);
        List<ChunkPos> toGenerate = new ArrayList<>(positions.size());
        Map<ChunkPos, ResourceLocation> biomeMap = new HashMap<>(positions.size() * 2);

        for (ChunkPos pos : positions) {
            if (!session.isValid()) break;
            ResourceLocation biome = resolveBiome(ctx, dimension, pos, transientBiomeCache);
            if (biome == null) continue;
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            toGenerate.add(pos);
            biomeMap.put(pos, biome);
        }

        if (toGenerate.isEmpty()) return Collections.emptyList();

        VanillaChunkGeneratorService generator = getGenerator(dimension);
        session.recordChunksRequested(toGenerate.size());
        List<ChunkAccess> chunks = generator.generateBatch(toGenerate);

        List<LoadedChunk> loadedChunks = new ArrayList<>(chunks.size());
        int loaded = 0;

        for (int i = 0; i < chunks.size(); i++) {
            ChunkAccess chunk = chunks.get(i);
            if (chunk == null || !session.isValid()) continue;
            loaded++;
            ChunkPos pos = toGenerate.get(i);
            ResourceLocation biome = biomeMap.get(pos);
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            loadedChunks.add(new LoadedChunk(chunk, pos, biome));
        }

        session.recordChunksLoaded(loaded);

        return loadedChunks;
    }

    public List<ScanResult> analyzeLoadedBatch(List<LoadedChunk> loadedChunks, ScanSession session) {
        if (loadedChunks.isEmpty() || !session.isValid()) return Collections.emptyList();

        List<ScanResult> results = new ArrayList<>(loadedChunks.size());
        int snapshotted = 0;

        for (LoadedChunk loadedChunk : loadedChunks) {
            if (!session.isValid()) break;
            ChunkSnapshot snapshot = analyzer.createSnapshot(loadedChunk.chunk());
            if (snapshot == null) continue;
            snapshotted++;
            results.add(new ScanResult(snapshot, loadedChunk.biome()));
        }

        session.recordSnapshotsBuilt(snapshotted);
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
