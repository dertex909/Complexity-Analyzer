package org.complexityanalyzer.geoscan.scan;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.data.ScanMetadata;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ScanSession {

    public record BiomeKey(ResourceLocation dim, ResourceLocation biome) {
    }

    private final long sessionId;
    private final ScanProfile profile;
    private final int chunksPerBiome;

    private final AtomicLong totalChunksScanned = new AtomicLong(0);
    private final long startTimeMs = System.currentTimeMillis();

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger totalChunksNeeded = new AtomicInteger(0);
    private final AtomicInteger totalChunksFound = new AtomicInteger(0);

    private final Set<Long> attemptedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<BiomeKey, AtomicInteger> remainingNeeds = new ConcurrentHashMap<>();

    private volatile ScanMetadata.ScanPhase phase = ScanMetadata.ScanPhase.RECONNAISSANCE;

    public void recordChunkScanned() {
        totalChunksScanned.incrementAndGet();
    }

    public float getScanSpeed() {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        if (elapsedMs < 1000) return 0;
        return totalChunksScanned.get() / (elapsedMs / 1000f);
    }

    public long getTotalChunksScanned() {
        return totalChunksScanned.get();
    }

    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    public int getTotalChunksNeeded() {
        int remaining = 0;
        for (AtomicInteger need : remainingNeeds.values()) {
            remaining += need.get();
        }
        return remaining + (int) totalChunksScanned.get();
    }

    public int getProgressPercent() {
        int totalNeeded = remainingNeeds.size() * chunksPerBiome;
        if (totalNeeded == 0) return 100;
        return (int) (totalChunksScanned.get() * 100 / totalNeeded);
    }

    public Map<ResourceLocation, Map<ResourceLocation, int[]>> getBiomeProgress() {
        Map<ResourceLocation, Map<ResourceLocation, int[]>> result = new HashMap<>();

        for (var entry : remainingNeeds.entrySet()) {
            BiomeKey key = entry.getKey();
            int remaining = entry.getValue().get();
            int scanned = Math.max(0, chunksPerBiome - remaining);

            result.computeIfAbsent(key.dim(), k -> new HashMap<>())
                    .put(key.biome(), new int[]{scanned, chunksPerBiome});
        }

        return result;
    }

    public ScanSession(long sessionId, ScanProfile profile, int chunksPerBiome) {
        this.sessionId = sessionId;
        this.profile = profile;
        this.chunksPerBiome = chunksPerBiome;
    }

    public long getSessionId() {
        return sessionId;
    }

    public ScanProfile getProfile() {
        return profile;
    }

    public int getChunksPerBiome() {
        return chunksPerBiome;
    }

    public boolean isValid() {
        return active.get() && !Thread.currentThread().isInterrupted();
    }

    public void invalidate() {
        active.set(false);
    }

    public void setPhase(ScanMetadata.ScanPhase phase) {
        this.phase = phase;
    }

    public void setTotalChunksNeeded(int total) {
        totalChunksNeeded.set(total);
    }

    public void setBiomeNeed(ResourceLocation dim, ResourceLocation biome, int needed) {
        remainingNeeds.put(new BiomeKey(dim, biome), new AtomicInteger(needed));
    }

    public boolean doesNotNeedBiome(ResourceLocation dim, ResourceLocation biome) {
        AtomicInteger remaining = remainingNeeds.get(new BiomeKey(dim, biome));
        return remaining == null || remaining.get() <= 0;
    }

    public boolean tryClaimChunk(ResourceLocation dim, ResourceLocation biome) {
        AtomicInteger remaining = remainingNeeds.get(new BiomeKey(dim, biome));
        if (remaining == null) return false;

        int current;
        do {
            current = remaining.get();
            if (current <= 0) return false;
        } while (!remaining.compareAndSet(current, current - 1));

        totalChunksFound.incrementAndGet();
        return true;
    }

    public ResourceLocation getRandomNeededBiome(ResourceLocation dim) {
        List<ResourceLocation> needed = new ArrayList<>();

        for (var entry : remainingNeeds.entrySet()) {
            if (entry.getKey().dim().equals(dim) && entry.getValue().get() > 0) needed.add(entry.getKey().biome());
        }

        if (needed.isEmpty()) return null;
        return needed.get(ThreadLocalRandom.current().nextInt(needed.size()));
    }

    public List<ResourceLocation> getDimensionsWithNeeds() {
        Set<ResourceLocation> dims = new HashSet<>();
        for (var entry : remainingNeeds.entrySet()) {
            if (entry.getValue().get() > 0) dims.add(entry.getKey().dim());
        }
        return new ArrayList<>(dims);
    }

    public boolean hasAnyNeeds() {
        for (AtomicInteger remaining : remainingNeeds.values()) {
            if (remaining.get() > 0) return true;
        }
        return false;
    }

    public int countCompletedBiomes() {
        int completed = 0;
        for (AtomicInteger remaining : remainingNeeds.values()) {
            if (remaining.get() <= 0) completed++;
        }
        return completed;
    }

    public int countTotalBiomes() {
        return remainingNeeds.size();
    }

    public boolean tryMarkChunk(ChunkPos pos) {
        return attemptedChunks.add(pos.toLong());
    }

    public void loadAttemptedChunks(Set<Long> chunks) {
        attemptedChunks.addAll(chunks);
    }

    public String getStatusString() {
        return switch (phase) {
            case IDLE -> "Idle";
            case RECONNAISSANCE -> String.format("%s scan - %d/%d biomes (%d/%d chunks)",
                    profile.name(), countCompletedBiomes(), countTotalBiomes(),
                    totalChunksFound.get(), totalChunksNeeded.get());
            case REFINING -> "Refining data...";
            case COMPLETE -> "Complete";
        };
    }

    public void clear() {
        attemptedChunks.clear();
        remainingNeeds.clear();
        totalChunksNeeded.set(0);
        totalChunksFound.set(0);
    }
}