package org.complexityanalyzer.geoscan.worldgen;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.block.state.BlockState;
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
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.task.ChunkAnalyzer;
import org.complexityanalyzer.mixin.ChunkGeneratorAccessor;
import org.complexityanalyzer.mixin.NoiseBasedChunkGeneratorAccessor;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;
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
    private final FeatureOrderSavedData featureOrderData;
    private final List<FeatureSorter.StepFeatureData> featureSteps;
    private final Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter;
    private final Set<Holder<Biome>> supportedBiomes;
    private final Map<Holder<Biome>, int[][]> biomeFeatureIndicesByStep;
    private final ConcurrentHashMap<Long, ReentrantLock> featureRegionLocks = new ConcurrentHashMap<>();

    private final boolean isNoiseGenerator;
    private final NoiseBasedChunkGeneratorAccessor noiseAccessor;
    private final Holder<NoiseGeneratorSettings> noiseSettings;

    private static final Set<String> LOGGED_FEATURE_FAILURES = ConcurrentHashMap.newKeySet();

    public enum DiagnosticPhase {
        TERRAIN("terrain"),
        SURFACE("surface"),
        CARVERS("carvers"),
        FEATURES("features");

        private final String id;

        DiagnosticPhase(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static DiagnosticPhase fromId(String id) {
            for (DiagnosticPhase phase : values()) {
                if (phase.id.equalsIgnoreCase(id)) return phase;
            }
            throw new IllegalArgumentException("Unknown diagnostic phase: " + id);
        }
    }

    public enum FeatureTraceStatus {
        APPLIED,
        SKIPPED_DISABLED,
        FAILED
    }

    public record FeatureTraceEntry(int originChunkX, int originChunkZ, int step, int featureIndex, String featureName,
                                    FeatureTraceStatus status, int changedBlockTypes, int absoluteDelta, int netDelta,
                                    Map<String, Integer> blockDeltas, String error) {
        public static FeatureTraceEntry applied(ChunkPos originPos,
                                                int step,
                                                int featureIndex,
                                                String featureName,
                                                int changedBlockTypes,
                                                int absoluteDelta,
                                                int netDelta,
                                                Map<String, Integer> blockDeltas) {
            return new FeatureTraceEntry(
                    originPos.x,
                    originPos.z,
                    step,
                    featureIndex,
                    featureName,
                    FeatureTraceStatus.APPLIED,
                    changedBlockTypes,
                    absoluteDelta,
                    netDelta,
                    Map.copyOf(blockDeltas),
                    null
            );
        }

        public static FeatureTraceEntry failed(ChunkPos originPos, int step, int featureIndex, String featureName,
                                               String error) {
            return new FeatureTraceEntry(
                    originPos.x,
                    originPos.z,
                    step,
                    featureIndex,
                    featureName,
                    FeatureTraceStatus.FAILED,
                    0,
                    0,
                    0,
                    Map.of(),
                    error
            );
        }

        public boolean changedCenterChunk() {
            return absoluteDelta > 0;
        }
    }

    public record FeatureTraceResult(ChunkAccess chunk, List<FeatureTraceEntry> entries) {
        public long changedEntries() {
            return entries.stream()
                    .filter(entry -> entry.status() == FeatureTraceStatus.APPLIED)
                    .filter(FeatureTraceEntry::changedCenterChunk).count();
        }

        public long failedEntries() {
            return entries.stream()
                    .filter(entry -> entry.status() == FeatureTraceStatus.FAILED).count();
        }

        public long skippedEntries() {
            return entries.stream()
                    .filter(entry -> entry.status() == FeatureTraceStatus.SKIPPED_DISABLED).count();
        }
    }

    public UltraFastChunkGenerator(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.structureManager = new StructurelessStructureManager(level.structureManager());

        String dimensionKey = level.dimension().location().toString();
        this.cache = DIMENSION_CACHES.computeIfAbsent(dimensionKey, k -> new OptimizedChunkCache());
        this.featureOrderData = FeatureOrderSavedData.get(level);
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
            return FeatureSorter.buildFeaturesPerStep(List.copyOf(chunkGenerator.getBiomeSource().possibleBiomes()),
                    biome -> generationSettingsGetter.apply(biome).features(), true
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
            List<ChunkPos> featureOrigins = orderFeatureOrigins(collectChunkArea(positions, 1));
            int supportRadius = positions.size() == 1 ? 8 : 2;
            Set<Long> workingKeys = new LinkedHashSet<>(collectChunkArea(positions, 2).stream()
                    .map(ChunkPos::toLong).toList());
            Set<Long> cacheableSurfaceKeys = new LinkedHashSet<>(positions.stream().map(ChunkPos::toLong).toList());
            Set<Long> allNeeded = new LinkedHashSet<>(collectChunkArea(positions, supportRadius).stream()
                    .map(ChunkPos::toLong).toList());

            Map<Long, ChunkAccess> area = generateExactChunks(allNeeded, cacheableSurfaceKeys);
            ensureAllPositionsPresent(allNeeded, area);
            List<ChunkPos> needFeatures = new ArrayList<>();
            List<ChunkPos> needCarvers = new ArrayList<>();

            for (long key : workingKeys) {
                ChunkPos pos = new ChunkPos(key);
                ChunkAccess chunk = area.get(key);
                if (chunk == null) continue;
                if (hasStatus(chunk, ChunkStatus.CARVERS)) continue;
                needCarvers.add(pos);
            }

            if (!needCarvers.isEmpty()) runParallelPhase(needCarvers, pos -> {
                ChunkAccess chunk = area.get(pos.toLong());
                if (chunk == null || hasStatus(chunk, ChunkStatus.CARVERS)) return;
                generateCarvers(chunk, area);
            });

            for (ChunkPos pos : featureOrigins) {
                ChunkAccess chunk = area.get(pos.toLong());
                if (chunk == null) continue;
                if (hasStatus(chunk, ChunkStatus.FEATURES)) continue;
                needFeatures.add(pos);
            }

            for (ChunkPos pos : needFeatures) {
                ChunkAccess chunk = area.get(pos.toLong());
                if (chunk != null && hasNotStatus(chunk, ChunkStatus.FEATURES)) generateFeatures(chunk, area);
            }

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

    public ChunkAccess generateDiagnosticChunk(ChunkPos centerPos, DiagnosticPhase phase) {
        IsolatedThreadMarker.markIsolated();

        try {
            int workingRadius = phase == DiagnosticPhase.FEATURES ? 2 : 1;
            int supportRadius = switch (phase) {
                case CARVERS, FEATURES -> 8;
                default -> 1;
            };
            List<ChunkPos> allNeededPositions = collectChunkArea(List.of(centerPos), supportRadius);
            List<ChunkPos> featureOrigins = phase == DiagnosticPhase.FEATURES
                    ? collectChunkArea(List.of(centerPos), 1) : List.of(centerPos);
            Set<Long> workingKeys = new LinkedHashSet<>(collectChunkArea(List.of(centerPos), workingRadius).stream()
                    .map(ChunkPos::toLong).toList());
            Set<Long> allNeeded = new LinkedHashSet<>(allNeededPositions.stream().map(ChunkPos::toLong).toList());

            Map<Long, ChunkAccess> area = new ConcurrentHashMap<>(allNeeded.size() * 2);
            List<ChunkPos> positions = new ArrayList<>(allNeeded.size());

            for (long key : allNeeded) {
                ChunkPos pos = new ChunkPos(key);
                positions.add(pos);
                area.put(key, createProtoChunk(pos));
            }

            runParallelPhase(positions, pos -> generateTerrain(area.get(pos.toLong())));
            if (phase == DiagnosticPhase.TERRAIN) return area.get(centerPos.toLong());

            runParallelPhase(positions, pos -> generateSurface(area.get(pos.toLong()), area, false));
            if (phase == DiagnosticPhase.SURFACE) return area.get(centerPos.toLong());

            List<ChunkPos> carverTargets = new ArrayList<>(workingKeys.size());
            for (long key : workingKeys) {
                carverTargets.add(new ChunkPos(key));
            }
            runParallelPhase(carverTargets, pos -> generateCarvers(area.get(pos.toLong()), area));
            if (phase == DiagnosticPhase.CARVERS) return area.get(centerPos.toLong());

            for (ChunkPos featureOrigin : featureOrigins) {
                ChunkAccess originChunk = area.get(featureOrigin.toLong());
                if (originChunk != null) generateFeatures(originChunk, area, false);
            }
            return area.get(centerPos.toLong());
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[UltraFast] Diagnostic generation failed for {} at {}: {}",
                    centerPos, phase.id(), e.getMessage());
            return null;
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

    private Map<Long, ChunkAccess> generateExactChunks(Set<Long> allNeeded, Set<Long> cacheableSurfaceKeys) {
        Map<Long, ChunkAccess> area = new ConcurrentHashMap<>(allNeeded.size() * 2);
        List<ChunkPos> toCreate = new ArrayList<>();

        for (long key : allNeeded) {
            ChunkPos pos = new ChunkPos(key);

            OptimizedChunkCache.CacheEntry cached = cache.getEntry(pos, ChunkStatus.SURFACE);
            if (cached != null) {
                area.put(key, cloneChunkForWork(cached.chunk, cached.status));
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
            ChunkAccess chunk = area.get(pos.toLong());
            if (chunk == null || hasStatus(chunk, ChunkStatus.SURFACE)) return;

            generateSurface(chunk, area);
            if (cacheableSurfaceKeys.contains(pos.toLong())) cache.put(pos,
                    cloneChunkForWork(chunk, ChunkStatus.SURFACE), ChunkStatus.SURFACE);
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

    private ChunkAccess cloneChunkForWork(ChunkAccess source, ChunkStatus trustedStatus) {
        LevelChunkSection[] sectionCopies = cloneSections(source);
        ProtoChunk clone = new ProtoChunk(
                source.getPos(),
                source.getUpgradeData(),
                sectionCopies,
                new ProtoChunkTicks<>(),
                new ProtoChunkTicks<>(),
                level,
                level.registryAccess().registryOrThrow(Registries.BIOME),
                source.getBlendingData()
        );

        clone.setPersistedStatus(trustedStatus);
        clone.setInhabitedTime(source.getInhabitedTime());
        clone.setLightCorrect(source.isLightCorrect());

        copyHeightmaps(source, clone);
        copyStructures(source, clone);
        copyPostProcessing(source, clone);

        if (source instanceof ProtoChunk protoSource) copyProtoChunkState(protoSource, clone);

        return clone;
    }

    private LevelChunkSection[] cloneSections(ChunkAccess source) {
        LevelChunkSection[] sourceSections = source.getSections();
        LevelChunkSection[] copies = new LevelChunkSection[sourceSections.length];

        for (int i = 0; i < sourceSections.length; i++) {
            LevelChunkSection section = sourceSections[i];
            if (section == null) continue;

            copies[i] = new LevelChunkSection(
                    copyStateContainer(section.getStates()), copyBiomeContainer(section.getBiomes())
            );
        }

        return copies;
    }

    private PalettedContainer<BlockState> copyStateContainer(PalettedContainer<BlockState> sourceStates) {
        PalettedContainer<BlockState> copy = sourceStates.recreate();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    copy.getAndSetUnchecked(x, y, z, sourceStates.get(x, y, z));
                }
            }
        }

        return copy;
    }

    private PalettedContainer<Holder<Biome>> copyBiomeContainer(PalettedContainerRO<Holder<Biome>> sourceBiomes) {
        PalettedContainer<Holder<Biome>> copy = sourceBiomes.recreate();

        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    copy.getAndSetUnchecked(x, y, z, sourceBiomes.get(x, y, z));
                }
            }
        }

        return copy;
    }

    private void copyHeightmaps(ChunkAccess source, ChunkAccess target) {
        for (Map.Entry<Heightmap.Types, Heightmap> entry : source.getHeightmaps()) {
            target.setHeightmap(entry.getKey(), entry.getValue().getRawData().clone());
        }
    }

    private void copyStructures(ChunkAccess source, ChunkAccess target) {
        target.setAllStarts(new HashMap<>(source.getAllStarts()));

        Map<Structure, LongSet> copiedReferences = new HashMap<>();
        for (Map.Entry<Structure, LongSet> entry : source.getAllReferences().entrySet()) {
            copiedReferences.put(entry.getKey(), new LongOpenHashSet(entry.getValue()));
        }
        target.setAllReferences(copiedReferences);
    }

    private void copyPostProcessing(ChunkAccess source, ProtoChunk target) {
        ShortList[] postProcessing = source.getPostProcessing();
        for (int sectionIndex = 0; sectionIndex < postProcessing.length; sectionIndex++) {
            ShortList offsets = postProcessing[sectionIndex];
            if (offsets == null || offsets.isEmpty()) continue;
            for (int i = 0; i < offsets.size(); i++) target.addPackedPostProcess(offsets.getShort(i), sectionIndex);
        }
    }

    private void copyProtoChunkState(ProtoChunk source, ProtoChunk target) {
        for (Map.Entry<BlockPos, CompoundTag> entry : source.getBlockEntityNbts().entrySet()) {
            target.setBlockEntityNbt(entry.getValue().copy());
        }

        for (CompoundTag entityTag : source.getEntities()) target.addEntity(entityTag.copy());
        if (source.getBelowZeroRetrogen() != null) target.setBelowZeroRetrogen(source.getBelowZeroRetrogen());
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
                        for (LevelChunkSection section : acquired) section.release();
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
        generateSurface(chunk, area, true);
    }

    private void generateSurface(ChunkAccess chunk, Map<Long, ChunkAccess> area, boolean allowCacheReads) {
        if (chunk == null) return;
        try {
            List<ChunkAccess> neighbors = collectNeighbors(chunk.getPos(), area, allowCacheReads);
            IsolatedWorldGenRegion region = new IsolatedWorldGenRegion(level, chunk, neighbors, 1, ChunkStatus.SURFACE);
            generator.buildSurface(region, structureManager, randomState, chunk);
            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.SURFACE);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Surface failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    public FeatureTraceResult traceFeatureGeneration(ChunkPos centerPos) {
        IsolatedThreadMarker.markIsolated();

        try {
            List<ChunkPos> allNeededPositions = collectChunkArea(List.of(centerPos), 2);
            List<ChunkPos> featureOrigins = collectChunkArea(List.of(centerPos), 1);
            Set<Long> allNeeded = new LinkedHashSet<>(allNeededPositions.stream().map(ChunkPos::toLong).toList());

            Map<Long, ChunkAccess> area = new ConcurrentHashMap<>(allNeeded.size() * 2);
            List<ChunkPos> positions = new ArrayList<>(allNeeded.size());

            for (long key : allNeeded) {
                ChunkPos pos = new ChunkPos(key);
                positions.add(pos);
                area.put(key, createProtoChunk(pos));
            }

            runParallelPhase(positions, pos -> generateTerrain(area.get(pos.toLong())));
            runParallelPhase(positions, pos -> generateSurface(area.get(pos.toLong()), area, false));
            runParallelPhase(positions, pos -> generateCarvers(area.get(pos.toLong()), area));

            ChunkAccess centerChunk = area.get(centerPos.toLong());
            if (centerChunk == null) return new FeatureTraceResult(null, List.of());

            List<FeatureTraceEntry> entries = new ArrayList<>();
            for (ChunkPos featureOrigin : featureOrigins) {
                ChunkAccess originChunk = area.get(featureOrigin.toLong());
                if (originChunk != null) entries.addAll(traceFeatures(originChunk, area));
            }

            return new FeatureTraceResult(centerChunk, List.copyOf(entries));
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[UltraFast] Feature trace failed for {}: {}", centerPos, e.getMessage());
            return new FeatureTraceResult(null, List.of());
        } finally {
            IsolatedThreadMarker.unmarkIsolated();
        }
    }

    private void generateCarvers(ChunkAccess chunk, Map<Long, ChunkAccess> area) {
        if (chunk == null) return;
        try {
            IsolatedWorldGenRegion region = new IsolatedWorldGenRegion(
                    level, chunk, new ArrayList<>(area.values()), 8, ChunkStatus.CARVERS
            );

            for (GenerationStep.Carving carving : GenerationStep.Carving.values()) {
                generator.applyCarvers(region, level.getSeed(), randomState,
                        region.getBiomeManager(), structureManager, chunk, carving
                );
            }

            if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.CARVERS);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace("[UltraFast] Carvers failed for {}: {}", chunk.getPos(), e.getMessage());
        }
    }

    private void generateFeatures(ChunkAccess chunk, Map<Long, ChunkAccess> area) {
        generateFeatures(chunk, area, true);
    }

    private void generateFeatures(ChunkAccess chunk, Map<Long, ChunkAccess> area, boolean allowCacheReads) {
        if (chunk == null) return;
        withFeatureRegionLocks(chunk.getPos(), () -> {
            try {
                Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE)
                );

                List<ChunkAccess> neighbors = collectNeighbors(chunk.getPos(), area, allowCacheReads);
                IsolatedWorldGenRegion region =
                        new IsolatedWorldGenRegion(level, chunk, neighbors, 1, ChunkStatus.FEATURES);
                applyBiomeDecorationSafely(region, chunk);
                Blender.generateBorderTicks(region, chunk);

                if (chunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.FEATURES);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.trace("[UltraFast] Features failed for {}: {}", chunk.getPos(), e.getMessage());
            }
        });
    }

    private List<FeatureTraceEntry> traceFeatures(ChunkAccess centerChunk, Map<Long, ChunkAccess> area) {
        AtomicReference<List<FeatureTraceEntry>> traceRef = new AtomicReference<>(List.of());

        withFeatureRegionLocks(centerChunk.getPos(), () -> {
            try {
                Heightmap.primeHeightmaps(centerChunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE)
                );

                List<ChunkAccess> neighbors = collectNeighbors(centerChunk.getPos(), area, false);
                IsolatedWorldGenRegion region =
                        new IsolatedWorldGenRegion(level, centerChunk, neighbors, 1, ChunkStatus.FEATURES);
                traceRef.set(applyBiomeDecoration(region, centerChunk, true));
                Blender.generateBorderTicks(region, centerChunk);

                if (centerChunk instanceof ProtoChunk proto) proto.setPersistedStatus(ChunkStatus.FEATURES);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.trace("[UltraFast] Feature trace failed for {}: {}",
                        centerChunk.getPos(), e.getMessage());
            }
        });

        return List.copyOf(traceRef.get());
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
        applyBiomeDecoration(region, centerChunk, false);
    }

    private List<FeatureTraceEntry> applyBiomeDecoration(IsolatedWorldGenRegion region,
                                                         ChunkAccess centerChunk,
                                                         boolean traceCenterChanges) {
        if (featureSteps.isEmpty()) {
            generator.applyBiomeDecoration(region, centerChunk, structureManager);
            return List.of();
        }

        ChunkPos chunkPos = centerChunk.getPos();
        SectionPos sectionPos = SectionPos.of(chunkPos, region.getMinSection());
        BlockPos origin = sectionPos.origin();
        ChunkAnalyzer analyzer = traceCenterChanges ? new ChunkAnalyzer() : null;
        Map<String, Integer> previousCounts = traceCenterChanges
                ? snapshotCounts(analyzer.createSnapshot(centerChunk)) : Collections.emptyMap();
        List<FeatureTraceEntry> traceEntries = traceCenterChanges ? new ArrayList<>() : Collections.emptyList();

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

                if (featureIndices == null) featureIndices =
                        new IntArraySet(cachedIndices.length * Math.max(1, presentBiomes.size()));

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
                random.setFeatureSeed(decorationSeed, featureIndex, step);
                region.setCurrentlyGenerating(() -> featureName);

                try {
                    feature.placeWithBiomeCheck(region, generator, random, origin);
                    if (traceCenterChanges) {
                        Map<String, Integer> currentCounts = snapshotCounts(analyzer.createSnapshot(centerChunk));
                        traceEntries.add(buildFeatureTraceEntry(
                                chunkPos, step, featureIndex, featureName, previousCounts, currentCounts
                        ));
                        previousCounts = currentCounts;
                    }
                } catch (Throwable t) {
                    logFeatureFailure(chunkPos, featureName, step, t);
                    if (traceCenterChanges) traceEntries.add(
                            FeatureTraceEntry.failed(chunkPos, step, featureIndex, featureName, t.toString()));
                }
            }
        }

        region.setCurrentlyGenerating(null);
        return traceCenterChanges ? List.copyOf(traceEntries) : List.of();
    }

    private void logFeatureFailure(ChunkPos chunkPos, String featureName, int step, Throwable error) {
        String failureKey = featureName + "|" + step;
        if (LOGGED_FEATURE_FAILURES.add(failureKey)) ComplexityAnalyzer.LOGGER.debug(
                "[UltraFast] Feature {} at step {} failed in {}: {}", featureName, step, chunkPos, error.toString()
        );
    }

    private FeatureTraceEntry buildFeatureTraceEntry(ChunkPos originPos, int step, int featureIndex,
                                                     String featureName, Map<String, Integer> previousCounts,
                                                     Map<String, Integer> currentCounts) {
        TreeSet<String> allKeys = new TreeSet<>();
        allKeys.addAll(previousCounts.keySet());
        allKeys.addAll(currentCounts.keySet());

        List<Map.Entry<String, Integer>> rawDeltas = new ArrayList<>();
        int absoluteDelta = 0;

        for (String blockId : allKeys) {
            int delta = currentCounts.getOrDefault(blockId, 0) - previousCounts.getOrDefault(blockId, 0);
            if (delta == 0) continue;
            absoluteDelta += Math.abs(delta);
            rawDeltas.add(Map.entry(blockId, delta));
        }

        rawDeltas.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> entry) -> Math.abs(entry.getValue()))
                .reversed()
                .thenComparing(Map.Entry::getKey));

        Map<String, Integer> sortedDeltas = new LinkedHashMap<>(rawDeltas.size());
        for (Map.Entry<String, Integer> entry : rawDeltas) sortedDeltas.put(entry.getKey(), entry.getValue());

        return FeatureTraceEntry.applied(
                originPos,
                step,
                featureIndex,
                featureName,
                sortedDeltas.size(),
                absoluteDelta,
                totalBlockCount(currentCounts) - totalBlockCount(previousCounts),
                sortedDeltas
        );
    }

    private Map<String, Integer> snapshotCounts(ChunkSnapshot snapshot) {
        return snapshot != null ? new HashMap<>(snapshot.blockCounts()) : Collections.emptyMap();
    }

    private int totalBlockCount(Map<String, Integer> counts) {
        int total = 0;
        for (int count : counts.values()) total += count;
        return total;
    }

    private List<ChunkPos> collectChunkArea(Collection<ChunkPos> centers, int radius) {
        LinkedHashSet<Long> keys = new LinkedHashSet<>();

        for (ChunkPos center : centers) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    keys.add(ChunkPos.asLong(center.x + dx, center.z + dz));
                }
            }
        }

        List<ChunkPos> result = new ArrayList<>(keys.size());
        for (long key : keys) {
            result.add(new ChunkPos(key));
        }
        return result;
    }

    private List<ChunkPos> orderFeatureOrigins(List<ChunkPos> featureOrigins) {
        if (featureOrigins.size() < 2) return featureOrigins;

        List<ChunkPos> ordered = new ArrayList<>(featureOrigins);
        ordered.sort(Comparator.comparingLong(pos -> {
            long sequence = featureOrderData.getSequence(pos);
            return sequence >= 0L ? sequence : Long.MAX_VALUE;
        }));
        return ordered;
    }

    private List<ChunkAccess> collectNeighbors(ChunkPos center, Map<Long, ChunkAccess> area, boolean allowCacheReads) {
        List<ChunkAccess> neighbors = new ArrayList<>(9);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            int nx = center.x + offset[0];
            int nz = center.z + offset[1];
            long key = ChunkPos.asLong(nx, nz);
            ChunkAccess neighbor = area.get(key);

            ChunkPos neighborPos = new ChunkPos(nx, nz);
            if (neighbor == null || hasNotStatus(neighbor, ChunkStatus.SURFACE)) {
                OptimizedChunkCache.CacheEntry cached = allowCacheReads ? cache.getEntry(neighborPos, ChunkStatus.SURFACE) : null;
                if (cached != null) {
                    neighbor = cloneChunkForWork(cached.chunk, cached.status);
                    area.put(key, neighbor);
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
        LOGGED_FEATURE_FAILURES.clear();
    }
}
