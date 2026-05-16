package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.bytecode.cache.*;
import org.complexityanalyzer.bytecode.model.*;
import org.complexityanalyzer.graph.RecipeGraph;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class BytecodeAnalysisEngine {

    private final BytecodeCache cache;
    private volatile AnalysisResult lastResult;

    public BytecodeAnalysisEngine() {
        this.cache = BytecodeCacheManager.loadCache();
    }

    public static final class AnalysisResult {
        public final ObjectList<EventNode> events;
        public final ObjectList<MachineNode> machines;
        public final ObjectList<SemanticEdge> edges;
        public final int classesScanned;
        public final int classesSkipped;
        public final long durationMs;

        AnalysisResult(ObjectList<EventNode> events, ObjectList<MachineNode> machines,
                       ObjectList<SemanticEdge> edges, int scanned, int skipped, long ms) {
            this.events = events;
            this.machines = machines;
            this.edges = edges;
            this.classesScanned = scanned;
            this.classesSkipped = skipped;
            this.durationMs = ms;
        }

        public boolean hasResults() {
            return !edges.isEmpty() || !events.isEmpty() || !machines.isEmpty();
        }
    }

    public AnalysisResult getLastResult() {
        return lastResult;
    }

    public AnalysisResult analyzeAndMerge(RecipeGraph graph) {
        long start = System.currentTimeMillis();

        ComplexityAnalyzer.LOGGER.info("=== [Bytecode Analysis] Starting semantic extraction ===");

        var modList = ModList.get();
        if (modList == null) {
            ComplexityAnalyzer.LOGGER.warn("[Bytecode] ModList is null, skipping");
            return emptyResult(start);
        }

        Map<String, byte[]> allClasses = ClassCollector.collectAll(modList);
        Map<String, byte[]> toAnalyze = BytecodeCacheManager.filterChanged(allClasses, cache);
        int skipped = allClasses.size() - toAnalyze.size();

        if (toAnalyze.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("[Bytecode] No changes detected, skipping analysis");
            this.lastResult = emptyResult(start);
            return this.lastResult;
        }

        var allEvents = new ObjectArrayList<EventNode>();
        var allMachines = new ObjectArrayList<MachineNode>();
        var scanned = new AtomicInteger(0);
        var failed = new AtomicInteger(0);

        toAnalyze.entrySet().parallelStream().forEach(entry -> {
            try {
                var clazz = BytecodeAnalyzer.analyze(entry.getKey(), entry.getValue());
                if (clazz == null) {
                    failed.incrementAndGet();
                    return;
                }

                String modId = inferModId(clazz.className());

                var events = EventDetector.detectEvents(clazz, modId);
                if (!events.isEmpty()) for (var event : events) {
                    var m = BytecodeAnalyzer.findMethod(clazz, event.methodName());
                    if (m != null) {
                        synchronized (allEvents) {
                            allEvents.add(enrichEvent(event, m));
                        }
                    } else {
                        synchronized (allEvents) {
                            allEvents.add(event);
                        }
                    }
                }

                var candidate = PatternEngine.classifyMachine(clazz, modId);
                if (candidate.isMachine()) synchronized (allMachines) {
                    allMachines.add(PatternEngine.extractMachineLogic(candidate));
                }
                scanned.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
            }
        });

        var edges = SemanticGraphBuilder.build(allEvents, allMachines);

        int merged = 0;
        if (graph != null && !edges.isEmpty()) merged = GraphMerger.mergeInto(graph, edges);

        BytecodeCacheManager.saveCache(cache);

        long duration = System.currentTimeMillis() - start;
        ComplexityAnalyzer.LOGGER.info("[Bytecode] {} events, {} machines, {} edges ({} merged), {} scanned, {} skipped, {} failed, {}ms",
                allEvents.size(), allMachines.size(), edges.size(), merged, scanned.get(), skipped, failed.get(), duration);

        this.lastResult = new AnalysisResult(allEvents, allMachines, edges, scanned.get(), skipped, duration);
        return this.lastResult;
    }

    private static EventNode enrichEvent(EventNode event, BytecodeAnalyzer.AnalyzedMethod method) {
        var conds = ConditionExtractor.extract(method);
        var acts = EffectExtractor.extract(method);
        if (conds.isEmpty() && acts.isEmpty()) return event;

        var b = new EventNode.Builder().eventType(event.eventType()).methodName(event.methodName())
                .className(event.className()).modId(event.modId());
        for (var c : conds) b.addCondition(c);
        for (var a : acts) b.addAction(a);
        return b.build();
    }

    private static String inferModId(String className) {
        String[] parts = className.split("/");
        return parts.length >= 1 ? parts[0] : "unknown";
    }

    private static AnalysisResult emptyResult(long start) {
        return new AnalysisResult(ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), 0, 0, System.currentTimeMillis() - start);
    }
}
