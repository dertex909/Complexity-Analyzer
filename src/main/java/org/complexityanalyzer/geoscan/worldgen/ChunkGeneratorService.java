/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.geoscan.worldgen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.level.TicketType.START;
import static net.minecraft.util.Unit.INSTANCE;
import static net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES;

public class ChunkGeneratorService {

    private final ServerLevel level;

    public ChunkGeneratorService(ServerLevel level) {
        this.level = level;
    }

    public ObjectArrayList<ChunkAccess> generateBatch(LongArrayList packedPositions) {
        int size = packedPositions.size();
        if (size == 0) return new ObjectArrayList<>();

        var server = level.getServer();
        var chunkSource = level.getChunkSource();
        var futures = new ObjectArrayList<CompletableFuture<ChunkAccess>>(size);
        var chunkPositions = new ObjectArrayList<ChunkPos>(size);

        server.execute(() -> {
            for (int i = 0; i < size; i++) {
                long packed = packedPositions.getLong(i);
                var pos = new ChunkPos(packed);
                chunkPositions.add(pos);
                chunkSource.addRegionTicket(START, pos, 1, INSTANCE);
            }
        });

        for (int i = 0; i < size; i++) {
            long packed = packedPositions.getLong(i);
            int chunkX = ChunkPos.getX(packed);
            int chunkZ = ChunkPos.getZ(packed);

            var future = chunkSource.getChunkFuture(chunkX, chunkZ, FEATURES, true).thenApply(chunkResult -> {
                var chunk = chunkResult.orElse(null);
                if (chunk != null) chunk.setUnsaved(false);
                return chunk;
            });

            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        var results = new ObjectArrayList<ChunkAccess>(size);
        for (int i = 0; i < size; i++) results.add(futures.get(i).join());

        server.execute(() -> {
            for (var pos : chunkPositions) chunkSource.removeRegionTicket(START, pos, 1, INSTANCE);
        });

        return results;
    }
}