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

package org.complexityanalyzer.geoscan.refinement;

import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.util.Scope;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.complexityanalyzer.util.LogMarkers.GEO_DATA_REFINER;

public class DataRefiner {

    private final GeoDatabase database;
    private final OutputManager output;

    private final ConcurrentHashMap<Thread, Boolean> activeThreads = new ConcurrentHashMap<>();
    private final ExecutorService refinementExecutor = ThreadPoolManager.getInstance().getComputePool();

    public DataRefiner(GeoDatabase database, OutputManager output) {
        this.database = database;
        this.output = output;
    }

    public void refine(Runnable onComplete) {
        database.setScanPhase(ScanMetadata.ScanPhase.REFINING);
        ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Reconnaissance complete. Starting refinement...");

        try {
            ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Submitting refinement task...");
            refinementExecutor.execute(() -> {
                ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Task STARTED on thread: {}", Thread.currentThread().getName());
                var currentThread = Thread.currentThread();
                activeThreads.put(currentThread, Boolean.TRUE);

                try (var ignored = Scope.of(() -> {
                    activeThreads.remove(currentThread);
                    ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Task FINISHED");
                })) {
                    var reconPaths = database.getAllReconFilePaths();
                    ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Found {} dimensions to refine", reconPaths.size());

                    if (reconPaths.isEmpty()) {
                        ComplexityAnalyzer.LOGGER.warn(GEO_DATA_REFINER, "No reconnaissance data found.");
                        finishRefinement(onComplete);
                        return;
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        handleCancellation("before heuristics");
                        return;
                    }

                    ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Building heuristics from reconnaissance data...");
                    database.buildHeuristicFromFiles(reconPaths);

                    if (Thread.currentThread().isInterrupted()) {
                        handleCancellation("after heuristics");
                        return;
                    }

                    ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Clearing old final data...");
                    database.clearFinalData();

                    if (Thread.currentThread().isInterrupted()) {
                        handleCancellation("after clearing");
                        return;
                    }

                    int totalBiomes = 0;
                    for (var biomesMap : reconPaths.values()) totalBiomes += biomesMap.size();

                    int processedBiomes = 0;

                    ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Refining data for {} dimensions...", reconPaths.size());

                    for (var dimEntry : Object2ObjectMaps.fastIterable(reconPaths)) {

                        if (Thread.currentThread().isInterrupted()) {
                            handleCancellation("during dimension loop");
                            return;
                        }

                        var dimension = dimEntry.getKey();
                        var biomesMap = dimEntry.getValue();
                        ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Refining dimension: {} ({} biomes)", dimension, biomesMap.size());

                        for (var biomeEntry : Object2ObjectMaps.fastIterable(biomesMap)) {

                            if (Thread.currentThread().isInterrupted()) {
                                handleCancellation("during biome loop");
                                return;
                            }

                            var biome = biomeEntry.getKey();
                            var path = biomeEntry.getValue();
                            processedBiomes++;

                            try {
                                var snapshots = database.readReconFile(path);
                                var finData = database.refineRawData(snapshots, dimension);
                                if (finData.getChunksScanned() > 0) database.saveBiomeData(dimension, biome, finData);
                                ComplexityAnalyzer.LOGGER.debug(GEO_DATA_REFINER, "Refined {}/{}: {} in {} ({} chunks)", processedBiomes, totalBiomes, biome, dimension, finData.getChunksScanned());
                            } catch (Exception e) {
                                ComplexityAnalyzer.LOGGER.error(GEO_DATA_REFINER, "Error refining {} in {}", biome, dimension, e);
                            }
                        }
                    }

                    ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "All {} biomes refined successfully.", totalBiomes);
                    finishRefinement(onComplete);

                } catch (IOException e) {
                    ComplexityAnalyzer.LOGGER.error(GEO_DATA_REFINER, "Critical error during refinement.", e);
                    database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.error(GEO_DATA_REFINER, "Unexpected error!", e);
                    database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
                }
            });

            ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Task submitted successfully");

        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.error(GEO_DATA_REFINER, "Executor REJECTED the task!", e);
            database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
        }
    }

    private void finishRefinement(Runnable onComplete) {
        ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Finalizing refinement...");
        database.setScanPhase(ScanMetadata.ScanPhase.COMPLETE);
        database.loadAll();
        output.broadcast(Component.translatable("complexityanalyzer.notifier.complete").withStyle(ChatFormatting.GREEN));
        ComplexityAnalyzer.LOGGER.info(GEO_DATA_REFINER, "Global refinement complete. GeoDatabase is now fully operational.");

        if (onComplete != null) try {
            onComplete.run();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error(GEO_DATA_REFINER, "Error in onComplete callback!", e);
        }
    }

    private void handleCancellation(String stage) {
        ComplexityAnalyzer.LOGGER.warn(GEO_DATA_REFINER, "Cancelled at stage: {}", stage);
        database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
    }

    public void shutdown() {
        ComplexityAnalyzer.LOGGER.debug(GEO_DATA_REFINER, "Shutting down, interrupting {} threads", activeThreads.size());
        for (var thread : activeThreads.keySet()) thread.interrupt();
        activeThreads.clear();
    }
}