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

package org.complexityanalyzer.geoscan.refinement;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.task.ScanNotifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

public class DataRefiner {

    private final GeoDatabase database;
    private final ScanNotifier notifier;

    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();

    public DataRefiner(GeoDatabase database, ScanNotifier notifier) {
        this.database = database;
        this.notifier = notifier;
    }

    public void refine(Runnable onComplete) {
        database.setScanPhase(ScanMetadata.ScanPhase.REFINING);
        notifier.sendSuccess(null, Component.translatable("complexityanalyzer.log.refiner.starting"));

        ComplexityAnalyzer.LOGGER.info("[Refiner] Submitting refinement task...");
        ExecutorService executor = ThreadPoolManager.getInstance().getComputePool();

        try {
            executor.execute(() -> {
                ComplexityAnalyzer.LOGGER.info("[Refiner] Task STARTED on thread: {}", Thread.currentThread().getName());
                Thread currentThread = Thread.currentThread();
                activeThreads.add(currentThread);

                try {
                    Map<ResourceLocation, Map<ResourceLocation, Path>> reconPaths = database.getAllReconFilePaths();
                    ComplexityAnalyzer.LOGGER.info("[Refiner] Found {} dimensions to refine", reconPaths.size());

                    if (reconPaths.isEmpty()) {
                        notifier.logWarn(Component.translatable("complexityanalyzer.log.refiner.no_data").getString());
                        finishRefinement(onComplete);
                        return;
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        handleCancellation("before heuristics");
                        return;
                    }

                    notifier.logInfo(Component.translatable("complexityanalyzer.log.refiner.heuristics").getString());
                    database.buildHeuristicFromFiles(reconPaths);

                    if (Thread.currentThread().isInterrupted()) {
                        handleCancellation("after heuristics");
                        return;
                    }

                    notifier.logInfo(Component.translatable("complexityanalyzer.log.refiner.clearing").getString());
                    database.clearFinalData();

                    if (Thread.currentThread().isInterrupted()) {
                        handleCancellation("after clearing");
                        return;
                    }

                    int totalBiomes = reconPaths.values().stream().mapToInt(Map::size).sum();
                    int processedBiomes = 0;

                    notifier.logInfo(Component.translatable("complexityanalyzer.log.refiner.dimensions_count", reconPaths.size()).getString());

                    for (Map.Entry<ResourceLocation, Map<ResourceLocation, Path>> dimEntry : reconPaths.entrySet()) {

                        if (Thread.currentThread().isInterrupted()) {
                            handleCancellation("during dimension loop");
                            return;
                        }

                        ResourceLocation dimension = dimEntry.getKey();
                        notifier.logInfo(Component.translatable("complexityanalyzer.log.refiner.dimension_stat", dimension.toString(), dimEntry.getValue().size()).getString());

                        for (Map.Entry<ResourceLocation, Path> biomeEntry : dimEntry.getValue().entrySet()) {

                            if (Thread.currentThread().isInterrupted()) {
                                handleCancellation("during biome loop");
                                return;
                            }

                            ResourceLocation biome = biomeEntry.getKey();
                            Path path = biomeEntry.getValue();
                            processedBiomes++;

                            try (Stream<ChunkSnapshot> stream = database.streamReconFile(path)) {
                                BiomeScanData finalData = database.refineRawDataFromStream(stream, dimension);
                                if (finalData.getChunksScanned() > 0)
                                    database.saveBiomeData(dimension, biome, finalData);

                                ComplexityAnalyzer.LOGGER.debug("[Refiner] Refined {}/{}: {} in {} ({} chunks)", processedBiomes, totalBiomes, biome, dimension, finalData.getChunksScanned());
                            } catch (Exception e) {
                                ComplexityAnalyzer.LOGGER.error("Error refining {} in {}", biome, dimension, e);
                            }
                        }
                    }

                    notifier.logInfo(Component.translatable("complexityanalyzer.log.refiner.success", totalBiomes).getString());
                    finishRefinement(onComplete);

                } catch (IOException e) {
                    notifier.logError(Component.translatable("complexityanalyzer.log.refiner.error").getString(), e);
                    database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.error("[Refiner] Unexpected error!", e);
                    database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
                } finally {
                    activeThreads.remove(currentThread);
                    ComplexityAnalyzer.LOGGER.info("[Refiner] Task FINISHED");
                }
            });

            ComplexityAnalyzer.LOGGER.info("[Refiner] Task submitted successfully");

        } catch (RejectedExecutionException e) {
            ComplexityAnalyzer.LOGGER.error("[Refiner] Executor REJECTED the task!", e);
            database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
        }
    }

    private void finishRefinement(Runnable onComplete) {
        notifier.logInfo(Component.translatable("complexityanalyzer.log.refiner.finalizing").getString());
        database.setScanPhase(ScanMetadata.ScanPhase.COMPLETE);
        database.loadAll();
        notifier.notifyRefinementFinished();

        if (onComplete != null) try {
            onComplete.run();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Refiner] Error in onComplete callback!", e);
        }
    }

    private void handleCancellation(String stage) {
        ComplexityAnalyzer.LOGGER.warn("[Refiner] Cancelled at stage: {}", stage);
        database.setScanPhase(ScanMetadata.ScanPhase.IDLE);
    }

    public void shutdown() {
        ComplexityAnalyzer.LOGGER.info("[Refiner] Shutting down, interrupting {} threads", activeThreads.size());
        for (Thread thread : activeThreads) thread.interrupt();
        activeThreads.clear();
    }
}