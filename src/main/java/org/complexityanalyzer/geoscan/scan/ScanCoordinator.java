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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.geoscan.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.task.ScanNotifier;
import org.complexityanalyzer.geoscan.task.ScanTask;
import org.complexityanalyzer.geoscan.task.WorldScanner;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ScanCoordinator {

    private final MinecraftServer server;
    private final GeoDatabase database;
    private final WorldScanner worldScanner;
    private final ScanNotifier notifier;

    private final AtomicLong sessionIdGenerator = new AtomicLong(0);

    private final AtomicReference<ScanSession> currentSession = new AtomicReference<>(null);

    public ScanCoordinator(
            MinecraftServer server,
            GeoDatabase database,
            WorldScanner worldScanner,
            ScanNotifier notifier
    ) {
        this.server = server;
        this.database = database;
        this.worldScanner = worldScanner;
        this.notifier = notifier;
    }

    public ScanSession createSession(int chunksPerBiome, ScanProfile profile) {
        invalidateCurrentSession();

        long sessionId = sessionIdGenerator.incrementAndGet();
        ScanSession session = new ScanSession(sessionId, profile, chunksPerBiome);
        this.currentSession.set(session);

        worldScanner.clearStopRequest();
        worldScanner.configureForScan(chunksPerBiome);

        ComplexityAnalyzer.LOGGER.info("Created scan session {} with profile {} for {} chunks/biome",
                sessionId, profile.name(), chunksPerBiome);

        return session;
    }

    public void invalidateCurrentSession() {
        ScanSession session = currentSession.getAndSet(null);
        if (session != null) {
            session.invalidate();
            ComplexityAnalyzer.LOGGER.debug("Invalidated session {}", session.getSessionId());
        }
    }

    public ScanSession getCurrentSession() {
        return currentSession.get();
    }

    public ObjectArrayList<ScanTask> prepareTasks(ScanSession session) {
        if (!session.isValid()) return ObjectArrayList.of();

        database.loadAll();
        ObjectArrayList<ScanTask> tasks = new ObjectArrayList<>();
        int chunksPerBiome = session.getChunksPerBiome();

        ComplexityAnalyzer.LOGGER.debug("[Prepare] Building scan tasks for {} chunks/biome", chunksPerBiome);

        for (ServerLevel level : server.getAllLevels()) {
            if (!session.isValid()) break;

            ResourceKey<Level> dimension = level.dimension();
            ComplexityAnalyzer.LOGGER.info("Scanning dimension: {}", dimension.location());

            ObjectOpenHashSet<ResourceKey<Biome>> biomes = getBiomesForDimension(level);
            ComplexityAnalyzer.LOGGER.debug("Found {} biomes in {}", biomes.size(), dimension.location());

            for (ResourceKey<Biome> biomeKey : biomes) {
                if (!session.isValid()) break;

                int existingChunks = getExistingChunkCount(dimension.location(), biomeKey.location());
                int chunksNeeded = chunksPerBiome - existingChunks;

                if (chunksNeeded > 0) tasks.add(new ScanTask(dimension, biomeKey, chunksNeeded));
            }
        }

        ComplexityAnalyzer.LOGGER.debug("[Prepare] Created {} scan tasks", tasks.size());
        tasks.sort(null);
        return tasks;
    }

    private ObjectOpenHashSet<ResourceKey<Biome>> getBiomesForDimension(ServerLevel level) {
        ObjectOpenHashSet<ResourceKey<Biome>> biomes = new ObjectOpenHashSet<>();
        var biomeSource = level.getChunkSource().getGenerator().getBiomeSource();

        ComplexityAnalyzer.LOGGER.info("[Prepare] Dimension {} biomeSource: {}",
                level.dimension().location(), biomeSource.getClass().getSimpleName());

        for (var holder : biomeSource.possibleBiomes()) {
            var key = holder.unwrapKey().orElse(null);
            if (key != null) {
                ComplexityAnalyzer.LOGGER.debug("[Prepare] {} -> {}", level.dimension().location(), key.location());
                biomes.add(key);
            }
        }

        ComplexityAnalyzer.LOGGER.info("[Prepare] {} has {} biomes", level.dimension().location(), biomes.size());

        return biomes;
    }

    private int getExistingChunkCount(ResourceLocation dimension, ResourceLocation biome) {
        int finalChunks = database.getBiomeData(dimension, biome)
                .map(BiomeScanData::getChunksScanned)
                .orElse(0);
        int reconChunks = database.countReconChunks(dimension, biome);
        return Math.max(finalChunks, reconChunks);
    }

    public boolean initializeSession(ScanSession session, ObjectArrayList<ScanTask> tasks, Object2ObjectMap<ResourceLocation, LongOpenHashSet> existingCoordinates) {
        if (!session.isValid() || tasks.isEmpty()) return false;

        database.setScanPhase(ScanMetadata.ScanPhase.RECONNAISSANCE);

        int totalChunks = 0;
        for (int i = 0, n = tasks.size(); i < n; i++) {
            ScanTask task = tasks.get(i);
            int needed = task.chunksToFind();
            totalChunks += needed;
            session.setBiomeNeed(task.dimension().location(), task.biome().location(), needed);
        }

        session.setTotalChunksNeeded(totalChunks);
        session.loadAttemptedChunks(existingCoordinates);

        notifier.logInfo(Component.translatable("complexityanalyzer.log.scan.starting_stats", tasks.size(), totalChunks).getString());

        return true;
    }

    public void finishReconnaissance(ScanSession session) {
        session.setPhase(ScanMetadata.ScanPhase.IDLE);
        session.clear();
        worldScanner.clearStopRequest();
        notifier.notifyReconnaissanceFinished(false);
    }

    public void stopScan() {
        ScanSession session = currentSession.getAndSet(null);
        worldScanner.requestStop();
        if (session != null) {
            session.invalidate();
            session.setPhase(ScanMetadata.ScanPhase.IDLE);
            session.clear();
        }
    }

    public void shutdown() {
        invalidateCurrentSession();
        worldScanner.shutdown();
    }
}
