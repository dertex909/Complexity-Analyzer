package org.complexityanalyzer.geoscan.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Map;

public class IsolatedWorldGenRegion extends WorldGenRegion {

    private final ChunkAccess centerChunk;
    private final Map<Long, ChunkAccess> localChunkCache;
    private final ChunkStatus targetStatus;

    public IsolatedWorldGenRegion(ServerLevel level, ChunkAccess centerChunk,
                                  Map<Long, ChunkAccess> chunkCache, ChunkStatus targetStatus) {

        super(level, createCache(centerChunk, chunkCache), getChunkStep(targetStatus), centerChunk);
        this.centerChunk = centerChunk;
        this.localChunkCache = chunkCache;
        this.targetStatus = targetStatus;
    }

    private static ChunkStep getChunkStep(ChunkStatus status) {
        return net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
    }

    private static StaticCache2D<GenerationChunkHolder> createCache(ChunkAccess centerChunk,
                                                                    Map<Long, ChunkAccess> chunkCache) {
        ChunkPos center = centerChunk.getPos();

        int radius = 1;

        return StaticCache2D.create(center.x, center.z, radius, (x, z) -> {
            long key = ChunkPos.asLong(x, z);
            ChunkAccess chunk;

            if (x == center.x && z == center.z) {
                chunk = centerChunk;
            } else {
                chunk = chunkCache.get(key);
            }

            if (chunk == null) return null;
            return new FakeGenerationChunkHolder(chunk);
        });
    }

    @Override
    @Nullable
    public ChunkAccess getChunk(int x, int z, @NotNull ChunkStatus status, boolean required) {
        long key = ChunkPos.asLong(x, z);
        if (x == centerChunk.getPos().x && z == centerChunk.getPos().z) return centerChunk;

        ChunkAccess chunk = localChunkCache.get(key);

        if (chunk == null && required && targetStatus != ChunkStatus.CARVERS) ComplexityAnalyzer.LOGGER.debug(
                "[IsolatedRegion] Missing chunk [{}, {}] for {} at {}", x, z, targetStatus, centerChunk.getPos()
        );

        return chunk;
    }

    @Override
    public boolean hasChunk(int x, int z) {
        if (x == centerChunk.getPos().x && z == centerChunk.getPos().z) return true;
        return localChunkCache.containsKey(ChunkPos.asLong(x, z));
    }

    @Override
    public @NotNull BlockState getBlockState(@NotNull BlockPos pos) {
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());

        ChunkAccess chunk;
        if (cx == centerChunk.getPos().x && cz == centerChunk.getPos().z) {
            chunk = centerChunk;
        } else {
            chunk = localChunkCache.get(ChunkPos.asLong(cx, cz));
        }

        if (chunk != null) try {
            return chunk.getBlockState(pos);
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }

        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public @NotNull FluidState getFluidState(@NotNull BlockPos pos) {
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());

        ChunkAccess chunk;
        if (cx == centerChunk.getPos().x && cz == centerChunk.getPos().z) {
            chunk = centerChunk;
        } else {
            chunk = localChunkCache.get(ChunkPos.asLong(cx, cz));
        }

        if (chunk != null) try {
            return chunk.getFluidState(pos);
        } catch (Exception e) {
            return Fluids.EMPTY.defaultFluidState();
        }

        return Fluids.EMPTY.defaultFluidState();
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