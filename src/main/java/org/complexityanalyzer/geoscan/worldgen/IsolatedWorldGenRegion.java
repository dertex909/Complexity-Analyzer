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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class IsolatedWorldGenRegion extends WorldGenRegion {

    private final ChunkAccess centerChunk;
    private final ChunkAccess[] localChunks;
    private final int side;
    private final int originX;
    private final int originZ;

    public IsolatedWorldGenRegion(ServerLevel level, ChunkAccess centerChunk,
                                  List<ChunkAccess> neighborChunks, int radius,
                                  ChunkStatus targetStatus) {

        super(level, createCache(level, centerChunk, neighborChunks, radius), getChunkStep(targetStatus), centerChunk);

        this.centerChunk = centerChunk;
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

        int lx = cx - originX;
        int lz = cz - originZ;

        if (lx >= 0 && lx < side && lz >= 0 && lz < side) {
            ChunkAccess chunk = localChunks[lx + lz * side];
            if (chunk != null) {
                try {
                    int y = pos.getY();
                    if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
                        return Blocks.AIR.defaultBlockState();
                    }
                    return chunk.getBlockState(pos);
                } catch (Throwable ignored) {
                }
            }
        }
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public @NotNull FluidState getFluidState(@NotNull BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int lx = cx - originX;
        int lz = cz - originZ;

        if (lx >= 0 && lx < side && lz >= 0 && lz < side) {
            ChunkAccess chunk = localChunks[lx + lz * side];
            if (chunk != null) {
                try {
                    int y = pos.getY();
                    if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
                        return Fluids.EMPTY.defaultFluidState();
                    }
                    return chunk.getFluidState(pos);
                } catch (Throwable ignored) {
                }
            }
        }
        return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public boolean hasChunk(int x, int z) {
        int lx = x - originX;
        int lz = z - originZ;
        return lx >= 0 && lx < side && lz >= 0 && lz < side && localChunks[lx + lz * side] != null;
    }

    @Override
    public ChunkAccess getChunk(int x, int z, @NotNull ChunkStatus status, boolean required) {
        int lx = x - originX;
        int lz = z - originZ;
        if (lx >= 0 && lx < side && lz >= 0 && lz < side) return localChunks[lx + lz * side];
        return null;
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