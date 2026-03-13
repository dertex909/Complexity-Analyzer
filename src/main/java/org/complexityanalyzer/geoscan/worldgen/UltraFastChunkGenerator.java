package org.complexityanalyzer.geoscan.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.mixin.NoiseBasedChunkGeneratorAccessor;

import java.util.*;
import java.util.concurrent.*;

public class UltraFastChunkGenerator {

    private static final ConcurrentHashMap<String, OptimizedChunkCache> DIMENSION_CACHES = new ConcurrentHashMap<>();

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final StructureManager structureManager;
    private final OptimizedChunkCache cache;

    private final boolean isNoiseGenerator;
    private final NoiseBasedChunkGeneratorAccessor noiseAccessor;
    private final Holder<NoiseGeneratorSettings> noiseSettings;

    public UltraFastChunkGenerator(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.structureManager = level.structureManager();

        String dimensionKey = level.dimension().location().toString();
        this.cache = DIMENSION_CACHES.computeIfAbsent(dimensionKey, k -> new OptimizedChunkCache());

        this.isNoiseGenerator = generator instanceof NoiseBasedChunkGenerator;
        if (isNoiseGenerator) {
            this.noiseAccessor = (NoiseBasedChunkGeneratorAccessor) generator;
            this.noiseSettings = noiseAccessor.getSettings();
        } else {
            this.noiseAccessor = null;
            this.noiseSettings = null;
        }
    }

    public List<ChunkAccess> generateBatch(List<ChunkPos> positions) {
        if (positions.isEmpty()) return Collections.emptyList();

        IsolatedThreadMarker.markIsolated();

        try {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

            for (ChunkPos pos : positions) {
                minX = Math.min(minX, pos.x);
                maxX = Math.max(maxX, pos.x);
                minZ = Math.min(minZ, pos.z);
                maxZ = Math.max(maxZ, pos.z);
            }

            minX--;
            minZ--;
            maxX++;
            maxZ++;

            Map<Long, ChunkAccess> fullArea = generateAreaParallel(minX, minZ, maxX, maxZ);
            List<ChunkAccess> results = new ArrayList<>(positions.size());

            for (ChunkPos pos : positions) {
                ChunkAccess chunk = fullArea.get(pos.toLong());
                if (chunk == null) continue;

                ChunkAccess cached = cache.get(pos, ChunkStatus.FEATURES);
                if (cached != null) {
                    results.add(cached);
                    continue;
                }

                if (hasStatus(chunk, ChunkStatus.FEATURES)) {
                    results.add(chunk);
                    continue;
                }

                generateFeatures(chunk, fullArea);
                cache.put(pos, chunk, ChunkStatus.FEATURES);
                results.add(chunk);
            }

            return results;
        } finally {
            IsolatedThreadMarker.unmarkIsolated();
        }
    }

    private Map<Long, ChunkAccess> generateAreaParallel(int minX, int minZ, int maxX, int maxZ) {
        Map<Long, ChunkAccess> area = new ConcurrentHashMap<>();
        List<ChunkPos> toGenerate = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                ChunkAccess cached = cache.get(pos, ChunkStatus.SURFACE);
                if (cached != null) {
                    area.put(pos.toLong(), cached);
                } else {
                    toGenerate.add(pos);
                }
            }
        }

        if (toGenerate.isEmpty()) return area;

        runParallelPhase(toGenerate, pos -> area.put(pos.toLong(), getOrCreateChunk(pos)));

        runParallelPhase(toGenerate, pos -> {
            ChunkAccess chunk = area.get(pos.toLong());
            if (hasNotStatus(chunk, ChunkStatus.BIOMES)) generateBiomes(chunk);
        });

        runParallelPhase(toGenerate, pos -> {
            ChunkAccess chunk = area.get(pos.toLong());
            if (hasNotStatus(chunk, ChunkStatus.NOISE)) generateNoise(chunk);
        });

        runParallelPhase(toGenerate, pos -> {
            ChunkAccess chunk = area.get(pos.toLong());
            if (hasNotStatus(chunk, ChunkStatus.SURFACE)) {
                generateSurface(chunk, area);
                cache.put(pos, chunk, ChunkStatus.SURFACE);
            }
        });

        return area;
    }

    private void runParallelPhase(List<ChunkPos> items, java.util.function.Consumer<ChunkPos> task) {
        ForkJoinPool pool = ThreadPoolManager.getInstance().getForkJoinPool();
        CountDownLatch latch = new CountDownLatch(items.size());

        for (ChunkPos pos : items) {
            pool.execute(() -> {
                IsolatedThreadMarker.markIsolated();
                try {
                    task.accept(pos);
                } finally {
                    IsolatedThreadMarker.unmarkIsolated();
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (!completed) {
                ComplexityAnalyzer.LOGGER.warn("[UltraFast] Phase timeout! Not all chunks generated in 60s.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ComplexityAnalyzer.LOGGER.warn("[UltraFast] Phase generation interrupted.");
        }
    }

    private ChunkAccess getOrCreateChunk(ChunkPos pos) {
        ChunkAccess cached = cache.get(pos, ChunkStatus.EMPTY);
        if (cached != null) return cached;
        return createProtoChunk(pos);
    }

    private boolean hasStatus(ChunkAccess chunk, ChunkStatus status) {
        if (chunk instanceof ProtoChunk proto) return proto.getPersistedStatus().isOrAfter(status);
        return true;
    }

    private boolean hasNotStatus(ChunkAccess chunk, ChunkStatus status) {
        return !hasStatus(chunk, status);
    }

    private ProtoChunk createProtoChunk(ChunkPos pos) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
    }

    private void generateBiomes(ChunkAccess chunk) {
        try {
            chunk.fillBiomesFromNoise(generator.getBiomeSource(), randomState.sampler());
            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.BIOMES);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Biomes failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private void generateNoise(ChunkAccess chunk) {
        try {
            if (isNoiseGenerator && noiseAccessor != null && noiseSettings != null) {
                NoiseSettings noise = noiseSettings.value().noiseSettings()
                        .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());

                int minY = noise.minY();
                int cellHeight = noise.getCellHeight();
                int minCellY = Mth.floorDiv(minY, cellHeight);
                int cellCountY = Mth.floorDiv(noise.height(), cellHeight);

                if (cellCountY > 0) {
                    int topSectionIndex = chunk.getSectionIndex(cellCountY * cellHeight - 1 + minY);
                    int bottomSectionIndex = chunk.getSectionIndex(minY);
                    Set<LevelChunkSection> acquiredSections = new HashSet<>();

                    for (int i = topSectionIndex; i >= bottomSectionIndex; --i) {
                        LevelChunkSection section = chunk.getSection(i);
                        section.acquire();
                        acquiredSections.add(section);
                    }

                    try {
                        noiseAccessor.invokeDoFill(Blender.empty(), structureManager, randomState,
                                chunk, minCellY, cellCountY);
                    } finally {
                        for (LevelChunkSection section : acquiredSections) {
                            section.release();
                        }
                    }
                }
            } else {
                generator.fillFromNoise(Blender.empty(), randomState, structureManager, chunk).join();
            }
            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.NOISE);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Noise failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private void generateSurface(ChunkAccess chunk, Map<Long, ChunkAccess> area) {
        try {
            List<ChunkAccess> neighbors = new ArrayList<>(area.values());
            IsolatedWorldGenRegion region = new IsolatedWorldGenRegion(
                    level, chunk, neighbors, 1, ChunkStatus.SURFACE
            );
            generator.buildSurface(region, structureManager, randomState, chunk);
            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.SURFACE);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Surface failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private void generateFeatures(ChunkAccess chunk, Map<Long, ChunkAccess> area) {
        try {
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.OCEAN_FLOOR,
                    Heightmap.Types.WORLD_SURFACE
            ));

            List<ChunkAccess> neighbors = new ArrayList<>(area.values());
            IsolatedWorldGenRegion region = new IsolatedWorldGenRegion(
                    level, chunk, neighbors, 1, ChunkStatus.FEATURES
            );
            generator.applyBiomeDecoration(region, chunk, structureManager);

            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.FEATURES);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Features failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    public static void clearAllCaches() {
        DIMENSION_CACHES.values().forEach(OptimizedChunkCache::clear);
        DIMENSION_CACHES.clear();
    }
}