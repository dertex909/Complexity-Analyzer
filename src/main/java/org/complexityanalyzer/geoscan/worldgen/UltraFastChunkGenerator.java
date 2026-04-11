package org.complexityanalyzer.geoscan.worldgen;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.mixin.ChunkGeneratorAccessor;
import org.complexityanalyzer.mixin.NoiseBasedChunkGeneratorAccessor;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

public class UltraFastChunkGenerator {
    private static final int PHASE_TIMEOUT_SECONDS = 12;
    private static final int[] EMPTY_FEATURE_INDICES = new int[0];

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
    private final List<FeatureSorter.StepFeatureData> featureSteps;
    private final Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter;
    private final Set<Holder<Biome>> supportedBiomes;
    private final Map<Holder<Biome>, int[][]> biomeFeatureIndicesByStep;
    private final ConcurrentHashMap<Long, ReentrantLock> featureRegionLocks = new ConcurrentHashMap<>();

    private final boolean isNoiseGenerator;
    private final NoiseBasedChunkGeneratorAccessor noiseAccessor;
    private final Holder<NoiseGeneratorSettings> noiseSettings;

    private static final Set<String> DISABLED_FEATURES = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_FEATURE_FAILURES = ConcurrentHashMap.newKeySet();

    public UltraFastChunkGenerator(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.structureManager = new StructurelessStructureManager(level.structureManager());

        String dimensionKey = level.dimension().location().toString();
        this.cache = DIMENSION_CACHES.computeIfAbsent(dimensionKey, k -> new OptimizedChunkCache());
        this.generationSettingsGetter = ((ChunkGeneratorAccessor) generator).getGenerationSettingsGetter();
        this.featureSteps = buildFeatureSteps(generator);
        this.supportedBiomes = new HashSet<>(generator.getBiomeSource().possibleBiomes());
        this.biomeFeatureIndicesByStep = buildBiomeFeatureIndexCache();

        this.isNoiseGenerator = generator instanceof NoiseBasedChunkGenerator;
        if (isNoiseGenerator) {
            this.noiseAccessor = (NoiseBasedChunkGeneratorAccessor) generator;
            this.noiseSettings = noiseAccessor.getSettings();
        } else {
            this.noiseAccessor = null;
            this.noiseSettings = null;
        }
    }

    private List<FeatureSorter.StepFeatureData> buildFeatureSteps(ChunkGenerator chunkGenerator) {
        try {
            return FeatureSorter.buildFeaturesPerStep(
                    List.copyOf(chunkGenerator.getBiomeSource().possibleBiomes()),
                    biome -> generationSettingsGetter.apply(biome).features(),
                    true
            );
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[UltraFast] Failed to precompute feature steps: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<Holder<Biome>, int[][]> buildBiomeFeatureIndexCache() {
        if (featureSteps.isEmpty() || supportedBiomes.isEmpty()) return Collections.emptyMap();

        int maxSteps = Math.min(featureSteps.size(), GenerationStep.Decoration.values().length);
        Map<Holder<Biome>, int[][]> cache = new HashMap<>(supportedBiomes.size());

        for (Holder<Biome> biome : supportedBiomes) {
            List<HolderSet<PlacedFeature>> biomeFeatures = generationSettingsGetter.apply(biome).features();
            int[][] perStep = new int[maxSteps][];

            for (int step = 0; step < maxSteps; step++) {
                if (step >= biomeFeatures.size()) {
                    perStep[step] = EMPTY_FEATURE_INDICES;
                    continue;
                }

                FeatureSorter.StepFeatureData stepData = featureSteps.get(step);
                IntSet featureIndices = new IntArraySet();
                biomeFeatures.get(step).stream()
                        .map(Holder::value)
                        .mapToInt(stepData.indexMapping())
                        .filter(index -> index >= 0)
                        .forEach(featureIndices::add);

                if (featureIndices.isEmpty()) {
                    perStep[step] = EMPTY_FEATURE_INDICES;
                    continue;
                }

                int[] sortedIndices = featureIndices.toIntArray();
                Arrays.sort(sortedIndices);
                perStep[step] = sortedIndices;
            }

            cache.put(biome, perStep);
        }

        return cache;
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

            ensureAllPositionsPresent(allNeeded, area);

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

            if (!needFeatures.isEmpty()) runParallelPhase(needFeatures, pos -> {
                ChunkAccess chunk = cache.computeIfAbsent(pos, ChunkStatus.FEATURES, () -> {
                    ChunkAccess localChunk = area.get(pos.toLong());
                    if (localChunk != null && hasNotStatus(localChunk, ChunkStatus.FEATURES)) {
                        generateFeatures(localChunk, area);
                    }
                    return localChunk;
                });
                if (chunk != null) area.put(pos.toLong(), chunk);
            });


            List<ChunkAccess> results = new ArrayList<>(positions.size());
            for (ChunkPos pos : positions) {
                ChunkAccess chunk = area.get(pos.toLong());
                if (chunk != null) results.add(chunk);
            }

            return results;
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[UltraFast] Batch generation error: {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            IsolatedThreadMarker.unmarkIsolated();
        }
    }

    private void ensureAllPositionsPresent(Set<Long> allNeeded, Map<Long, ChunkAccess> area) {
        for (long key : allNeeded) {
            if (!area.containsKey(key)) {
                ChunkPos pos = new ChunkPos(key);
                area.put(key, createProtoChunk(pos));
            }
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

        List<ChunkPos> needTerrain = new ArrayList<>(toCreate.size());
        for (ChunkPos pos : toCreate) {
            ChunkAccess chunk = area.get(pos.toLong());
            if (chunk != null && (hasNotStatus(chunk, ChunkStatus.BIOMES) || hasNotStatus(chunk, ChunkStatus.NOISE))) {
                needTerrain.add(pos);
            }
        }
        if (!needTerrain.isEmpty()) runParallelPhase(needTerrain, pos -> generateTerrain(area.get(pos.toLong())));

        List<ChunkPos> needSurface = filterByMissingStatus(toCreate, area);
        if (!needSurface.isEmpty()) runParallelPhase(needSurface, pos -> {
            ChunkAccess chunk = cache.computeIfAbsent(pos, ChunkStatus.SURFACE, () -> {
                ChunkAccess localChunk = area.get(pos.toLong());
                if (localChunk != null) generateSurface(localChunk, area);
                return localChunk;
            });
            if (chunk != null) area.put(pos.toLong(), chunk);
        });

        return area;
    }

    private List<ChunkPos> filterByMissingStatus(List<ChunkPos> positions,
                                                 Map<Long, ChunkAccess> area) {
        List<ChunkPos> result = new ArrayList<>();
        for (ChunkPos pos : positions) {
            ChunkAccess chunk = area.get(pos.toLong());
            if (chunk != null && hasNotStatus(chunk, ChunkStatus.SURFACE)) result.add(pos);
        }
        return result;
    }

    private void runParallelPhase(List<ChunkPos> items,
                                  Consumer<ChunkPos> task) {
        if (items.isEmpty()) return;

        if (items.size() <= 3) {
            for (ChunkPos pos : items) {
                try {
                    task.accept(pos);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.trace("[UltraFast] Task failed for {}: {}", pos, e.getMessage());
                }
            }
            return;
        }

        CountDownLatch latch = getCountDownLatch(items, task);

        try {
            if (!latch.await(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) ComplexityAnalyzer.LOGGER.warn(
                    "[UltraFast] Phase timeout after {}s: {}/{} tasks still running",
                    PHASE_TIMEOUT_SECONDS, latch.getCount(), items.size()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static @NotNull CountDownLatch getCountDownLatch(List<ChunkPos> items, Consumer<ChunkPos> task) {
        ForkJoinPool pool = ThreadPoolManager.getInstance().getForkJoinPool();
        CountDownLatch latch = new CountDownLatch(items.size());

        for (ChunkPos pos : items) {
            pool.execute(() -> {
                IsolatedThreadMarker.markIsolated();
                try {
                    task.accept(pos);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.trace("[UltraFast] Parallel task failed for {}: {}", pos, e.getMessage());
                } finally {
                    IsolatedThreadMarker.unmarkIsolated();
                    latch.countDown();
                }
            });
        }
        return latch;
    }

    private ChunkAccess getOrCreateChunk(ChunkPos pos) {
        ChunkAccess cached = cache.get(pos, ChunkStatus.EMPTY);
        if (cached != null) return cached;
        return createProtoChunk(pos);
    }

    private boolean hasStatus(ChunkAccess chunk, ChunkStatus status) {
        if (chunk == null) return false;
        if (chunk instanceof ProtoChunk proto) {
            ChunkStatus persisted = proto.getPersistedStatus();
            return persisted.isOrAfter(status);
        }
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
            chunk.fillBiomesFromNoise(generator.getBiomeSource(), randomState.sampler());
            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.BIOMES);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Biomes failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private void generateTerrain(ChunkAccess chunk) {
        if (chunk == null) return;
        if (hasNotStatus(chunk, ChunkStatus.BIOMES)) generateBiomes(chunk);
        if (hasNotStatus(chunk, ChunkStatus.NOISE)) generateNoise(chunk);
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

                    int sectionCount = chunk.getSectionsCount();
                    topSection = Math.min(topSection, sectionCount - 1);
                    bottomSection = Math.max(bottomSection, 0);

                    if (topSection < 0 || bottomSection >= sectionCount) {
                        ComplexityAnalyzer.LOGGER.trace(
                                "[UltraFast] Invalid section indices for {}: top={}, bottom={}, count={}",
                                chunk.getPos(), topSection, bottomSection, sectionCount);
                        return;
                    }

                    Set<LevelChunkSection> acquired = new HashSet<>();

                    for (int i = topSection; i >= bottomSection; --i) {
                        if (i < sectionCount) {
                            LevelChunkSection section = chunk.getSection(i);
                            section.acquire();
                            acquired.add(section);
                        }
                    }

                    try {
                        noiseAccessor.invokeDoFill(Blender.empty(), structureManager, randomState, chunk, minCellY, cellCountY);
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
            IsolatedWorldGenRegion region = new IsolatedWorldGenRegion(level, chunk, neighbors, 1, ChunkStatus.SURFACE);
            generator.buildSurface(region, structureManager, randomState, chunk);
            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.SURFACE);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Surface failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private void generateFeatures(ChunkAccess chunk, Map<Long, ChunkAccess> area) {
        if (chunk == null) return;
        withFeatureRegionLocks(chunk.getPos(), () -> {
            try {
                Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                        Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE)
                );

                List<ChunkAccess> neighbors = collectNeighbors(chunk.getPos(), area);
                IsolatedWorldGenRegion region =
                        new IsolatedWorldGenRegion(level, chunk, neighbors, 1, ChunkStatus.FEATURES);
                applyBiomeDecorationSafely(region, chunk);

                if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.FEATURES);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.trace("[UltraFast] Features failed for {}: {}", chunk.getPos(), e.getMessage());
            }
        });
    }

    private void withFeatureRegionLocks(ChunkPos center, Runnable action) {
        long[] regionKeys = collectRegionKeys(center);
        ReentrantLock[] locks = new ReentrantLock[regionKeys.length];

        for (int i = 0; i < regionKeys.length; i++) {
            ReentrantLock lock = featureRegionLocks.computeIfAbsent(regionKeys[i], ignored -> new ReentrantLock());
            locks[i] = lock;
            lock.lock();
        }

        try {
            action.run();
        } finally {
            for (int i = locks.length - 1; i >= 0; i--) locks[i].unlock();
        }
    }

    private long[] collectRegionKeys(ChunkPos center) {
        long[] keys = new long[NEIGHBOR_OFFSETS.length];
        int count = 0;

        for (int[] offset : NEIGHBOR_OFFSETS) {
            keys[count++] = ChunkPos.asLong(center.x + offset[0], center.z + offset[1]);
        }

        Arrays.sort(keys, 0, count);

        int uniqueCount = 0;
        for (int i = 0; i < count; i++) if (i == 0 || keys[i] != keys[i - 1]) keys[uniqueCount++] = keys[i];

        return Arrays.copyOf(keys, uniqueCount);
    }

    private void applyBiomeDecorationSafely(IsolatedWorldGenRegion region, ChunkAccess centerChunk) {
        if (featureSteps.isEmpty()) {
            generator.applyBiomeDecoration(region, centerChunk, structureManager);
            return;
        }

        ChunkPos chunkPos = centerChunk.getPos();
        SectionPos sectionPos = SectionPos.of(chunkPos, region.getMinSection());
        BlockPos origin = sectionPos.origin();

        Set<Holder<Biome>> presentBiomes = new HashSet<>();

        ChunkPos.rangeClosed(sectionPos.chunk(), 1).forEach(pos -> {
            ChunkAccess chunk = region.getChunk(pos.x, pos.z, ChunkStatus.EMPTY, false);
            for (LevelChunkSection section : chunk.getSections()) section.getBiomes().getAll(presentBiomes::add);
        });
        presentBiomes.retainAll(supportedBiomes);

        Registry<PlacedFeature> featureRegistry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(region.getSeed(), origin.getX(), origin.getZ());

        int maxSteps = Math.min(featureSteps.size(), GenerationStep.Decoration.values().length);
        for (int step = 0; step < maxSteps; step++) {
            IntSet featureIndices = null;

            for (Holder<Biome> biome : presentBiomes) {
                int[][] perStepIndices = biomeFeatureIndicesByStep.get(biome);
                if (perStepIndices == null || step >= perStepIndices.length) continue;

                int[] cachedIndices = perStepIndices[step];
                if (cachedIndices.length == 0) continue;

                if (featureIndices == null) {
                    featureIndices = new IntArraySet(cachedIndices.length * Math.max(1, presentBiomes.size()));
                }

                for (int cachedIndex : cachedIndices) featureIndices.add(cachedIndex);
            }

            if (featureIndices == null || featureIndices.isEmpty()) continue;

            int[] sortedIndices = featureIndices.toIntArray();
            Arrays.sort(sortedIndices);

            FeatureSorter.StepFeatureData stepData = featureSteps.get(step);
            for (int featureIndex : sortedIndices) {
                PlacedFeature feature = stepData.features().get(featureIndex);
                ResourceLocation featureId = featureRegistry.getResourceKey(feature)
                        .map(net.minecraft.resources.ResourceKey::location).orElse(null);
                String featureName = featureId != null ? featureId.toString() : feature.toString();
                String featureKey = step + "|" + featureName;

                if (DISABLED_FEATURES.contains(featureKey)) continue;

                random.setFeatureSeed(decorationSeed, featureIndex, step);
                region.setCurrentlyGenerating(() -> featureName);

                try {
                    feature.placeWithBiomeCheck(region, generator, random, origin);
                } catch (Throwable t) {
                    DISABLED_FEATURES.add(featureKey);
                    logFeatureFailure(chunkPos, featureName, step, t);
                }
            }
        }

        region.setCurrentlyGenerating(null);
    }

    private void logFeatureFailure(ChunkPos chunkPos, String featureName, int step, Throwable error) {
        String failureKey = featureName + "|" + step;
        if (LOGGED_FEATURE_FAILURES.add(failureKey)) ComplexityAnalyzer.LOGGER.debug(
                "[UltraFast] Disabling failing feature {} at step {} after error in {}: {}",
                featureName,
                step,
                chunkPos,
                error.toString()
        );
    }

    private List<ChunkAccess> collectNeighbors(ChunkPos center, Map<Long, ChunkAccess> area) {
        List<ChunkAccess> neighbors = new ArrayList<>(9);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            int nx = center.x + offset[0];
            int nz = center.z + offset[1];
            long key = ChunkPos.asLong(nx, nz);
            ChunkAccess neighbor = area.get(key);

            ChunkPos neighborPos = new ChunkPos(nx, nz);
            if (neighbor == null || hasNotStatus(neighbor, ChunkStatus.SURFACE)) {
                ChunkAccess cached = cache.get(neighborPos, ChunkStatus.SURFACE);
                if (cached != null) {
                    neighbor = cached;
                    area.put(key, cached);
                } else if (neighbor == null) {
                    neighbor = createProtoChunk(neighborPos);
                    area.put(key, neighbor);
                }
            }
            neighbors.add(neighbor);
        }
        return neighbors;
    }

    public static void clearAllCaches() {
        DIMENSION_CACHES.values().forEach(OptimizedChunkCache::clear);
        DIMENSION_CACHES.clear();
        DISABLED_FEATURES.clear();
        LOGGED_FEATURE_FAILURES.clear();
    }
}