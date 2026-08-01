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

package org.complexityanalyzer.core;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.ComplexityCalculator;
import org.complexityanalyzer.analyzer.DepthAnalyzer;
import org.complexityanalyzer.analyzer.solver.SccCondensedSolver;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.api.ComplexityAnalyzerAPI;
import org.complexityanalyzer.api.event.ComplexityAnalysisCompleteEvent;
import org.complexityanalyzer.api.event.ComplexityRegistrationEvent;
import org.complexityanalyzer.cache.ComplexityCache;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.graph.GraphBuilder;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.harvest.machine.MachineRegistry;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.SourceManager;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.resource.sources.*;
import org.complexityanalyzer.util.ProbeScope;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class AnalysisEngine {
    private final AtomicReference<State> currentState = new AtomicReference<>(State.IDLE);
    private final AtomicReference<Future<?>> currentAnalysisTask = new AtomicReference<>(null);
    private final AtomicLong analysisGeneration = new AtomicLong(0);
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
    private volatile MobPropertyProvider mobPropProvider;
    private volatile GeoAnalysisManager geoManager;
    private volatile MachineRegistry machineRegistry;
    private volatile MinecraftServer server;

    private AnalysisEngine() {
        this.complexityCache = new ComplexityCache();
    }

    public static AnalysisEngine getInstance() {
        return InstanceHolder.INSTANCE;
    }

    @Nullable
    public SolverResult getSolverResult() {
        var calc = this.calculator;
        return (calc != null) ? calc.getSolverResult() : null;
    }

    public void initializeAsync(Level level, Runnable onComplete) {
        if (isShuttingDown.get()) {
            ComplexityAnalyzer.LOGGER.warn("Cannot initialize: engine is shutting down");
            return;
        }

        resetFailedToIdle();

        if (!currentState.compareAndSet(State.IDLE, State.ANALYZING)) {
            if (isReady()) {
                safeRunCallback(onComplete);
            } else if (currentState.get() == State.ANALYZING) {
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
        submitBuild(serverLevel, true, onComplete);
    }

    private void resetFailedToIdle() {
        if (currentState.get() != State.FAILED) return;
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

    private void submitBuild(ServerLevel level, boolean fullRebuild, Runnable onComplete) {
        final long gen = analysisGeneration.incrementAndGet();
        ComplexityAnalyzer.LOGGER.info("Starting {} on {} threads...", fullRebuild ? "full build" : "geo refresh",
                ThreadPoolManager.getInstance().getParallelism());
        var task = ThreadPoolManager.getInstance().getComputePool().submit(() -> runBuild(level, fullRebuild, gen, onComplete));
        currentAnalysisTask.set(task);
    }

    private void runBuild(ServerLevel level, boolean fullRebuild, long gen, Runnable onComplete) {
        try (var ignored = ProbeScope.open()) {
            if (!setStateIfCurrent(gen, State.ANALYZING, false)) return;

            if (fullRebuild) {
                buildGraph(level);
                if (abort(gen)) return;
                initializeCoreProviders(level);
                if (abort(gen)) return;
            }

            initializeResourceSources(level);
            if (abort(gen)) return;

            recalculateComplexity();
            if (abort(gen)) return;

            if (fullRebuild) startGeoScanIfNeeded();

            if (setStateIfCurrent(gen, State.READY, false)) {
                ComplexityAnalyzer.LOGGER.info("=== [State: READY] Analysis complete. Mod is operational. ===");
                safeRunCallback(onComplete);
                fireAnalysisComplete(!fullRebuild);
            }
        } catch (Throwable e) {
            if (isInterrupted() || isSuperseded(gen)) {
                restoreIdleState(gen);
            } else {
                ComplexityAnalyzer.LOGGER.error("Critical error during analysis", e);
                setStateIfCurrent(gen, State.FAILED, false);
            }
        }
    }

    private void fireAnalysisComplete(boolean reload) {
        var api = ComplexityAnalyzerAPI.Holder.peek();
        if (api == null) return;
        this.server.execute(() -> {
            try {
                NeoForge.EVENT_BUS.post(new ComplexityAnalysisCompleteEvent(api, reload));
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.error("An addon threw during ComplexityAnalysisCompleteEvent.", t);
            }
        });
    }

    private boolean abort(long gen) {
        if (!isInterrupted() && !isSuperseded(gen)) return false;
        restoreIdleState(gen);
        return true;
    }

    private boolean isSuperseded(long gen) {
        return analysisGeneration.get() != gen;
    }

    private void buildGraph(ServerLevel level) {
        ComplexityAnalyzer.LOGGER.info("Initializing MachineRegistry...");
        this.machineRegistry = new MachineRegistry();
        this.machineRegistry.initialize(level.getServer());

        ComplexityAnalyzer.LOGGER.info("Building recipe graph...");
        this.graph = GraphBuilder.buildFromWorld(level);
    }

    private void startGeoScanIfNeeded() {
        var srv = this.server;
        if (srv == null || isInterrupted()) return;
        try {
            createGeoManager(srv);
            var geoMgr = getGeoManager();
            if (geoMgr != null && !isInterrupted()) geoMgr.startInitialScanIfNeeded();
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to create or start GeoAnalysisManager", e);
        }
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

    private void restoreIdleState(long generation) {
        setStateIfCurrent(generation, State.IDLE, true);
    }

    private boolean setStateIfCurrent(long generation, State state, boolean clearData) {
        stateLock.lock();
        try {
            long active = analysisGeneration.get();
            if (active != generation) {
                ComplexityAnalyzer.LOGGER.warn("Discarding superseded analysis task (gen {}, active {}); not transitioning to {}.", generation, active, state);
                return false;
            }
            if (clearData) clearDataInternal();
            currentState.set(state);
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    private void initializeCoreProviders(ServerLevel serverLevel) {
        this.mobPropProvider = new MobPropertyProvider();
        this.mobPropProvider.initialize();

        this.geoDatabase = new GeoDatabase(serverLevel.getServer());
        this.geoDatabase.loadAll();
    }

    private void initializeResourceSources(ServerLevel serverLevel) {
        var initialSources = new ObjectArrayList<IResourceSource>();

        initialSources.add(new UniversalLootSource());
        initialSources.add(new MobDropSource(this.mobPropProvider));
        initialSources.add(new BlockBreakAsRecipeSource(this.geoDatabase));
        initialSources.add(new FarmingSource());
        initialSources.add(new VillagerTradeSource());
        initialSources.add(new PassiveProductionSource());

        var hardcoded = new HardcodedSource();
        initialSources.add(hardcoded);

        var addonSources = new ObjectArrayList<IResourceSource>();
        try {
            NeoForge.EVENT_BUS.post(new ComplexityRegistrationEvent(this.mobPropProvider, hardcoded, this.mobPropProvider, addonSources::add));
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error("An addon threw during ComplexityRegistrationEvent; continuing without it.", t);
        }
        if (!addonSources.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("Registered {} custom resource source(s) from addons.", addonSources.size());
            initialSources.addAll(addonSources);
        }

        this.sourceManager = new SourceManager(initialSources);

        ComplexityAnalyzer.LOGGER.info("Resource sources configured with {} providers. Initializing all...", initialSources.size());
        this.sourceManager.initialize(serverLevel);

        if (ComplexityAnalyzer.LOGGER.isDebugEnabled()) for (var source : initialSources) {
            ComplexityAnalyzer.LOGGER.debug("  - {} (priority: {})", source.getName(), source.getPriority());
        }

        ComplexityAnalyzer.LOGGER.info("All resource sources initialized successfully.");
    }

    public void recalculateComplexity() {
        var currentGraph = this.graph;
        var currentSourceManager = this.sourceManager;

        if (isInterrupted() || currentGraph == null || currentSourceManager == null) return;

        ComplexityAnalyzer.LOGGER.info("Recalculating all complexity data...");

        this.complexityCache.clear();

        if (isInterrupted()) return;

        var solver = new SccCondensedSolver(currentGraph, currentSourceManager, this.machineRegistry);
        var solverResult = solver.solve();

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
        refreshGeoData("Geo-scan finished.");
    }

    private void refreshGeoData(String reason) {
        if (!isReady() || isShuttingDown.get()) {
            ComplexityAnalyzer.LOGGER.warn("Ignoring geo refresh ({}): engine not ready.", reason);
            return;
        }
        var srv = this.server;
        if (srv == null) return;
        ComplexityAnalyzer.LOGGER.info("{} Rebuilding geo-dependent data...", reason);
        submitBuild(srv.overworld(), false, null);
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
        this.geoDatabase = null;
        this.mobPropProvider = null;
    }

    public void createGeoManager(MinecraftServer server) {
        if (isShuttingDown.get()) return;

        geoManagerLock.lock();
        try {
            if (isShuttingDown.get()) return;
            var oldManager = this.geoManager;
            if (oldManager != null) oldManager.shutdown();
            var geoDB = this.geoDatabase;
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
        var geoDB = this.geoDatabase;
        if (geoDB == null) {
            ComplexityAnalyzer.LOGGER.warn("Cannot clear GeoDatabase: not initialized.");
            return;
        }
        if (!isReady() || isShuttingDown.get()) {
            ComplexityAnalyzer.LOGGER.warn("Cannot clear GeoDatabase: engine not ready. Current state: {}", currentState.get());
            return;
        }
        geoDB.clear();
        refreshGeoData("GeoDatabase cleared.");
    }

    public boolean isReady() {
        return currentState.get() == State.READY && !isShuttingDown.get();
    }

    public RecipeGraph getGraph() {
        return this.graph;
    }

    @Nullable
    public HolderLookup.Provider getRegistryAccess() {
        var srv = this.server;
        return srv != null ? srv.registryAccess() : null;
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
        var calc = this.calculator;
        if (calc == null || !isReady()) return null;
        return calc.getOrCalculateComplexity(item);
    }

    public double getComplexity(Item item) {
        var result = getComplexityResult(item);
        return (result != null) ? result.getComplexity() : -1.0;
    }

    public int getUsageCount(Item item) {
        var currentGraph = this.graph;
        return (currentGraph != null && isReady()) ? currentGraph.getUsageCount(item) : 0;
    }

    public boolean hasRecipe(Item item) {
        var currentGraph = this.graph;
        return currentGraph != null && isReady() && currentGraph.hasRecipe(item);
    }

    public ObjectList<BaseResourceData> findAllSourcesForItem(Item item) {
        var sm = this.sourceManager;
        return (sm != null && isReady()) ? sm.findAllSources(item) : ObjectLists.emptyList();
    }

    @Nullable
    public BaseResourceData getBaseResourceData(Item item) {
        var sm = this.sourceManager;
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

        var srv = serverLevel.getServer();

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
        analysisGeneration.incrementAndGet();
        var currentTask = currentAnalysisTask.getAndSet(null);
        if (currentTask != null && !currentTask.isDone()) currentTask.cancel(true);

        geoManagerLock.lock();
        try {
            var geoMgr = this.geoManager;
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
        initializeAsync(serverLevel, () -> ComplexityAnalyzer.LOGGER.info("✓ Reload complete. System operational."));
    }

    public void shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            ComplexityAnalyzer.LOGGER.debug("Shutdown already in progress");
            return;
        }

        ComplexityAnalyzer.LOGGER.debug("Shutdown requested for AnalysisEngine.");

        analysisCancelled.set(true);
        analysisGeneration.incrementAndGet();

        var currentTask = currentAnalysisTask.getAndSet(null);
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
            try {
                currentTask.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }

        geoManagerLock.lock();
        try {
            var geoMgr = this.geoManager;
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
            ComplexityAnalyzer.LOGGER.debug("AnalysisEngine state reset to IDLE.");
        } finally {
            stateLock.unlock();
        }

        isShuttingDown.set(false);
    }

    public void shutdownCompletely() {
        shutdown();
        ThreadPoolManager.getInstance().shutdown();
        ComplexityAnalyzer.LOGGER.info("AnalysisEngine and ThreadPoolManager fully shutdown.");
    }

    private void clearAllCaches() {
        ComplexityAnalyzer.LOGGER.info("Clearing all analysis caches...");

        complexityCache.clear();

        if (mobPropProvider != null) mobPropProvider.clearCache();
        ComplexityAnalyzer.LOGGER.info("All caches cleared.");
    }

    @Nullable
    public MobDropSource getMobDropSource() {
        var sm = this.sourceManager;
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

    @Nullable
    public GeoDatabase getGeoDatabase() {
        return this.geoDatabase;
    }

    public EngineStats getStats() {
        if (!isReady()) return new EngineStats(currentState.get(), 0, 0, 0);
        var currentGraph = this.graph;
        if (currentGraph == null) return new EngineStats(currentState.get(), 0, 0, 0);
        return new EngineStats(currentState.get(), currentGraph.getAllItems().size(), currentGraph.getTotalRecipeCount(), 0);
    }

    @Nullable
    public <T extends IResourceSource> T getSourceByType(Class<T> type) {
        var sm = this.sourceManager;
        if (sm == null) return null;
        return sm.getSourceByType(type);
    }

    public enum State {IDLE, ANALYZING, READY, FAILED}

    private static class InstanceHolder {
        private static final AnalysisEngine INSTANCE = new AnalysisEngine();
    }

    public record EngineStats(State state, int itemCount, int recipeCount, int baseResourceCount) {
    }
}