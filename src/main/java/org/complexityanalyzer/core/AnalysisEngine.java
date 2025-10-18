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
import org.complexityanalyzer.analyzer.resource.providers.BlockPropertyProvider;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.providers.TheoreticalDistributionProvider;
import org.complexityanalyzer.analyzer.resource.sources.*;
import org.complexityanalyzer.analyzer.solver.IterativeSolver;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.cache.ComplexityCache;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.data.PathType;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.graph.GraphBuilder;
import org.complexityanalyzer.graph.RecipeGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AnalysisEngine {
    public enum State { IDLE, ANALYZING, READY, FAILED }
    private final AtomicReference<State> currentState = new AtomicReference<>(State.IDLE);
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Complexity-Analysis-Thread");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean analysisCancelled = new AtomicBoolean(false);

    private final ComplexityCache complexityCache;
    private RecipeGraph graph;
    private SourceManager sourceManager;
    private ComplexityCalculator calculator;
    private DepthAnalyzer depthAnalyzer;
    private GeoDatabase geoDatabase;
    private BlockPropertyProvider blockPropProvider;
    private MobPropertyProvider mobPropProvider;
    private GeoAnalysisManager geoManager;

    private static class InstanceHolder {
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
            if (isReady()) {
                onComplete.run();
            }
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            currentState.set(State.FAILED); return;
        }

        ComplexityAnalyzer.LOGGER.info("Building recipe graph...");
        final RecipeGraph prebuiltGraph = GraphBuilder.buildFromWorld(level);
        ComplexityAnalyzer.LOGGER.info("Recipe graph built. Starting background analysis...");

        analysisExecutor.execute(() -> {
            try {
                analysisCancelled.set(false);
                ComplexityAnalyzer.LOGGER.info("=== [State: ANALYZING] Starting FAST initial analysis ===");
                this.graph = prebuiltGraph;

                initializeCoreProviders(serverLevel);
                if (analysisCancelled.get()) return;

                initializeResourceSources(serverLevel);
                if (analysisCancelled.get()) return;

                recalculateComplexity();

                ComplexityAnalyzer.LOGGER.info("=== [State: READY] Analysis complete. Mod is operational. ===");
                currentState.set(State.READY);

                onComplete.run();

            } catch (Exception e) {
                if (!analysisCancelled.get()) {
                    ComplexityAnalyzer.LOGGER.error("Critical error during analysis initialization", e);
                    currentState.set(State.FAILED);
                }
            }
        });
    }

    private void initializeCoreProviders(ServerLevel serverLevel) {
        this.blockPropProvider = new BlockPropertyProvider();
        this.blockPropProvider.initialize();

        this.mobPropProvider = new MobPropertyProvider();
        this.mobPropProvider.initialize();

        this.geoDatabase = new GeoDatabase(serverLevel.getServer());
        this.geoDatabase.loadAll();
    }

    private void initializeResourceSources(ServerLevel serverLevel) {
        List<IResourceSource> initialSources = new ArrayList<>();

        if (this.geoDatabase.isLoaded()) {
            initialSources.add(new EmpiricalBlockSource(this.blockPropProvider, this.geoDatabase));
            ComplexityAnalyzer.LOGGER.info("GeoDatabase loaded, skipping theoretical resources.");
        } else {
            TheoreticalDistributionProvider theoreticalDistProvider = new TheoreticalDistributionProvider();
            theoreticalDistProvider.initialize(serverLevel);
            initialSources.add(new TheoreticalBlockSource(this.blockPropProvider, theoreticalDistProvider));
        }

        initialSources.add(new UniversalLootSource());
        initialSources.add(new VillagerTradeSource());
        initialSources.add(new MobDropSource(this.mobPropProvider));

        this.sourceManager = new SourceManager(initialSources);

        ComplexityAnalyzer.LOGGER.info("Resource sources configured. Initializing all...");
        this.sourceManager.initialize(serverLevel);
        ComplexityAnalyzer.LOGGER.info("All resource sources initialized.");
    }


    public void recalculateComplexity() {
        if (analysisCancelled.get() || graph == null || sourceManager == null) return;

        ComplexityAnalyzer.LOGGER.info("Recalculating all complexity data...");

        this.complexityCache.clear();

        SourcePathAnalyzer pathAnalyzer = new SourcePathAnalyzer(this.graph, this.sourceManager);
        pathAnalyzer.findItemsWithBasePath();
        IterativeSolver solver = new IterativeSolver(this.graph, this.sourceManager);
        SolverResult solverResult = solver.solve();

        if (analysisCancelled.get()) {
            ComplexityAnalyzer.LOGGER.info("Analysis was cancelled after solver finished.");
            return;
        }

        this.depthAnalyzer = new DepthAnalyzer(this.graph, this.sourceManager);
        this.calculator = new ComplexityCalculator(this.graph, this.depthAnalyzer, solverResult, this.sourceManager);

        ComplexityAnalyzer.LOGGER.info("All systems refreshed with new data.");
    }

    public void onGeoScanFinished() {
        if (this.sourceManager == null || this.graph == null || analysisCancelled.get()) {
            ComplexityAnalyzer.LOGGER.warn("onGeoScanFinished called while AnalysisEngine was resetting. Ignoring refresh.");
            return;
        }

        ComplexityAnalyzer.LOGGER.info("Geo-scan finished. Updating resource sources...");

        // Шаг 1: Удаляем старые теоретические и эмпирические источники.
        // Это гарантирует, что мы не оставим "хвостов" и не создадим дубликатов.
        sourceManager.removeSourcesByType(TheoreticalBlockSource.class);
        sourceManager.removeSourcesByType(EmpiricalBlockSource.class);

        // Шаг 2: Добавляем новый актуальный эмпирический источник.
        // Метод addSourceAndRefresh сам позаботится о добавлении и обновлении кэша.
        EmpiricalBlockSource empiricalSource = new EmpiricalBlockSource(this.blockPropProvider, this.geoDatabase);
        sourceManager.addSourceAndRefresh(empiricalSource);

        // Шаг 3: Запускаем полный перерасчет сложности с новыми, чистыми данными.
        recalculateComplexity();
    }

    public ComplexityCache getComplexityCache() {
        return this.complexityCache;
    }

    private void clearData() {
        if (currentState.get() == State.IDLE) return;
        analysisCancelled.set(true);

        this.graph = null;
        this.sourceManager = null;
        this.calculator = null;
        this.depthAnalyzer = null;

        this.complexityCache.clear();

        currentState.set(State.IDLE);
    }

    public void createGeoManager(MinecraftServer server) {
        if (this.geoManager == null && this.geoDatabase != null) {
            this.geoManager = new GeoAnalysisManager(server, this.geoDatabase, this);
        }
    }

    public Executor getBackgroundExecutor() {
        return analysisExecutor;
    }

    public void clearGeoDatabase() {
        if (geoDatabase != null) {
            geoDatabase.clear();
            onGeoScanFinished();
            ComplexityAnalyzer.LOGGER.info("GeoDatabase cleared by command and engine state refreshed.");
        }
    }

    public boolean isReady() { return currentState.get() == State.READY; }
    public RecipeGraph getGraph() { return this.graph; }
    public State getCurrentState() { return currentState.get(); }
    public Optional<ItemComplexity> getComplexityResult(Item item, PathType pathType) {if (!isReady()) return Optional.empty();return calculator.getOrCalculateComplexity(item, pathType);}
    public Optional<DepthAnalyzer> getDepthAnalyzer() { return Optional.ofNullable(this.depthAnalyzer); }
    public double getComplexity(Item item, PathType pathType) { return getComplexityResult(item, pathType).map(ItemComplexity::getComplexity).orElse(-1.0); }
    public int getUsageCount(Item item) { return isReady() ? graph.getUsageCount(item) : 0; }
    public boolean hasRecipe(Item item) { return isReady() && graph.hasRecipe(item); }
    public List<BaseResourceData> findAllSourcesForItem(Item item) { return isReady() ? sourceManager.findAllSources(item) : new ArrayList<>(); }
    public Optional<BaseResourceData> getBaseResourceData(Item item) { return isReady() ? sourceManager.analyze(item) : Optional.empty(); }

    public void reloadAsync(Level level) {
        if (currentState.get() == State.ANALYZING) return;
        ComplexityAnalyzer.LOGGER.info("Queueing engine reload task...");
        analysisExecutor.execute(() -> {
            clearData();
            initializeAsync(level, () -> {});
        });
    }

    public void shutdown() {
        this.geoDatabase = null;
        this.geoManager = null;
        clearData();
        analysisExecutor.shutdownNow();
    }

    public Optional<MobDropSource> getMobDropSource() {
        if (sourceManager == null) {
            return Optional.empty();
        }
        return sourceManager.getSourceByType(MobDropSource.class);
    }

    public Optional<MobPropertyProvider> getMobPropertyProvider() {
        return Optional.ofNullable(this.mobPropProvider);
    }

    public Optional<GeoAnalysisManager> getGeoManager() {
        return Optional.ofNullable(this.geoManager);
    }

    public record EngineStats(State state, int itemCount, int recipeCount, int baseResourceCount) {}

    public EngineStats getStats() {
        if (!isReady()) return new EngineStats(currentState.get(), 0, 0, 0);
        return new EngineStats(currentState.get(), graph.getAllItems().size(), graph.getTotalRecipeCount(), 0);
    }

    public <T extends IResourceSource> Optional<T> getSourceByType(Class<T> type) {
        if (sourceManager == null) {
            return Optional.empty();
        }
        return sourceManager.getSourceByType(type);
    }
}