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

    private static final int[][] NEIGHBOR_OFFSETS = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0}, {0, 0}, {1, 0},
            {-1, 1}, {0, 1}, {1, 1}
    };

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
            Set<Long> allNeeded = new LinkedHashSet<>(positions.size() * 9);

            for (ChunkPos pos : positions) {
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    allNeeded.add(ChunkPos.asLong(pos.x + offset[0], pos.z + offset[1]));
                }
            }

            Map<Long, ChunkAccess> area = generateExactChunks(allNeeded);

            List<ChunkPos> needFeatures = new ArrayList<>();

            for (ChunkPos pos : positions) {
                ChunkAccess chunk = area.get(pos.toLong());
                if (chunk == null) continue;

                ChunkAccess cached = cache.get(pos, ChunkStatus.FEATURES);
                if (cached != null) {
                    area.put(pos.toLong(), cached);
                    continue;
                }

                if (hasStatus(chunk, ChunkStatus.FEATURES)) continue;

                needFeatures.add(pos);
            }

            if (!needFeatures.isEmpty()) {
                runParallelPhase(needFeatures, pos -> {
                    ChunkAccess chunk = area.get(pos.toLong());
                    if (chunk != null && hasNotStatus(chunk, ChunkStatus.FEATURES)) {
                        generateFeatures(chunk, area);
                        cache.put(pos, chunk, ChunkStatus.FEATURES);
                    }
                });
            }

            List<ChunkAccess> results = new ArrayList<>(positions.size());
            for (ChunkPos pos : positions) {
                ChunkAccess chunk = area.get(pos.toLong());
                if (chunk != null) results.add(chunk);
            }

            return results;
        } finally {
            IsolatedThreadMarker.unmarkIsolated();
        }
    }

    private Map<Long, ChunkAccess> generateExactChunks(Set<Long> allNeeded) {
        Map<Long, ChunkAccess> area = new ConcurrentHashMap<>(allNeeded.size() * 2);
        List<ChunkPos> toCreate = new ArrayList<>();

        for (long key : allNeeded) {
            ChunkPos pos = new ChunkPos(key);

            ChunkAccess cached = cache.get(pos, ChunkStatus.SURFACE);
            if (cached != null) {
                area.put(key, cached);
            } else {
                toCreate.add(pos);
            }
        }

        if (toCreate.isEmpty()) return area;

        runParallelPhase(toCreate, pos -> area.put(pos.toLong(), getOrCreateChunk(pos)));

        List<ChunkPos> needBiomes = filterByMissingStatus(toCreate, area, ChunkStatus.BIOMES);
        if (!needBiomes.isEmpty()) runParallelPhase(needBiomes, pos -> generateBiomes(area.get(pos.toLong())));

        List<ChunkPos> needNoise = filterByMissingStatus(toCreate, area, ChunkStatus.NOISE);
        if (!needNoise.isEmpty()) runParallelPhase(needNoise, pos -> generateNoise(area.get(pos.toLong())));

        List<ChunkPos> needSurface = filterByMissingStatus(toCreate, area, ChunkStatus.SURFACE);
        if (!needSurface.isEmpty()) {
            runParallelPhase(needSurface, pos -> {
                ChunkAccess chunk = area.get(pos.toLong());
                if (chunk != null) {
                    generateSurface(chunk, area);
                    cache.put(pos, chunk, ChunkStatus.SURFACE);
                }
            });
        }

        return area;
    }

    private List<ChunkPos> filterByMissingStatus(List<ChunkPos> positions,
                                                 Map<Long, ChunkAccess> area,
                                                 ChunkStatus status) {
        List<ChunkPos> result = new ArrayList<>();
        for (ChunkPos pos : positions) {
            ChunkAccess chunk = area.get(pos.toLong());
            if (chunk != null && hasNotStatus(chunk, status)) result.add(pos);
        }
        return result;
    }

    private void runParallelPhase(List<ChunkPos> items,
                                  java.util.function.Consumer<ChunkPos> task) {
        if (items.isEmpty()) return;

        if (items.size() <= 3) {
            for (ChunkPos pos : items) {
                task.accept(pos);
            }
            return;
        }

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
            if (!latch.await(60, TimeUnit.SECONDS)) ComplexityAnalyzer.LOGGER.warn(
                    "[UltraFast] Phase timeout! Not all chunks generated in 60s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ChunkAccess getOrCreateChunk(ChunkPos pos) {
        ChunkAccess cached = cache.get(pos, ChunkStatus.EMPTY);
        if (cached != null) return cached;
        return createProtoChunk(pos);
    }

    private boolean hasStatus(ChunkAccess chunk, ChunkStatus status) {
        if (chunk == null) return false;
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
        if (chunk == null) return;
        try {
            chunk.fillBiomesFromNoise(generator.getBiomeSource(),
                    randomState.sampler());
            if (chunk instanceof ProtoChunk proto) {
                proto.setPersistedStatus(ChunkStatus.BIOMES);
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Biomes failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private void generateNoise(ChunkAccess chunk) {
        if (chunk == null) return;
        try {
            if (isNoiseGenerator && noiseAccessor != null && noiseSettings != null) {
                NoiseSettings noise = noiseSettings.value().noiseSettings()
                        .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());

                int minY = noise.minY();
                int cellHeight = noise.getCellHeight();
                int minCellY = Mth.floorDiv(minY, cellHeight);
                int cellCountY = Mth.floorDiv(noise.height(), cellHeight);

                if (cellCountY > 0) {
                    int topSection = chunk.getSectionIndex(cellCountY * cellHeight - 1 + minY);
                    int bottomSection = chunk.getSectionIndex(minY);
                    Set<LevelChunkSection> acquired = new HashSet<>();

                    for (int i = topSection; i >= bottomSection; --i) {
                        LevelChunkSection section = chunk.getSection(i);
                        section.acquire();
                        acquired.add(section);
                    }

                    try {
                        noiseAccessor.invokeDoFill(Blender.empty(), structureManager, randomState,
                                chunk, minCellY, cellCountY);
                    } finally {
                        for (LevelChunkSection section : acquired) {
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
        if (chunk == null) return;
        try {
            List<ChunkAccess> neighbors = collectNeighbors(chunk.getPos(), area);
            IsolatedWorldGenRegion region =
                    new IsolatedWorldGenRegion(level, chunk, neighbors, 1, ChunkStatus.SURFACE);
            generator.buildSurface(region, structureManager, randomState, chunk);
            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.SURFACE);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Surface failed for {}: {}",
                    chunk.getPos(), e.getMessage());
        }
    }

    private void generateFeatures(ChunkAccess chunk, Map<Long, ChunkAccess> area) {
        if (chunk == null) return;
        try {
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.OCEAN_FLOOR,
                    Heightmap.Types.WORLD_SURFACE
            ));

            List<ChunkAccess> neighbors = collectNeighbors(chunk.getPos(), area);
            IsolatedWorldGenRegion region =
                    new IsolatedWorldGenRegion(level, chunk, neighbors, 1, ChunkStatus.FEATURES);
            generator.applyBiomeDecoration(region, chunk, structureManager);

            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.FEATURES);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Features failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private List<ChunkAccess> collectNeighbors(ChunkPos center, Map<Long, ChunkAccess> area) {
        List<ChunkAccess> neighbors = new ArrayList<>(9);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            long key = ChunkPos.asLong(center.x + offset[0], center.z + offset[1]);
            ChunkAccess neighbor = area.get(key);
            if (neighbor != null) neighbors.add(neighbor);
        }
        return neighbors;
    }

    public static void clearAllCaches() {
        DIMENSION_CACHES.values().forEach(OptimizedChunkCache::clear);
        DIMENSION_CACHES.clear();
    }
}