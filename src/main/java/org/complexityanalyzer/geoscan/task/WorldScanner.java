package org.complexityanalyzer.geoscan.task;

import com.mojang.datafixers.util.Pair;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

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

        ComplexityAnalyzer.LOGGER.info("[WorldScanner] Configured for {} chunks/biome → {} mode (radius: {})",
                chunksPerBiome,
                fullWorldMode ? "FULL-WORLD" : "NORMAL",
                fullWorldMode ? ScanConfig.RADIUS_FULL_WORLD : ScanConfig.RADIUS_MAX);
    }

    public Optional<ChunkPos> findBiomeLocation(
            ResourceKey<Level> dimension,
            ResourceKey<Biome> biomeKey,
            boolean isRelocation
    ) {
        if (shouldStop()) return Optional.empty();

        while (server.isPaused() && !shouldStop()) {
            LockSupport.parkNanos(100_000_000L);
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
        }

        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            ComplexityAnalyzer.LOGGER.error("Cannot find biome, level {} is not loaded.", dimension.location());
            return Optional.empty();
        }

        if (!level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(biomeKey)) {
            ComplexityAnalyzer.LOGGER.error("Cannot find biome, {} is not registered.", biomeKey.location());
            return Optional.empty();
        }

        if (shouldStop()) return Optional.empty();

        String cacheKey = dimension.location() + "|" + biomeKey.location();

        return isRelocation
                ? findForRelocation(level, biomeKey, cacheKey)
                : findInitial(level, biomeKey, cacheKey);
    }

    private Optional<ChunkPos> findInitial(ServerLevel level, ResourceKey<Biome> biomeKey, String cacheKey) {
        if (shouldStop()) return Optional.empty();

        List<BlockPos> cached = biomeLocationCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            BlockPos pos = cached.getFirst();
            ComplexityAnalyzer.LOGGER.debug("Using cached location for {}: [{}, {}]",
                    biomeKey.location().getPath(), pos.getX(), pos.getZ());
            return Optional.of(new ChunkPos(pos));
        }

        if (shouldStop()) return Optional.empty();

        Optional<BlockPos> found = fullWorldMode
                ? locateBiome(level, biomeKey, BlockPos.ZERO, ScanConfig.RADIUS_FULL_WORLD)
                : locateBiomeCascading(level, biomeKey);

        if (shouldStop()) return Optional.empty();

        found.ifPresent(pos -> cacheLocation(cacheKey, pos));

        return found.map(ChunkPos::new);
    }

    private Optional<ChunkPos> findForRelocation(ServerLevel level, ResourceKey<Biome> biomeKey, String cacheKey) {
        if (shouldStop()) return Optional.empty();

        List<BlockPos> known = biomeLocationCache.getOrDefault(cacheKey, Collections.emptyList());
        int searchRadius = fullWorldMode ? ScanConfig.RADIUS_EXTENDED : ScanConfig.RADIUS_NORMAL;
        int spreadDistance = fullWorldMode ? 50000 : 20000;

        List<BlockPos> origins = generateSpreadOrigins(known, spreadDistance);

        for (BlockPos origin : origins) {
            if (shouldStop()) return Optional.empty();

            Optional<BlockPos> result = locateBiome(level, biomeKey, origin, searchRadius);

            if (shouldStop()) return Optional.empty();

            if (result.isPresent() && !isTooClose(result.get(), known)) {
                cacheLocation(cacheKey, result.get());
                return Optional.of(new ChunkPos(result.get()));
            }
        }

        if (shouldStop()) return Optional.empty();

        int maxRange = fullWorldMode ? ScanConfig.RADIUS_FULL_WORLD : ScanConfig.RADIUS_MAX;
        BlockPos randomOrigin = randomBlockPos(maxRange);

        Optional<BlockPos> result = locateBiome(level, biomeKey, randomOrigin, searchRadius);

        if (shouldStop()) return Optional.empty();

        if (result.isPresent()) {
            cacheLocation(cacheKey, result.get());
            return Optional.of(new ChunkPos(result.get()));
        }

        return Optional.empty();
    }

    private Optional<BlockPos> locateBiome(ServerLevel level, ResourceKey<Biome> biomeKey, BlockPos origin, int radius) {
        if (shouldStop()) return Optional.empty();

        try {
            var chunkSource = level.getChunkSource();
            var generator = chunkSource.getGenerator();
            var biomeSource = generator.getBiomeSource();
            var sampler = chunkSource.randomState().sampler();

            var result = biomeSource.findClosestBiome3d(
                    origin,
                    radius,
                    8,
                    8,
                    holder -> holder.is(biomeKey),
                    sampler,
                    level
            );

            if (shouldStop()) return Optional.empty();

            return Optional.ofNullable(result).map(Pair::getFirst);

        } catch (Exception e) {
            if (!shouldStop()) ComplexityAnalyzer.LOGGER.error("Error locating biome {}", biomeKey.location(), e);
            return Optional.empty();
        }
    }

    private Optional<BlockPos> locateBiomeCascading(ServerLevel level, ResourceKey<Biome> biomeKey) {
        int[] radii = {ScanConfig.RADIUS_NORMAL, ScanConfig.RADIUS_EXTENDED, ScanConfig.RADIUS_MAX};

        for (int radius : radii) {
            if (shouldStop()) return Optional.empty();
            Optional<BlockPos> result = locateBiome(level, biomeKey, BlockPos.ZERO, radius);
            if (shouldStop()) return Optional.empty();
            if (result.isPresent()) return result;
        }

        if (!shouldStop()) {
            ComplexityAnalyzer.LOGGER.warn("Could not find biome {} within {} blocks",
                    biomeKey.location(), ScanConfig.RADIUS_MAX);
        }
        return Optional.empty();
    }

    private boolean isTooClose(BlockPos candidate, List<BlockPos> known) {
        long minDistSq = (long) ScanConfig.RELOCATION_MIN_DISTANCE * ScanConfig.RELOCATION_MIN_DISTANCE;
        return known.stream().anyMatch(k -> k.distSqr(candidate) < minDistSq);
    }

    private List<BlockPos> generateSpreadOrigins(List<BlockPos> known, int spreadDistance) {
        List<BlockPos> origins = new ArrayList<>(ScanConfig.RELOCATION_ORIGINS_COUNT);

        if (known.isEmpty()) {
            origins.add(new BlockPos(spreadDistance, 64, 0));
            origins.add(new BlockPos(-spreadDistance, 64, 0));
            origins.add(new BlockPos(0, 64, spreadDistance));
            origins.add(new BlockPos(0, 64, -spreadDistance));
            origins.add(new BlockPos((int) (spreadDistance * 0.7), 64, (int) (spreadDistance * 0.7)));
        } else {
            ThreadLocalRandom rand = ThreadLocalRandom.current();
            for (int i = 0; i < ScanConfig.RELOCATION_ORIGINS_COUNT; i++) {
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
        if (shouldStop()) return;
        biomeLocationCache
                .computeIfAbsent(cacheKey, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(pos);
    }

    public void shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return;
        stopRequested.set(true);
        biomeLocationCache.clear();
        ComplexityAnalyzer.LOGGER.info("[WorldScanner] Shutdown complete.");
    }
}