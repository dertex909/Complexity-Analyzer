package org.complexityanalyzer.geoscan.task;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class WorldScanner {

    private final MinecraftServer server;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    private static final int FULL_WORLD_THRESHOLD = 60;

    private static final int RADIUS_NORMAL = 6400;
    private static final int RADIUS_EXTENDED = 32000;
    private static final int RADIUS_MAX = 64000;

    private static final int RADIUS_FULL_WORLD = 1_000_000;

    private static final int RELOCATION_MIN_DISTANCE = 500;
    private static final int RELOCATION_ORIGINS_COUNT = 5;

    private volatile boolean fullWorldMode = false;

    private final ConcurrentHashMap<String, List<BlockPos>> biomeLocationCache = new ConcurrentHashMap<>();

    public record ScanResult(ChunkSnapshot snapshot, @Nullable ResourceLocation actualBiome) {
    }

    public WorldScanner(MinecraftServer server) {
        this.server = server;
    }

    public void configureForScan(int chunksPerBiome) {
        this.fullWorldMode = chunksPerBiome > FULL_WORLD_THRESHOLD;
        ComplexityAnalyzer.LOGGER.info("[WorldScanner] Configured for {} chunks/biome → {} mode (radius: {})",
                chunksPerBiome,
                fullWorldMode ? "FULL-WORLD" : "NORMAL",
                fullWorldMode ? RADIUS_FULL_WORLD : RADIUS_MAX);
    }

    public Optional<ChunkPos> findBiomeLocation(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> biomeKey,
            boolean isRelocation
    ) {
        if (shutdownRequested.get()) return Optional.empty();

        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot find biome, level {} is not loaded.", dimension.location());
            return Optional.empty();
        }

        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(biomeKey)) {
            ComplexityAnalyzer.LOGGER.error("Cannot find biome, {} is not registered.", biomeKey.location());
            return Optional.empty();
        }

        String cacheKey = dimension.location() + "|" + biomeKey.location();
        return isRelocation
                ? findForRelocation(level, biomeKey, cacheKey)
                : findInitial(level, biomeKey, cacheKey);
    }

    private Optional<ChunkPos> findInitial(ServerLevel level, ResourceKey<Biome> biomeKey, String cacheKey) {
        List<BlockPos> cached = biomeLocationCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            BlockPos pos = cached.getFirst();
            ComplexityAnalyzer.LOGGER.debug("Using cached location for {}: [{}, {}]",
                    biomeKey.location().getPath(), pos.getX(), pos.getZ());
            return Optional.of(new ChunkPos(pos));
        }

        Optional<BlockPos> found = fullWorldMode
                ? locateBiome(level, biomeKey, BlockPos.ZERO, RADIUS_FULL_WORLD)
                : locateBiomeCascading(level, biomeKey);

        found.ifPresent(pos -> cacheLocation(cacheKey, pos));
        return found.map(ChunkPos::new);
    }

    private Optional<ChunkPos> findForRelocation(ServerLevel level, ResourceKey<Biome> biomeKey, String cacheKey) {
        List<BlockPos> known = biomeLocationCache.getOrDefault(cacheKey, Collections.emptyList());
        int searchRadius = fullWorldMode ? RADIUS_EXTENDED : RADIUS_NORMAL;
        int spreadDistance = fullWorldMode ? 50000 : 20000;

        for (BlockPos origin : generateSpreadOrigins(known, spreadDistance)) {
            if (shutdownRequested.get() || Thread.currentThread().isInterrupted()) return Optional.empty();

            Optional<BlockPos> result = locateBiome(level, biomeKey, origin, searchRadius);
            if (result.isPresent() && !isTooClose(result.get(), known)) {
                cacheLocation(cacheKey, result.get());
                return Optional.of(new ChunkPos(result.get()));
            }
        }

        if (!shutdownRequested.get()) {
            int maxRange = fullWorldMode ? RADIUS_FULL_WORLD : RADIUS_MAX;
            BlockPos randomOrigin = randomBlockPos(maxRange);

            Optional<BlockPos> result = locateBiome(level, biomeKey, randomOrigin, searchRadius);
            if (result.isPresent()) {
                cacheLocation(cacheKey, result.get());
                return Optional.of(new ChunkPos(result.get()));
            }
        }

        return Optional.empty();
    }

    private Optional<BlockPos> locateBiome(ServerLevel level, ResourceKey<Biome> biomeKey, BlockPos origin, int radius) {
        if (shutdownRequested.get()) return Optional.empty();

        Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(
                holder -> holder.is(biomeKey), origin, radius, 8, 8
        );
        return Optional.ofNullable(result).map(Pair::getFirst);
    }

    private Optional<BlockPos> locateBiomeCascading(ServerLevel level, ResourceKey<Biome> biomeKey) {
        for (int radius : new int[]{RADIUS_NORMAL, RADIUS_EXTENDED, RADIUS_MAX}) {
            if (shutdownRequested.get()) return Optional.empty();

            Optional<BlockPos> result = locateBiome(level, biomeKey, BlockPos.ZERO, radius);
            if (result.isPresent()) return result;
        }

        ComplexityAnalyzer.LOGGER.warn("Could not find biome {} within {} blocks", biomeKey.location(), RADIUS_MAX);
        return Optional.empty();
    }

    private boolean isTooClose(BlockPos candidate, List<BlockPos> known) {
        long minDistSq = (long) RELOCATION_MIN_DISTANCE * RELOCATION_MIN_DISTANCE;
        return known.stream().anyMatch(k -> k.distSqr(candidate) < minDistSq);
    }

    private List<BlockPos> generateSpreadOrigins(List<BlockPos> known, int spreadDistance) {
        List<BlockPos> origins = new ArrayList<>(RELOCATION_ORIGINS_COUNT);

        if (known.isEmpty()) {
            origins.add(new BlockPos(spreadDistance, 64, 0));
            origins.add(new BlockPos(-spreadDistance, 64, 0));
            origins.add(new BlockPos(0, 64, spreadDistance));
            origins.add(new BlockPos(0, 64, -spreadDistance));
            origins.add(new BlockPos((int) (spreadDistance * 0.7), 64, (int) (spreadDistance * 0.7)));
        } else {
            for (int i = 0; i < RELOCATION_ORIGINS_COUNT; i++) {
                ThreadLocalRandom rand = ThreadLocalRandom.current();
                BlockPos base = known.get(rand.nextInt(known.size()));
                double angle = rand.nextDouble() * Math.PI * 2;
                int dist = spreadDistance / 2 + rand.nextInt(spreadDistance);
                origins.add(new BlockPos(
                        base.getX() + (int) (Math.cos(angle) * dist),
                        64,
                        base.getZ() + (int) (Math.sin(angle) * dist)
                ));
            }
        }

        return origins;
    }

    private BlockPos randomBlockPos(int maxRange) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        return new BlockPos(
                rand.nextInt(maxRange * 2) - maxRange,
                64,
                rand.nextInt(maxRange * 2) - maxRange
        );
    }

    private void cacheLocation(String cacheKey, BlockPos pos) {
        biomeLocationCache
                .computeIfAbsent(cacheKey, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(pos);
    }

    public void processChunk(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> targetBiomeKey,
            ChunkPos pos,
            BiConsumer<Optional<ScanResult>, Boolean> onComplete
    ) {
        if (shutdownRequested.get()) {
            onComplete.accept(Optional.empty(), false);
            return;
        }

        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            onComplete.accept(Optional.empty(), false);
            return;
        }

        if (shutdownRequested.get()) {
            onComplete.accept(Optional.empty(), false);
            return;
        }

        ServerChunkCache chunkCache = level.getChunkSource();

        chunkCache.getChunkFuture(pos.x, pos.z, ChunkStatus.BIOMES, true)
                .thenCompose(either -> {
                    if (shutdownRequested.get()) return CompletableFuture.completedFuture(null);

                    ChunkAccess chunk = either.orElse(null);
                    if (chunk == null) return CompletableFuture.completedFuture(null);

                    boolean hasTargetBiome = isBiomePresentInChunk(chunk, targetBiomeKey);
                    if (!hasTargetBiome) {
                        return CompletableFuture.completedFuture(null);
                    }

                    ComplexityAnalyzer.LOGGER.debug("[GEN] Chunk [{}, {}] generating...", pos.x, pos.z);
                    return chunkCache.getChunkFuture(pos.x, pos.z, ChunkStatus.FEATURES, true);
                })
                .thenAccept(either -> {
                    try {
                        if (shutdownRequested.get() || either == null) {
                            onComplete.accept(Optional.empty(), false);
                            return;
                        }

                        ChunkAccess chunk = either.orElse(null);
                        if (chunk != null) {
                            ChunkSnapshot snapshot = createSnapshot(chunk);
                            ResourceLocation actualBiome = getDominantBiome(chunk);

                            ComplexityAnalyzer.LOGGER.debug("[SCAN] Chunk [{}, {}] scanned: {} unique block types, biome: {}",
                                    pos.x, pos.z, snapshot.blockCounts().size(),
                                    actualBiome != null ? actualBiome.getPath() : "unknown");

                            onComplete.accept(Optional.of(new ScanResult(snapshot, actualBiome)), true);
                        } else {
                            onComplete.accept(Optional.empty(), false);
                        }
                    } catch (Exception e) {
                        ComplexityAnalyzer.LOGGER.error("[SCAN] Chunk [{}, {}] error", pos.x, pos.z, e);
                        onComplete.accept(Optional.empty(), false);
                    }
                })
                .exceptionally(throwable -> {
                    ComplexityAnalyzer.LOGGER.error("[SCAN] Chunk [{}, {}] failed", pos.x, pos.z, throwable);
                    onComplete.accept(Optional.empty(), false);
                    return null;
                });
    }

    private boolean isBiomePresentInChunk(ChunkAccess chunk, ResourceKey<Biome> targetBiomeKey) {
        for (int qy = chunk.getMinSection() * 4; qy < chunk.getMaxSection() * 4; ++qy) {
            for (int qx = 0; qx < 4; ++qx) {
                for (int qz = 0; qz < 4; ++qz) {
                    if (chunk.getNoiseBiome(qx, qy, qz).is(targetBiomeKey)) return true;
                }
            }
        }
        return false;
    }

    private ChunkSnapshot createSnapshot(ChunkAccess chunk) {
        Map<String, Integer> counts = new HashMap<>();

        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) continue;

            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        var state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            counts.merge(blockId, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, counts);
    }

    private ResourceLocation getDominantBiome(ChunkAccess chunk) {
        Map<ResourceLocation, Integer> biomeCounts = new HashMap<>();
        int minSectionY = chunk.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;

            int sectionY = (minSectionY + sectionIndex) * 16;
            int biomeY = (sectionY + 8) >> 2;

            var biomeHolder = chunk.getNoiseBiome(2, biomeY, 2);
            biomeHolder.unwrapKey().ifPresent(key ->
                    biomeCounts.merge(key.location(), 1, Integer::sum));
        }

        return biomeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public void shutdown() {
        shutdownRequested.set(true);
        biomeLocationCache.clear();
    }
}