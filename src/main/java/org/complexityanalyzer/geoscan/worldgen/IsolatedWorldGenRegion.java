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

package org.complexityanalyzer.geoscan.worldgen;

import net.minecraft.core.BlockPos;
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

        super(level, createCache(centerChunk, neighborChunks, radius), getChunkStep(targetStatus), centerChunk);

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
        int lx = chunk.getPos().x - originX;
        int lz = chunk.getPos().z - originZ;

        if (lx >= 0 && lx < side && lz >= 0 && lz < side) localChunks[lx + lz * side] = chunk;
    }

    private static ChunkStep getChunkStep(ChunkStatus status) {
        return net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
    }

    private static StaticCache2D<GenerationChunkHolder> createCache(ChunkAccess centerChunk,
                                                                    List<ChunkAccess> neighbors,
                                                                    int radius) {
        int side = 2 * radius + 1;
        int originX = centerChunk.getPos().x - radius;
        int originZ = centerChunk.getPos().z - radius;

        ChunkAccess[] tempArray = new ChunkAccess[side * side];

        for (ChunkAccess chunk : neighbors) {
            int lx = chunk.getPos().x - originX;
            int lz = chunk.getPos().z - originZ;
            if (lx >= 0 && lx < side && lz >= 0 && lz < side) tempArray[lx + lz * side] = chunk;
        }
        tempArray[(centerChunk.getPos().x - originX) + (centerChunk.getPos().z - originZ) * side] = centerChunk;

        return StaticCache2D.create(centerChunk.getPos().x, centerChunk.getPos().z, radius, (x, z) -> {
            int lx = x - originX;
            int lz = z - originZ;
            if (lx < 0 || lz < 0 || lx >= side || lz >= side) return null;
            ChunkAccess chunk = tempArray[lx + lz * side];

            if (chunk == null) return null;
            return new FakeGenerationChunkHolder(chunk);
        });
    }

    @Override
    public @NotNull BlockState getBlockState(@NotNull BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        int lx = cx - originX;
        int lz = cz - originZ;

        if (lx >= 0 && lx < side && lz >= 0 && lz < side) {
            ChunkAccess chunk = localChunks[lx + lz * side];
            if (chunk != null) try {
                return chunk.getBlockState(pos);
            } catch (Throwable ignored) {
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
            if (chunk != null) try {
                return chunk.getFluidState(pos);
            } catch (Throwable ignored) {
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