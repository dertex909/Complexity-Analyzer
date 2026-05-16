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

package org.complexityanalyzer.core;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.ComplexityCalculator;
import org.complexityanalyzer.analyzer.DepthAnalyzer;
import org.complexityanalyzer.analyzer.MachineRegistry;
import org.complexityanalyzer.analyzer.SourcePathAnalyzer;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.providers.BlockPropertyProvider;
import org.complexityanalyzer.analyzer.resource.providers.DimensionRarityAnalyzer;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.providers.MobRarityCalculator;
import org.complexityanalyzer.analyzer.resource.sources.*;
import org.complexityanalyzer.analyzer.solver.SccCondensedSolver;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.bytecode.BytecodeAnalysisEngine;
import org.complexityanalyzer.cache.ComplexityCache;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.graph.GraphBuilder;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class AnalysisEngine {
    public enum State {IDLE, ANALYZING, READY, FAILED}

    private final AtomicReference<State> currentState = new AtomicReference<>(State.IDLE);
    private final AtomicReference<Future<?>> currentAnalysisTask = new AtomicReference<>(null);
    private final AtomicBoolean analysisCancelled = new AtomicBoolean(false);
    private final AtomicBoolean isReloading = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final ReentrantLock stateLock = new ReentrantLock();
    private final ReentrantLock geoManagerLock = new ReentrantLock();
    private final ComplexityCache complexityCache;

    private volatile RecipeGraph graph;
    private volatile SourceManager sourceManager;
    private volatile ComplexityCalculator calculator;
    private volatile DepthAnalyzer depthAnalyzer;
    private volatile GeoDatabase geoDatabase;
    private volatile BlockPropertyProvider blockPropProvider;
    private volatile MobPropertyProvider mobPropProvider;
    private volatile GeoAnalysisManager geoManager;
    private volatile MobRarityCalculator mobRarityCalculator;
    private volatile MachineRegistry machineRegistry;
    private volatile BytecodeAnalysisEngine bytecodeEngine;
    private volatile MinecraftServer server;

    private static class InstanceHolder {
        private static final AnalysisEngine INSTANCE = new AnalysisEngine();
    }

    public static AnalysisEngine getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private AnalysisEngine() {
        this.complexityCache = new ComplexityCache();
    }

    @Nullable
    public SolverResult getSolverResult() {
        ComplexityCalculator calc = this.calculator;
        return (calc != null) ? calc.getSolverResult() : null;
    }

    public void initializeAsync(Level level, Runnable onComplete) {
        if (isShuttingDown.get()) {
            ComplexityAnalyzer.LOGGER.warn("Cannot initialize: engine is shutting down");
            return;
        }

        State current = currentState.get();
        if (current == State.FAILED) {
            stateLock.lock();
            try {
                if (currentState.get() == State.FAILED) {
                    currentState.set(State.IDLE);
                    ComplexityAnalyzer.LOGGER.info("Reset from FAILED state to IDLE for retry");
                }
            } finally {
                stateLock.unlock();
            }
        }

        if (!currentState.compareAndSet(State.IDLE, State.ANALYZING)) {
            current = currentState.get();
            if (current == State.READY && isReady()) {
                safeRunCallback(onComplete);
            } else if (current == State.ANALYZING) {
                ComplexityAnalyzer.LOGGER.debug("Analysis already in progress, ignoring duplicate request");
            }
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            currentState.set(State.FAILED);
            ComplexityAnalyzer.LOGGER.error("Cannot initialize: not a ServerLevel");
            return;
        }

        this.server = serverLevel.getServer();
        analysisCancelled.set(false);

        ExecutorService executor = ThreadPoolManager.getInstance().getComputePool();

        ComplexityAnalyzer.LOGGER.info("Starting background analysis with {} threads...",
                ThreadPoolManager.getInstance().getParallelism());

        Future<?> task = executor.submit(() -> {
            try {
                if (isInterrupted()) {
                    restoreIdleState();
                    return;
                }

                ComplexityAnalyzer.LOGGER.info("Initializing MachineRegistry...");
                this.machineRegistry = new MachineRegistry();
                this.machineRegistry.initialize();

                ComplexityAnalyzer.LOGGER.info("Building recipe graph...");
                this.graph = GraphBuilder.buildFromWorld(level);

                ComplexityAnalyzer.LOGGER.info("Running bytecode semantic analysis...");
                this.bytecodeEngine = new BytecodeAnalysisEngine(serverLevel.getServer().getWorldPath(LevelResource.ROOT));
                var bytecodeResult = this.bytecodeEngine.analyzeAndMerge(this.graph);
                if (bytecodeResult.hasResults()) {
                    ComplexityAnalyzer.LOGGER.info("Bytecode analysis: {} events, {} machines, {} edges ({}ms)",
                            bytecodeResult.events.size(), bytecodeResult.machines.size(),
                            bytecodeResult.edges.size(), bytecodeResult.durationMs);
                }

                ComplexityAnalyzer.LOGGER.info("=== [State: ANALYZING] Starting analysis ===");

                if (isInterrupted()) {
                    restoreIdleState();
                    return;
                }

                initializeCoreProviders(serverLevel);

                if (isInterrupted()) {
                    restoreIdleState();
                    return;
                }

                initializeResourceSources(serverLevel);

                if (isInterrupted()) {
                    restoreIdleState();
                    return;
                }

                performComplexityCalculation();

                if (isInterrupted()) {
                    restoreIdleState();
                    return;
                }

                ComplexityAnalyzer.LOGGER.info("=== [State: READY] Analysis complete. Mod is operational. ===");
                currentState.set(State.READY);

                safeRunCallback(onComplete);

            } catch (Exception e) {
                if (!isInterrupted()) {
                    ComplexityAnalyzer.LOGGER.error("Critical error during analysis initialization", e);
                    currentState.set(State.FAILED);
                } else {
                    restoreIdleState();
                }
            }
        });

        currentAnalysisTask.set(task);
    }

    private void performComplexityCalculation() {
        ComplexityAnalyzer.LOGGER.info("Starting complexity calculation...");

        recalculateComplexity();

        if (isInterrupted()) {
            ComplexityAnalyzer.LOGGER.info("Analysis was cancelled during calculation.");
            return;
        }

        try {
            MinecraftServer srv = this.server;
            if (srv != null && !isInterrupted()) {
                createGeoManager(srv);
                GeoAnalysisManager geoMgr = getGeoManager();
                if (geoMgr != null && !isInterrupted()) geoMgr.startInitialScanIfNeeded();
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to create or start GeoAnalysisManager", e);
        }

        ComplexityAnalyzer.LOGGER.info("Complexity calculation complete.");
    }

    private boolean isInterrupted() {
        return analysisCancelled.get() || isShuttingDown.get() || Thread.currentThread().isInterrupted();
    }

    private void safeRunCallback(Runnable callback) {
        if (callback == null) return;
        try {
            callback.run();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Error in completion callback", e);
        }
    }

    private void restoreIdleState() {
        stateLock.lock();
        try {
            clearDataInternal();
            currentState.set(State.IDLE);
        } finally {
            stateLock.unlock();
        }
    }

    private void initializeCoreProviders(ServerLevel serverLevel) {
        this.blockPropProvider = new BlockPropertyProvider();
        this.blockPropProvider.initialize();

        this.mobPropProvider = new MobPropertyProvider();
        this.mobPropProvider.initialize();

        DimensionRarityAnalyzer dimensionAnalyzer = new DimensionRarityAnalyzer(serverLevel);
        this.mobRarityCalculator = new MobRarityCalculator(dimensionAnalyzer);
        this.mobPropProvider.setRarityCalculator(this.mobRarityCalculator);

        this.geoDatabase = new GeoDatabase(serverLevel.getServer());
        this.geoDatabase.loadAll();
    }

    private void initializeResourceSources(ServerLevel serverLevel) {
        ObjectList<IResourceSource> initialSources = new ObjectArrayList<>();

        initialSources.add(new UniversalLootSource());
        initialSources.add(new MobDropSource(this.mobPropProvider, serverLevel));
        initialSources.add(new BlockBreakAsRecipeSource(this.geoDatabase));
        initialSources.add(new FarmingSource());
        initialSources.add(new VillagerTradeSource());
        initialSources.add(new PassiveProductionSource());
        initialSources.add(new HardcodedSourcesProvider());

        this.sourceManager = new SourceManager(initialSources);

        ComplexityAnalyzer.LOGGER.info("Resource sources configured with {} providers. Initializing all...", initialSources.size());
        this.sourceManager.initialize(serverLevel);

        if (ComplexityAnalyzer.LOGGER.isDebugEnabled()) for (IResourceSource source : initialSources) {
            ComplexityAnalyzer.LOGGER.debug("  - {} (priority: {})", source.getName(), source.getPriority());
        }

        ComplexityAnalyzer.LOGGER.info("All resource sources initialized successfully.");
    }

    public void recalculateComplexity() {
        RecipeGraph currentGraph = this.graph;
        SourceManager currentSourceManager = this.sourceManager;

        if (isInterrupted() || currentGraph == null || currentSourceManager == null) return;

        ComplexityAnalyzer.LOGGER.info("Recalculating all complexity data...");

        this.complexityCache.clear();

        SourcePathAnalyzer pathAnalyzer = new SourcePathAnalyzer(currentGraph, currentSourceManager);
        pathAnalyzer.findItemsWithBasePath();

        if (isInterrupted()) return;

        SccCondensedSolver solver = new SccCondensedSolver(currentGraph, currentSourceManager, this.machineRegistry);
        SolverResult solverResult = solver.solve();

        if (isInterrupted()) {
            ComplexityAnalyzer.LOGGER.info("Analysis was cancelled after solver finished.");
            return;
        }

        this.depthAnalyzer = new DepthAnalyzer(currentGraph, currentSourceManager);
        this.depthAnalyzer.setOptimalRecipes(solverResult.optimalRecipes());

        this.calculator = new ComplexityCalculator(currentGraph, this.depthAnalyzer, solverResult, currentSourceManager);

        SharedSuggestions.refresh();
        ComplexityAnalyzer.LOGGER.info("All systems refreshed with new data.");
    }

    public void onGeoScanFinished() {
        if (!isReady()) {
            ComplexityAnalyzer.LOGGER.warn("onGeoScanFinished called while engine not ready. Ignoring.");
            return;
        }

        if (isShuttingDown.get()) {
            ComplexityAnalyzer.LOGGER.warn("onGeoScanFinished called during shutdown. Ignoring.");
            return;
        }

        stateLock.lock();
        try {
            if (!isReady() || isShuttingDown.get()) return;
            ComplexityAnalyzer.LOGGER.info("Geo-scan finished. Recalculating complexity...");
            recalculateComplexity();
        } finally {
            stateLock.unlock();
        }
    }

    public ComplexityCache getComplexityCache() {
        return this.complexityCache;
    }

    private void clearDataInternal() {
        this.graph = null;
        this.sourceManager = null;
        this.calculator = null;
        this.depthAnalyzer = null;
        this.machineRegistry = null;
        this.complexityCache.clear();
        this.server = null;
    }

    public void createGeoManager(MinecraftServer server) {
        if (isShuttingDown.get()) return;

        geoManagerLock.lock();
        try {
            if (isShuttingDown.get()) return;
            GeoAnalysisManager oldManager = this.geoManager;
            if (oldManager != null) oldManager.shutdown();
            GeoDatabase geoDB = this.geoDatabase;
            if (geoDB != null) this.geoManager = new GeoAnalysisManager(server, geoDB, this);
        } finally {
            geoManagerLock.unlock();
        }
    }

    public Executor getBackgroundExecutor() {
        if (isShuttingDown.get()) return null;
        return ThreadPoolManager.getInstance().getComputePool();
    }

    public void clearGeoDatabase() {
        if (!isReady()) {
            ComplexityAnalyzer.LOGGER.warn("Cannot clear GeoDatabase: engine not ready. Current state: {}", currentState.get());
            return;
        }

        if (isShuttingDown.get()) return;

        stateLock.lock();
        try {
            if (!isReady() || isShuttingDown.get()) return;

            GeoDatabase geoDB = this.geoDatabase;
            if (geoDB != null) {
                geoDB.clear();
                ComplexityAnalyzer.LOGGER.info("GeoDatabase cleared. Recalculating complexity...");
                recalculateComplexity();
            }
        } finally {
            stateLock.unlock();
        }
    }

    public boolean isReady() {
        return currentState.get() == State.READY && !isShuttingDown.get();
    }

    public RecipeGraph getGraph() {
        return this.graph;
    }

    @Nullable
    public SourceManager getSourceManager() {
        return this.sourceManager;
    }

    public State getCurrentState() {
        return currentState.get();
    }

    @Nullable
    public ItemComplexity getComplexityResult(Item item) {
        ComplexityCalculator calc = this.calculator;
        if (calc == null || !isReady()) return null;
        return calc.getOrCalculateComplexity(item);
    }

    @Nullable
    public DepthAnalyzer getDepthAnalyzer() {
        return this.depthAnalyzer;
    }

    public double getComplexity(Item item) {
        var result = getComplexityResult(item);
        return (result != null) ? result.getComplexity() : -1.0;
    }

    public int getUsageCount(Item item) {
        RecipeGraph currentGraph = this.graph;
        return (currentGraph != null && isReady()) ? currentGraph.getUsageCount(item) : 0;
    }

    public boolean hasRecipe(Item item) {
        RecipeGraph currentGraph = this.graph;
        return currentGraph != null && isReady() && currentGraph.hasRecipe(item);
    }

    public ObjectList<BaseResourceData> findAllSourcesForItem(Item item) {
        SourceManager sm = this.sourceManager;
        return (sm != null && isReady()) ? sm.findAllSources(item) : ObjectLists.emptyList();
    }

    @Nullable
    public BaseResourceData getBaseResourceData(Item item) {
        SourceManager sm = this.sourceManager;
        return (sm != null && isReady()) ? sm.analyze(item) : null;
    }

    public void reloadAsync(Level level) {
        if (!isReloading.compareAndSet(false, true)) {
            ComplexityAnalyzer.LOGGER.warn("Reload already in progress. Ignoring.");
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.error("Cannot reload: not a ServerLevel");
            isReloading.set(false);
            return;
        }

        MinecraftServer srv = serverLevel.getServer();

        ComplexityAnalyzer.LOGGER.info("Reload requested. Scheduling on server thread...");

        srv.execute(() -> {
            try {
                performReloadOnServerThread(serverLevel);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Critical error during reload", e);
                currentState.set(State.FAILED);
                isReloading.set(false);
            }
        });
    }

    private void performReloadOnServerThread(ServerLevel serverLevel) {
        ComplexityAnalyzer.LOGGER.info("=== RELOAD Phase 1: Shutdown ===");
        analysisCancelled.set(true);
        Future<?> currentTask = currentAnalysisTask.getAndSet(null);
        if (currentTask != null && !currentTask.isDone()) currentTask.cancel(true);

        geoManagerLock.lock();
        try {
            GeoAnalysisManager geoMgr = this.geoManager;
            if (geoMgr != null) {
                geoMgr.shutdown();
                this.geoManager = null;
            }
        } finally {
            geoManagerLock.unlock();
        }

        stateLock.lock();
        try {
            clearDataInternal();
            currentState.set(State.IDLE);
        } finally {
            stateLock.unlock();
        }

        clearAllCaches();
        ComplexityConfig.resetThreadCache();

        ComplexityAnalyzer.LOGGER.info("=== RELOAD Phase 2: Shutdown Thread Pools ===");
        ThreadPoolManager.getInstance().shutdown();
        ComplexityAnalyzer.LOGGER.info("=== RELOAD Phase 3: Restart ===");
        ThreadPoolManager.reinitialize();

        isShuttingDown.set(false);
        analysisCancelled.set(false);
        isReloading.set(false);

        ComplexityAnalyzer.LOGGER.info("Starting fresh analysis...");
        initializeAsync(serverLevel, () -> {
            ComplexityAnalyzer.LOGGER.info("✓ Reload complete. System operational.");
            String modVersion = ModList.get()
                    .getModContainerById(ComplexityAnalyzer.MODID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
            CabinBackgroundService.getInstance().regenerateAsync(serverLevel.getServer(), this, modVersion);
        });
    }

    public void shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            ComplexityAnalyzer.LOGGER.debug("Shutdown already in progress");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Shutdown requested for AnalysisEngine.");

        analysisCancelled.set(true);

        Future<?> currentTask = currentAnalysisTask.getAndSet(null);
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
            try {
                currentTask.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }

        geoManagerLock.lock();
        try {
            GeoAnalysisManager geoMgr = this.geoManager;
            if (geoMgr != null) {
                geoMgr.shutdown();
                this.geoManager = null;
            }
        } finally {
            geoManagerLock.unlock();
        }

        stateLock.lock();
        try {
            clearDataInternal();
            currentState.set(State.IDLE);
            ComplexityAnalyzer.LOGGER.info("AnalysisEngine state reset to IDLE.");
        } finally {
            stateLock.unlock();
        }

        isShuttingDown.set(false);
    }

    public void enterEmergencyState(String reason) {
        stateLock.lock();
        try {
            ComplexityAnalyzer.LOGGER.warn("!!! EMERGENCY CLEANUP TRIGGERED: {} !!!", reason);

            geoManagerLock.lock();
            try {
                if (this.geoManager != null) {
                    this.geoManager.shutdown();
                    this.geoManager = null;
                }
            } finally {
                geoManagerLock.unlock();
            }

            clearAllCaches();
            ComplexityAnalyzer.LOGGER.warn("Emergency cleanup complete. Geo-scan halted, caches cleared. Engine remains active.");
        } finally {
            stateLock.unlock();
        }
    }

    public void shutdownCompletely() {
        shutdown();
        ThreadPoolManager.getInstance().shutdown();
        ComplexityAnalyzer.LOGGER.info("AnalysisEngine and ThreadPoolManager fully shutdown.");
    }

    private void clearAllCaches() {
        ComplexityAnalyzer.LOGGER.info("Clearing all analysis caches...");

        complexityCache.clear();

        MobRarityCalculator calc = mobRarityCalculator;
        if (calc != null) calc.clearCache();

        ComplexityAnalyzer.LOGGER.info("All caches cleared.");
    }

    @Nullable
    public MobDropSource getMobDropSource() {
        SourceManager sm = this.sourceManager;
        if (sm == null) return null;
        return sm.getSourceByType(MobDropSource.class);
    }

    @Nullable
    public MobPropertyProvider getMobPropertyProvider() {
        return this.mobPropProvider;
    }

    @Nullable
    public GeoAnalysisManager getGeoManager() {
        return this.geoManager;
    }

    @Nullable
    public MachineRegistry getMachineRegistry() {
        return this.machineRegistry;
    }

    public org.complexityanalyzer.bytecode.BytecodeAnalysisEngine getBytecodeEngine() {
        return this.bytecodeEngine;
    }

    public record EngineStats(State state, int itemCount, int recipeCount, int baseResourceCount) {
    }

    public EngineStats getStats() {
        if (!isReady()) return new EngineStats(currentState.get(), 0, 0, 0);

        RecipeGraph currentGraph = this.graph;
        if (currentGraph == null) return new EngineStats(currentState.get(), 0, 0, 0);

        return new EngineStats(currentState.get(), currentGraph.getAllItems().size(), currentGraph.getTotalRecipeCount(), 0);
    }

    @Nullable
    public <T extends IResourceSource> T getSourceByType(Class<T> type) {
        SourceManager sm = this.sourceManager;
        if (sm == null) return null;
        return sm.getSourceByType(type);
    }
}