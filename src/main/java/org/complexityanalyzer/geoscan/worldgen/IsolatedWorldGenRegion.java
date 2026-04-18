package org.complexityanalyzer.geoscan.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class IsolatedWorldGenRegion extends WorldGenRegion {

    private final ServerLevel level;
    private final ChunkAccess centerChunk;
    private final ChunkAccess[] localChunks;
    private final ConcurrentHashMap<Long, ChunkAccess> syntheticChunks = new ConcurrentHashMap<>();
    private final int side;
    private final int originX;
    private final int originZ;
    private final int localRadius;
    private final int writeRadius;

    public IsolatedWorldGenRegion(ServerLevel level, ChunkAccess centerChunk,
                                  List<ChunkAccess> neighborChunks, int radius,
                                  ChunkStatus targetStatus) {

        super(level, createCache(level, centerChunk, neighborChunks, radius), getChunkStep(targetStatus), centerChunk);

        this.level = level;
        this.centerChunk = centerChunk;
        this.localRadius = radius;
        this.writeRadius = getChunkStep(targetStatus).blockStateWriteRadius();
        this.side = 2 * radius + 1;
        this.originX = centerChunk.getPos().x - radius;
        this.originZ = centerChunk.getPos().z - radius;

        this.localChunks = new ChunkAccess[side * side];

        for (ChunkAccess chunk : neighborChunks) {
            putChunkInArray(chunk);
        }
        putChunkInArray(centerChunk);
    }

    private void putChunkInArray(ChunkAccess chunk) {
        if (chunk == null) return;
        int lx = chunk.getPos().x - originX;
        int lz = chunk.getPos().z - originZ;

        if (lx >= 0 && lx < side && lz >= 0 && lz < side) localChunks[lx + lz * side] = chunk;
    }

    private ChunkAccess getLocalChunk(int x, int z) {
        int lx = x - originX;
        int lz = z - originZ;
        if (lx < 0 || lx >= side || lz < 0 || lz >= side) return null;
        return localChunks[lx + lz * side];
    }

    private ChunkAccess getSyntheticChunk(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        return syntheticChunks.computeIfAbsent(key, ignored -> createPlaceholderChunk(level, new ChunkPos(x, z)));
    }

    private ChunkAccess getChunkOrPlaceholder(int x, int z) {
        ChunkAccess local = getLocalChunk(x, z);
        return local != null ? local : getSyntheticChunk(x, z);
    }

    private static ChunkStep getChunkStep(ChunkStatus status) {
        return net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
    }

    private static StaticCache2D<GenerationChunkHolder> createCache(ServerLevel level, ChunkAccess centerChunk,
                                                                    List<ChunkAccess> neighbors, int radius) {
        int side = 2 * radius + 1;
        int originX = centerChunk.getPos().x - radius;
        int originZ = centerChunk.getPos().z - radius;

        ChunkAccess[] tempArray = new ChunkAccess[side * side];

        for (ChunkAccess chunk : neighbors) {
            if (chunk == null) continue;
            int lx = chunk.getPos().x - originX;
            int lz = chunk.getPos().z - originZ;
            if (lx >= 0 && lx < side && lz >= 0 && lz < side) tempArray[lx + lz * side] = chunk;
        }

        int centerLx = centerChunk.getPos().x - originX;
        int centerLz = centerChunk.getPos().z - originZ;
        if (centerLx >= 0 && centerLx < side && centerLz >= 0 && centerLz < side) {
            tempArray[centerLx + centerLz * side] = centerChunk;
        }

        return StaticCache2D.create(centerChunk.getPos().x, centerChunk.getPos().z, radius, (x, z) -> {
            int lx = x - originX;
            int lz = z - originZ;

            ChunkAccess chunk = null;
            boolean b = lx >= 0 && lz >= 0 && lx < side && lz < side;
            if (b) chunk = tempArray[lx + lz * side];

            if (chunk == null) {
                chunk = createPlaceholderChunk(level, new ChunkPos(x, z));
                if (b) tempArray[lx + lz * side] = chunk;
            }

            return new FakeGenerationChunkHolder(chunk);
        });
    }

    private static ChunkAccess createPlaceholderChunk(ServerLevel level, ChunkPos pos) {
        try {
            return new ProtoChunk(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(
                    Registries.BIOME), null
            );
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.trace(
                    "[IsolatedWorldGenRegion] Failed to create placeholder for {}: {}", pos, e.getMessage());
            return new ProtoChunk(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(
                    Registries.BIOME), null
            );
        }
    }

    @Override
    public @NotNull BlockState getBlockState(@NotNull BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        ChunkAccess chunk = getChunkOrPlaceholder(cx, cz);
        try {
            int y = pos.getY();
            if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) return Blocks.AIR.defaultBlockState();
            return chunk.getBlockState(pos);
        } catch (Throwable ignored) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    @Override
    public @NotNull FluidState getFluidState(@NotNull BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        ChunkAccess chunk = getChunkOrPlaceholder(cx, cz);
        try {
            int y = pos.getY();
            if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
                return Fluids.EMPTY.defaultFluidState();
            }
            return chunk.getFluidState(pos);
        } catch (Throwable ignored) {
            return Fluids.EMPTY.defaultFluidState();
        }
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return centerChunk.getPos().getChessboardDistance(x, z) <= localRadius && getLocalChunk(x, z) != null;
    }

    @Override
    public @NotNull ChunkAccess getChunk(int x, int z) {
        return getChunkOrPlaceholder(x, z);
    }

    @Override
    public ChunkAccess getChunk(int x, int z, @NotNull ChunkStatus status, boolean required) {
        return getChunkOrPlaceholder(x, z);
    }

    @Override
    public boolean ensureCanWrite(@NotNull BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (centerChunk.getPos().getChessboardDistance(chunkX, chunkZ) > writeRadius) return false;

        int y = pos.getY();
        return y >= this.getMinBuildHeight() && y < this.getMaxBuildHeight();
    }

    @Override
    public int getHeight(Heightmap.@NotNull Types heightmap, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        ChunkAccess chunk = getLocalChunk(chunkX, chunkZ);
        if (chunk == null) return this.getMinBuildHeight();

        try {
            return chunk.getHeight(heightmap, x & 15, z & 15) + 1;
        } catch (Throwable ignored) {
            return this.getMinBuildHeight();
        }
    }

    @Override
    public boolean isOldChunkAround(@NotNull ChunkPos pos, int radius) {
        return false;
    }

    @Override
    public @NotNull ChunkPos getCenter() {
        return centerChunk.getPos();
    }
}
