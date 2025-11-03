/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.geoscan.data;

import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.geoscan.util.ChunkCoordinateUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class BiomeScanData {
    private final transient Map<Block, AtomicLong> blockCounts = new ConcurrentHashMap<>();
    private transient Set<Long> scannedChunksSet;

    Map<String, Long> serializableBlockCounts = new ConcurrentHashMap<>();
    List<Long> scannedChunks;

    public BiomeScanData() {
        this.scannedChunks = new ArrayList<>();
        this.scannedChunksSet = new HashSet<>();
    }

    public void addBlock(Block block) {
        blockCounts.computeIfAbsent(block, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void addBlock(Block block, long count) {
        blockCounts.computeIfAbsent(block, k -> new AtomicLong(0)).addAndGet(count);
    }

    public void addScannedChunk(int chunkX, int chunkZ) {
        long coord = ChunkCoordinateUtil.pack(chunkX, chunkZ);
        if (this.scannedChunksSet == null) {
            this.scannedChunksSet = new HashSet<>();
        }
        this.scannedChunksSet.add(coord);
    }

    public boolean hasScannedChunk(int chunkX, int chunkZ) {
        if (this.scannedChunksSet == null) return false;
        long coord = ChunkCoordinateUtil.pack(chunkX, chunkZ);
        return this.scannedChunksSet.contains(coord);
    }

    public int getChunksScanned() {
        return scannedChunksSet != null ? scannedChunksSet.size() : 0;
    }

    public Map<Block, AtomicLong> getBlockCounts() {
        return Collections.unmodifiableMap(blockCounts);
    }

    public void merge(BiomeScanData other) {
        if (other == null) return;

        other.getBlockCounts().forEach((block, count) -> this.addBlock(block, count.get()));

        if (other.scannedChunksSet != null) {
            if (this.scannedChunksSet == null) {
                this.scannedChunksSet = new HashSet<>();
            }
            this.scannedChunksSet.addAll(other.scannedChunksSet);
        }
    }

    public Map<Block, AtomicLong> getInternalBlockCounts() {
        return this.blockCounts;
    }

    Set<Long> getInternalScannedChunksSet() {
        return this.scannedChunksSet;
    }

    void setInternalScannedChunksSet(Set<Long> newSet) {
        this.scannedChunksSet = newSet;
    }
}