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
import org.complexityanalyzer.geoscan.worldgen.UltraFastChunkGenerator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkBatchProcessor {
    private static final int MAX_TARGET_CHUNKS_PER_BATCH = 64;

    private final MinecraftServer server;
    private final ChunkAnalyzer analyzer;

    private final ConcurrentHashMap<ResourceKey<Level>, BiomeContext> biomeContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceKey<Level>, UltraFastChunkGenerator> generators = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, ResourceLocation> biomeCache = new ConcurrentHashMap<>();
    private static final int BIOME_CACHE_MAX_SIZE = 100_000;

    private record BiomeContext(BiomeSource biomeSource, Climate.Sampler sampler, int seaLevel) {
    }

    public record ScanResult(ChunkSnapshot snapshot, ResourceLocation biome) {
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

    private UltraFastChunkGenerator getGenerator(ResourceKey<Level> dimension) {
        return generators.computeIfAbsent(dimension, dim -> {
            ServerLevel level = server.getLevel(dim);
            if (level == null) throw new IllegalStateException("Level not found: " + dim);
            return new UltraFastChunkGenerator(level);
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

    public List<ScanResult> processBatch(
            ResourceKey<Level> dimension,
            List<ChunkPos> positions,
            ScanSession session
    ) {
        if (positions.isEmpty()) return Collections.emptyList();
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return Collections.emptyList();
        ResourceLocation dimId = dimension.location();
        BiomeContext ctx = getBiomeContext(dimension);
        int targetCount = Math.min(positions.size(), MAX_TARGET_CHUNKS_PER_BATCH);
        List<ChunkPos> toGenerate = new ArrayList<>(targetCount);
        Map<ChunkPos, ResourceLocation> biomeMap = new HashMap<>(targetCount * 2);

        for (ChunkPos pos : positions) {
            if (!session.isValid()) break;
            ResourceLocation biome = checkBiomeCached(ctx, dimension, pos);
            if (biome == null) continue;
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            toGenerate.add(pos);
            biomeMap.put(pos, biome);
            if (toGenerate.size() >= targetCount) break;
        }

        if (toGenerate.isEmpty()) return Collections.emptyList();

        UltraFastChunkGenerator generator = getGenerator(dimension);
        List<ChunkAccess> chunks = generator.generateBatch(toGenerate);

        List<ScanResult> results = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            ChunkAccess chunk = chunks.get(i);
            if (chunk == null || !session.isValid()) continue;
            ChunkPos pos = toGenerate.get(i);
            ResourceLocation biome = biomeMap.get(pos);
            if (session.doesNotNeedBiome(dimId, biome)) continue;
            ChunkSnapshot snapshot = analyzer.createSnapshot(chunk);
            if (snapshot != null) results.add(new ScanResult(snapshot, biome));
        }

        return results;
    }

    public void shutdown() {
        biomeContexts.clear();
        generators.clear();
        biomeCache.clear();
        UltraFastChunkGenerator.clearAllCaches();
    }

    public void resetForNewSession() {
        generators.clear();
        UltraFastChunkGenerator.clearAllCaches();
    }
}
