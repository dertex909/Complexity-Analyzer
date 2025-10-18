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