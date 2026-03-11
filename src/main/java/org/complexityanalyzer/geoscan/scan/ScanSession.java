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

package org.complexityanalyzer.geoscan.scan;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.task.ScanTask;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ScanSession {

    private final long sessionId;
    private final ScanProfile profile;
    private final int chunksPerBiome;

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger totalTasks = new AtomicInteger(0);
    private final AtomicInteger tasksCompleted = new AtomicInteger(0);
    private final AtomicInteger totalChunksNeeded = new AtomicInteger(0);
    private final AtomicInteger totalChunksFound = new AtomicInteger(0);

    private final Queue<ScanTask> taskQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> attemptedChunks = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, AtomicInteger> remainingNeeds = new ConcurrentHashMap<>();

    private volatile ScanMetadata.ScanPhase phase = ScanMetadata.ScanPhase.RECONNAISSANCE;

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

    public ScanTask pollTask() {
        return taskQueue.poll();
    }

    public void addTasks(Iterable<ScanTask> tasks) {
        for (ScanTask task : tasks) {
            taskQueue.offer(task);
        }
    }

    public void setTotalTasks(int total) {
        totalTasks.set(total);
    }

    public int getTotalTasks() {
        return totalTasks.get();
    }

    public int incrementTasksCompleted() {
        return tasksCompleted.incrementAndGet();
    }

    public int getTasksCompleted() {
        return tasksCompleted.get();
    }

    public void setTotalChunksNeeded(int total) {
        totalChunksNeeded.set(total);
    }

    public void setBiomeNeed(ResourceLocation dim, ResourceLocation biome, int needed) {
        remainingNeeds.put(dim.toString() + "|" + biome.toString(), new AtomicInteger(needed));
    }

    public boolean hasBiomeNeed(ResourceLocation dim, ResourceLocation biome) {
        String key = dim.toString() + "|" + biome.toString();
        AtomicInteger remaining = remainingNeeds.get(key);
        return remaining != null && remaining.get() > 0;
    }

    public boolean consumeChunkNeed(ResourceLocation dim, ResourceLocation biome) {
        String key = dim.toString() + "|" + biome.toString();
        AtomicInteger remaining = remainingNeeds.get(key);
        if (remaining != null) {
            int current = remaining.get();
            while (current > 0) {
                if (remaining.compareAndSet(current, current - 1)) {
                    totalChunksFound.incrementAndGet();
                    return true;
                }
                current = remaining.get();
            }
        }
        return false;
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
                    profile.name(), tasksCompleted.get(), totalTasks.get(), totalChunksFound.get(), totalChunksNeeded.get());
            case REFINING -> "Refining data...";
            case COMPLETE -> "Complete";
        };
    }

    public void clear() {
        taskQueue.clear();
        attemptedChunks.clear();
        remainingNeeds.clear();
        totalTasks.set(0);
        tasksCompleted.set(0);
        totalChunksNeeded.set(0);
        totalChunksFound.set(0);
    }
}