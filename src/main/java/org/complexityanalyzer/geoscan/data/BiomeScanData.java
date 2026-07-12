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

package org.complexityanalyzer.geoscan.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.world.level.block.Block;

public class BiomeScanData {
    private final transient Reference2LongOpenHashMap<Block> blockCounts = new Reference2LongOpenHashMap<>();
    Object2LongOpenHashMap<String> serializableBlockCounts = new Object2LongOpenHashMap<>();
    LongArrayList scannedChunks;
    private transient LongOpenHashSet scannedChunksSet;

    public BiomeScanData() {
        this.scannedChunks = new LongArrayList();
        this.scannedChunksSet = new LongOpenHashSet();
        this.blockCounts.defaultReturnValue(0L);
        this.serializableBlockCounts.defaultReturnValue(0L);
    }

    public void addBlock(Block block, long count) {
        if (block == null) return;
        blockCounts.addTo(block, count);
    }

    public void addScannedChunk(int chunkX, int chunkZ) {
        long coord = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        var set = this.scannedChunksSet;
        if (set == null) {
            set = new LongOpenHashSet();
            this.scannedChunksSet = set;
        }
        set.add(coord);
    }

    public int getChunksScanned() {
        var set = this.scannedChunksSet;
        return set != null ? set.size() : 0;
    }

    public long getTotalBlocks() {
        long sum = 0;
        var it = blockCounts.values().iterator();
        while (it.hasNext()) sum += it.nextLong();
        return sum;
    }

    public long getBlockCount(Block block) {
        return blockCounts.getLong(block);
    }

    public Reference2LongOpenHashMap<Block> getInternalBlockCounts() {
        return this.blockCounts;
    }

    LongOpenHashSet getInternalScannedChunksSet() {
        return this.scannedChunksSet;
    }

    void setInternalScannedChunksSet(LongOpenHashSet newSet) {
        this.scannedChunksSet = newSet;
    }
}