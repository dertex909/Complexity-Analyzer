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

package org.complexityanalyzer.geoscan.refinement;

import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.scan.ScanSession;
import org.complexityanalyzer.geoscan.task.ScanNotifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public class DataRefiner {

    private final GeoDatabase database;
    private final ScanNotifier notifier;
    private final Executor backgroundExecutor;

    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();

    public DataRefiner(GeoDatabase database, ScanNotifier notifier, Executor backgroundExecutor) {
        this.database = database;
        this.notifier = notifier;
        this.backgroundExecutor = backgroundExecutor;
    }

    public void refine(ScanSession session, Runnable onComplete) {
        session.setPhase(ScanMetadata.ScanPhase.REFINING);
        database.setScanPhase(ScanMetadata.ScanPhase.REFINING);

        notifier.sendSuccess(null, "Reconnaissance complete! Starting refinement...");

        backgroundExecutor.execute(() -> {
            Thread currentThread = Thread.currentThread();
            activeThreads.add(currentThread);

            try {
                if (!session.isValid()) return;

                Map<ResourceLocation, Map<ResourceLocation, Path>> reconPaths = database.getAllReconFilePaths();

                if (reconPaths.isEmpty()) {
                    notifier.logWarn("No reconnaissance data found.");
                    finishRefinement(session, onComplete);
                    return;
                }

                if (!session.isValid()) return;
                notifier.logInfo("Building heuristics from reconnaissance data...");
                database.buildHeuristicFromFiles(reconPaths);
                if (!session.isValid()) return;
                notifier.logInfo("Clearing old final data...");
                database.clearFinalData();
                if (!session.isValid()) return;
                notifier.logInfo("Refining data for each biome...");
                for (Map.Entry<ResourceLocation, Map<ResourceLocation, Path>> dimEntry : reconPaths.entrySet()) {
                    if (!session.isValid()) break;

                    ResourceLocation dimension = dimEntry.getKey();

                    for (Map.Entry<ResourceLocation, Path> biomeEntry : dimEntry.getValue().entrySet()) {
                        if (!session.isValid()) break;

                        ResourceLocation biome = biomeEntry.getKey();
                        Path path = biomeEntry.getValue();

                        try (Stream<ChunkSnapshot> stream = database.streamReconFile(path)) {
                            BiomeScanData finalData = database.refineRawDataFromStream(stream, dimension);
                            if (finalData.getChunksScanned() > 0) database.saveBiomeData(dimension, biome, finalData);
                        } catch (Exception e) {
                            if (session.isValid())
                                ComplexityAnalyzer.LOGGER.error("Error refining {} in {}", biome, dimension, e);
                        }
                    }
                }

                if (session.isValid()) finishRefinement(session, onComplete);
            } catch (IOException e) {
                if (session.isValid()) notifier.logError("Critical error during refinement!", e);
                session.setPhase(ScanMetadata.ScanPhase.IDLE);
                database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
            } finally {
                activeThreads.remove(currentThread);
            }
        });
    }

    private void finishRefinement(ScanSession session, Runnable onComplete) {
        notifier.logInfo("Finalizing refinement...");
        session.setPhase(ScanMetadata.ScanPhase.COMPLETE);
        database.setScanPhase(ScanMetadata.ScanPhase.COMPLETE);
        database.loadAll();
        notifier.notifyRefinementFinished();
        if (onComplete != null) onComplete.run();
    }

    public void shutdown() {
        for (Thread thread : activeThreads) {
            thread.interrupt();
        }
        activeThreads.clear();
    }
}