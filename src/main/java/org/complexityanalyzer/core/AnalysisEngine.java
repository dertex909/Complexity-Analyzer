package org.complexityanalyzer.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.ComplexityCalculator;
import org.complexityanalyzer.analyzer.DepthAnalyzer;
import org.complexityanalyzer.analyzer.SourcePathAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.providers.*;
import org.complexityanalyzer.analyzer.resource.sources.*;
import org.complexityanalyzer.analyzer.solver.IterativeSolver;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.cache.ComplexityCache;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.graph.GraphBuilder;
import org.complexityanalyzer.graph.RecipeGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class AnalysisEngine {
    public enum State { IDLE, ANALYZING, READY, FAILED }

    private final AtomicReference<State> currentState = new AtomicReference<>(State.IDLE);
    private final AtomicReference<ExecutorService> analysisExecutor = new AtomicReference<>(null);
    private final AtomicReference<Future<?>> currentAnalysisTask = new AtomicReference<>(null);
    private final AtomicBoolean analysisCancelled = new AtomicBoolean(false);
    private final AtomicBoolean isReloading = new AtomicBoolean(false);
    private final ReentrantLock stateLock = new ReentrantLock();
    private final ReentrantLock geoManagerLock = new ReentrantLock();
    private final Object executorLock = new Object();

    private final ComplexityCache complexityCache;

    private volatile RecipeGraph graph;
    private volatile SourceManager sourceManager;
    private volatile ComplexityCalculator calculator;
    private volatile DepthAnalyzer depthAnalyzer;
    private volatile GeoDatabase geoDatabase;
    private volatile BlockPropertyProvider blockPropProvider;
    private volatile MobPropertyProvider mobPropProvider;
    private volatile GeoAnalysisManager geoManager;
    private volatile TheoreticalDistributionProvider theoreticalDistProvider;
    private volatile MobRarityCalculator mobRarityCalculator;private static class InstanceHolder {
        private static final AnalysisEngine INSTANCE = new AnalysisEngine();
    }

    public static AnalysisEngine getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private AnalysisEngine() {
        this.complexityCache = new ComplexityCache();
    }

    public void initializeAsync(Level level, Runnable onComplete) {
        if (!currentState.compareAndSet(State.IDLE, State.ANALYZING)) {
            State current = currentState.get();
            if (current == State.READY) {
                safeRunCallback(onComplete);
            }
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            currentState.set(State.FAILED);
            return;
        }

        ExecutorService executor = ensureExecutorAvailable();

        ComplexityAnalyzer.LOGGER.info("Starting background analysis...");

        Future<?> task = executor.submit(() -> {
            try {
                analysisCancelled.set(false);

                if (isInterrupted()) {
                    restoreIdleState();
                    return;
                }

                ComplexityAnalyzer.LOGGER.info("Building recipe graph...");
                this.graph = GraphBuilder.buildFromWorld(level);

                ComplexityAnalyzer.LOGGER.info("=== [State: ANALYZING] Starting FAST initial analysis ===");

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

                recalculateComplexity();

                if (isInterrupted()) {
                    restoreIdleState();
                    return;
                }

                ComplexityAnalyzer.LOGGER.info("=== [State: READY] Analysis complete. Mod is operational. ===");
                currentState.set(State.READY);

                try {
                    createGeoManager(serverLevel.getServer());
                    Optional<GeoAnalysisManager> geoMgr = getGeoManager();
                    geoMgr.ifPresent(GeoAnalysisManager::startInitialScanIfNeeded);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.error("Failed to create or start GeoAnalysisManager", e);
                }

                safeRunCallback(onComplete);

            } catch (Exception e) {
                if (!isInterrupted()) {
                    ComplexityAnalyzer.LOGGER.error("Critical error during analysis initialization", e);
                    currentState.set(State.FAILED);
                }
            }
        });

        currentAnalysisTask.set(task);
    }

    private boolean isInterrupted() {
        return analysisCancelled.get() || Thread.currentThread().isInterrupted();
    }

    private static ExecutorService createAnalysisExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Complexity-Analysis-Thread");
            t.setDaemon(true);
            return t;
        });
    }

    private ExecutorService ensureExecutorAvailable() {
        ExecutorService current = analysisExecutor.get();

        if (current != null && !current.isShutdown()) {
            return current;
        }

        synchronized (executorLock) {
            current = analysisExecutor.get();
            if (current != null && !current.isShutdown()) {
                return current;
            }

            ComplexityAnalyzer.LOGGER.info("Creating new analysis thread pool.");
            ExecutorService newExecutor = createAnalysisExecutor();
            analysisExecutor.set(newExecutor);
            return newExecutor;
        }
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
        clearDataInternal();
        currentState.set(State.IDLE);
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

        this.theoreticalDistProvider = new TheoreticalDistributionProvider();
        this.theoreticalDistProvider.initialize(serverLevel);
    }

    private void initializeResourceSources(ServerLevel serverLevel) {
        List<IResourceSource> initialSources = new ArrayList<>();

        GeoDatabase geoDB = this.geoDatabase;
        BlockPropertyProvider blockProp = this.blockPropProvider;

        if (geoDB != null && geoDB.isLoaded()) {
            initialSources.add(new EmpiricalBlockSource(blockProp, geoDB));
            ComplexityAnalyzer.LOGGER.info("GeoDatabase loaded, skipping theoretical resources.");
        } else {
            initialSources.add(new TheoreticalBlockSource(blockProp, this.theoreticalDistProvider));
        }

        initialSources.add(new UniversalLootSource());
        initialSources.add(new MobDropSource(this.mobPropProvider, serverLevel));
        initialSources.add(new BlockBreakAsRecipeSource());
        initialSources.add(new VillagerTradeSource());
        initialSources.add(new PassiveProductionSource());
        initialSources.add(new HardcodedSourcesProvider());

        this.sourceManager = new SourceManager(initialSources);

        ComplexityAnalyzer.LOGGER.info("Resource sources configured. Initializing all...");
        this.sourceManager.initialize(serverLevel);
        ComplexityAnalyzer.LOGGER.info("All resource sources initialized.");
    }

    public void recalculateComplexity() {
        RecipeGraph currentGraph = this.graph;
        SourceManager currentSourceManager = this.sourceManager;

        if (isInterrupted() || currentGraph == null || currentSourceManager == null) {
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Recalculating all complexity data...");

        this.complexityCache.clear();

        SourcePathAnalyzer pathAnalyzer = new SourcePathAnalyzer(currentGraph, currentSourceManager);
        pathAnalyzer.findItemsWithBasePath();

        if (isInterrupted()) {
            return;
        }

        IterativeSolver solver = new IterativeSolver(currentGraph, currentSourceManager);
        SolverResult solverResult = solver.solve();

        if (isInterrupted()) {
            ComplexityAnalyzer.LOGGER.info("Analysis was cancelled after solver finished.");
            return;
        }

        this.depthAnalyzer = new DepthAnalyzer(currentGraph, currentSourceManager);

        this.depthAnalyzer.setOptimalRecipes(solverResult.optimalRecipes());

        this.calculator = new ComplexityCalculator(currentGraph, this.depthAnalyzer, solverResult, currentSourceManager);

        ComplexityAnalyzer.LOGGER.info("All systems refreshed with new data.");
    }

    public void onGeoScanFinished() {
        stateLock.lock();
        try {
            SourceManager currentSourceManager = this.sourceManager;
            RecipeGraph currentGraph = this.graph;
            GeoDatabase geoDB = this.geoDatabase;
            BlockPropertyProvider blockProp = this.blockPropProvider;

            if (currentSourceManager == null || currentGraph == null || isInterrupted()) {
                ComplexityAnalyzer.LOGGER.warn("onGeoScanFinished called while AnalysisEngine was resetting. Ignoring refresh.");
                return;
            }

            ComplexityAnalyzer.LOGGER.info("Geo-scan finished. Updating resource sources...");

            currentSourceManager.removeSourcesByType(TheoreticalBlockSource.class);
            currentSourceManager.removeSourcesByType(EmpiricalBlockSource.class);

            if (geoDB != null && blockProp != null) {
                EmpiricalBlockSource empiricalSource = new EmpiricalBlockSource(blockProp, geoDB);
                currentSourceManager.addSourceAndRefresh(empiricalSource);
            }

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
        this.complexityCache.clear();
    }

    public void createGeoManager(MinecraftServer server) {
        geoManagerLock.lock();
        try {
            if (this.geoManager == null && this.geoDatabase != null) {
                this.geoManager = new GeoAnalysisManager(server, this.geoDatabase, this);
            }
        } finally {
            geoManagerLock.unlock();
        }
    }

    public Executor getBackgroundExecutor() {
        return analysisExecutor.get();
    }

    public void clearGeoDatabase() {
        if (!isReady()) {
            ComplexityAnalyzer.LOGGER.warn("Cannot clear GeoDatabase: engine not ready. Current state: {}", currentState.get());
            return;
        }

        stateLock.lock();
        try {
            GeoDatabase geoDB = this.geoDatabase;
            if (geoDB != null) {
                geoDB.clear();
                ComplexityAnalyzer.LOGGER.info("GeoDatabase cleared. Reverting to theoretical sources...");
            }

            SourceManager currentSourceManager = this.sourceManager;
            BlockPropertyProvider blockProp = this.blockPropProvider;
            TheoreticalDistributionProvider theoreticalDist = this.theoreticalDistProvider;

            if (currentSourceManager != null && blockProp != null && theoreticalDist != null) {
                currentSourceManager.removeSourcesByType(EmpiricalBlockSource.class);

                TheoreticalBlockSource theoreticalSource = new TheoreticalBlockSource(blockProp, theoreticalDist);
                currentSourceManager.addSourceAndRefresh(theoreticalSource);

                ComplexityAnalyzer.LOGGER.info("Theoretical block sources restored.");
                recalculateComplexity();
            } else {
                ComplexityAnalyzer.LOGGER.warn("Cannot revert to theoretical sources - providers not initialized.");
            }
        } finally {
            stateLock.unlock();
        }
    }

    public boolean isReady() {
        return currentState.get() == State.READY;
    }

    public RecipeGraph getGraph() {
        return this.graph;
    }

    @SuppressWarnings("unused")
    public Optional<SourceManager> getSourceManager() {
        return Optional.ofNullable(this.sourceManager);
    }

    public State getCurrentState() {
        return currentState.get();
    }

    public Optional<ItemComplexity> getComplexityResult(Item item) {
        ComplexityCalculator calc = this.calculator;
        if (calc == null || !isReady()) {
            return Optional.empty();
        }
        return calc.getOrCalculateComplexity(item);
    }

    public Optional<DepthAnalyzer> getDepthAnalyzer() {
        return Optional.ofNullable(this.depthAnalyzer);
    }

    public double getComplexity(Item item) {
        return getComplexityResult(item)
                .map(ItemComplexity::getComplexity)
                .orElse(-1.0);
    }

    public int getUsageCount(Item item) {
        RecipeGraph currentGraph = this.graph;
        return (currentGraph != null && isReady()) ? currentGraph.getUsageCount(item) : 0;
    }

    public boolean hasRecipe(Item item) {
        RecipeGraph currentGraph = this.graph;
        return currentGraph != null && isReady() && currentGraph.hasRecipe(item);
    }

    public List<BaseResourceData> findAllSourcesForItem(Item item) {
        SourceManager sm = this.sourceManager;
        return (sm != null && isReady()) ? sm.findAllSources(item) : new ArrayList<>();
    }

    public Optional<BaseResourceData> getBaseResourceData(Item item) {
        SourceManager sm = this.sourceManager;
        return (sm != null && isReady()) ? sm.analyze(item) : Optional.empty();
    }

    public void reloadAsync(Level level) {
        if (isReloading.getAndSet(true)) {
            ComplexityAnalyzer.LOGGER.warn("Reload is already in progress. Ignoring duplicate request.");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Reload requested. Scheduling full restart...");

        Thread reloadThread = new Thread(() -> {
            try {
                shutdown();
                clearAllCaches();
                Thread.sleep(500);
                initializeAsync(level, () -> ComplexityAnalyzer.LOGGER.info("Reload complete."));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ComplexityAnalyzer.LOGGER.error("Reload interrupted", e);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Error during reload", e);
            } finally {
                isReloading.set(false);
            }
        }, "Complexity-Reload-Thread");

        reloadThread.setDaemon(true);
        reloadThread.start();
    }

    public void shutdown() {
        ComplexityAnalyzer.LOGGER.info("Shutdown requested for AnalysisEngine.");

        Future<?> currentTask = currentAnalysisTask.getAndSet(null);
        if (currentTask != null && !currentTask.isDone()) {
            analysisCancelled.set(true);
            currentTask.cancel(true);
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

        ExecutorService executor = analysisExecutor.getAndSet(null);

        if (executor != null && !executor.isShutdown()) {
            ComplexityAnalyzer.LOGGER.info("Shutting down analysis thread pool gracefully... Attempting to stabilize core.");
            executor.shutdown();

            try {
                final String[] countdownMessages = {
                        "[T-5s] Warning: Analysis core is not responding to shutdown signal. Stabilizing magnetic field...",
                        "[T-4s] Warning: Coolant pressure dropping. Trying to bypass primary calculation loop...",
                        "[T-3s] CRITICAL: Resonance cascade imminent in solver matrix! Attempting to eject complexity queue...",
                        "[T-2s] DANGER: Control rod ejection FAILED! Core temperature rising exponentially!",
                        "[T-1s] !!! CORE INTEGRITY FAILURE IMMINENT. BRACE FOR IMPACT. !!!"
                };

                boolean terminatedGracefully = false;
                for (int i = 0; i < 5; i++) {
                    if (executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        terminatedGracefully = true;
                        ComplexityAnalyzer.LOGGER.info("Graceful shutdown successful. Analysis core is stable.");
                        break;
                    } else {
                        ComplexityAnalyzer.LOGGER.warn(countdownMessages[i]);
                    }
                }

                if (!terminatedGracefully) {
                    ComplexityAnalyzer.LOGGER.error("!!! CONTAINMENT BREACH: ANALYSIS CORE MELTDOWN !!!");
                    ComplexityAnalyzer.LOGGER.error("Forcing emergency shutdown protocol! Data integrity compromised!");

                    List<Runnable> discardedTasks = executor.shutdownNow();

                    ComplexityAnalyzer.LOGGER.error("SCRAM PROTOCOL ENGAGED. Purged {} tasks from execution queue.", discardedTasks.size());
                    ComplexityAnalyzer.LOGGER.error("System state is UNRECOVERABLE. Full analysis required on next boot.");
                }

            } catch (InterruptedException e) {
                ComplexityAnalyzer.LOGGER.error("!!! INTERRUPTED DURING EMERGENCY SHUTDOWN. STATE UNKNOWN. !!!", e);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            ComplexityAnalyzer.LOGGER.info("Analysis thread pool is now offline.");
        }

        stateLock.lock();
        try {
            clearDataInternal();
            currentState.set(State.IDLE);
            ComplexityAnalyzer.LOGGER.info("AnalysisEngine state reset to IDLE.");
        } finally {
            stateLock.unlock();
        }
    }

    private void clearAllCaches() {
        ComplexityAnalyzer.LOGGER.info("Clearing all analysis caches...");

        complexityCache.clear();

        if (mobRarityCalculator != null) {
            mobRarityCalculator.clearCache();
        }

        ComplexityAnalyzer.LOGGER.info("All caches cleared.");
    }

    public Optional<MobDropSource> getMobDropSource() {
        SourceManager sm = this.sourceManager;
        if (sm == null) {
            return Optional.empty();
        }
        return sm.getSourceByType(MobDropSource.class);
    }

    public Optional<MobPropertyProvider> getMobPropertyProvider() {
        return Optional.ofNullable(this.mobPropProvider);
    }

    public Optional<GeoAnalysisManager> getGeoManager() {
        return Optional.ofNullable(this.geoManager);
    }

    public record EngineStats(State state, int itemCount, int recipeCount, int baseResourceCount) {}

    public EngineStats getStats() {
        if (!isReady()) {
            return new EngineStats(currentState.get(), 0, 0, 0);
        }

        RecipeGraph currentGraph = this.graph;
        if (currentGraph == null) {
            return new EngineStats(currentState.get(), 0, 0, 0);
        }

        return new EngineStats(
                currentState.get(),
                currentGraph.getAllItems().size(),
                currentGraph.getTotalRecipeCount(),
                0
        );
    }

    public <T extends IResourceSource> Optional<T> getSourceByType(Class<T> type) {
        SourceManager sm = this.sourceManager;
        if (sm == null) {
            return Optional.empty();
        }
        return sm.getSourceByType(type);
    }
}