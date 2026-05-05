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

package org.complexityanalyzer.geoscan.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.geoscan.util.ChunkCoordinateUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class BiomeScanData {
    private final transient ConcurrentHashMap<Block, AtomicLong> blockCounts = new ConcurrentHashMap<>();
    private transient LongSet scannedChunksSet;

    ConcurrentHashMap<String, Long> serializableBlockCounts = new ConcurrentHashMap<>();
    LongList scannedChunks;

    public BiomeScanData() {
        this.scannedChunks = new LongArrayList();
        this.scannedChunksSet = new LongOpenHashSet();
    }

    public void addBlock(Block block, long count) {
        blockCounts.computeIfAbsent(block, k -> new AtomicLong(0)).addAndGet(count);
    }

    public void addScannedChunk(int chunkX, int chunkZ) {
        long coord = ChunkCoordinateUtil.pack(chunkX, chunkZ);
        if (this.scannedChunksSet == null) this.scannedChunksSet = new LongOpenHashSet();
        this.scannedChunksSet.add(coord);
    }

    public int getChunksScanned() {
        return scannedChunksSet != null ? scannedChunksSet.size() : 0;
    }

    public long getTotalBlocks() {
        long total = 0;
        for (AtomicLong v : blockCounts.values()) total += v.get();
        return total;
    }

    public long getBlockCount(Block block) {
        AtomicLong count = blockCounts.get(block);
        return (count != null) ? count.get() : 0;
    }

    public ConcurrentHashMap<Block, AtomicLong> getInternalBlockCounts() {
        return this.blockCounts;
    }

    LongSet getInternalScannedChunksSet() {
        return this.scannedChunksSet;
    }

    void setInternalScannedChunksSet(LongSet newSet) {
        this.scannedChunksSet = newSet;
    }
}