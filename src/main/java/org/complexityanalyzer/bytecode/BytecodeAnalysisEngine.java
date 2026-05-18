package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.bytecode.cache.*;
import org.complexityanalyzer.bytecode.graph.*;
import org.complexityanalyzer.bytecode.model.*;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.harvest.HarvestDebugWriter;
import org.complexityanalyzer.harvest.StructuralBytecodeHarvester;
import org.complexityanalyzer.harvest.StructuralSemanticExporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public final class BytecodeAnalysisEngine {

    private final BytecodeCache cache;
    private final Path worldDir;
    private volatile AnalysisResult lastResult;
    private final ObjectList<String> bytecodeDumps = new ObjectArrayList<>();

    public BytecodeAnalysisEngine(Path worldDir) {
        this.worldDir = worldDir;
        this.cache = BytecodeCacheManager.loadCache(worldDir);
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

    public Path getWorldDir() {
        return worldDir;
    }

    public AnalysisResult analyzeAndMerge(RecipeGraph graph) {
        long start = System.currentTimeMillis();

        ComplexityAnalyzer.LOGGER.info("=== [Bytecode Analysis] 3-pass call graph pipeline starting ===");

        bytecodeDumps.clear();

        var modList = ModList.get();
        if (modList == null) {
            ComplexityAnalyzer.LOGGER.warn("[Bytecode] ModList is null, skipping");
            return emptyResult(start);
        }

        Map<String, byte[]> allClassesRaw = ClassCollector.collectAll(modList);
        Map<String, byte[]> toAnalyze = BytecodeCacheManager.filterChanged(allClassesRaw, cache);
        int skipped = allClassesRaw.size() - toAnalyze.size();

        if (toAnalyze.isEmpty()) {
            ComplexityAnalyzer.LOGGER.info("[Bytecode] No changes detected, skipping analysis");
            this.lastResult = emptyResult(start);
            return this.lastResult;
        }

        // === PASS 1: analyze bytecode + build call graphs ===
        var analyzedClasses = new Object2ObjectOpenHashMap<String, BytecodeAnalyzer.AnalyzedClass>();
        var scanned = new AtomicInteger(0);
        var failed = new AtomicInteger(0);

        toAnalyze.entrySet().parallelStream().forEach(entry -> {
            try {
                var clazz = BytecodeAnalyzer.analyze(entry.getKey(), entry.getValue());
                if (clazz == null) {
                    failed.incrementAndGet();
                    return;
                }
                synchronized (analyzedClasses) {
                    analyzedClasses.put(clazz.className(), clazz);
                }
                scanned.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
            }
        });

        if (analyzedClasses.isEmpty()) {
            ComplexityAnalyzer.LOGGER.warn("[Bytecode] No classes successfully analyzed");
            return emptyResult(start);
        }

        ComplexityAnalyzer.LOGGER.info("[Bytecode] Pass 1 complete: {} classes analyzed, {} failed", analyzedClasses.size(), failed.get());

        var structuralShapes = StructuralBytecodeHarvester.analyze(analyzedClasses.values());
        int recipeShapes = 0;
        int machineShapes = 0;
        int codecShapes = 0;
        for (var shape : structuralShapes) {
            if (shape.recipeLike()) recipeShapes++;
            if (shape.machineLike()) machineShapes++;
            if (shape.codecLike()) codecShapes++;
        }
        ComplexityAnalyzer.LOGGER.info("[Harvest:Static] {} structural classes ({} recipe-like, {} machine-like, {} codec-like)",
                structuralShapes.size(), recipeShapes, machineShapes, codecShapes);
        HarvestDebugWriter.writeStructuralCandidates(worldDir, structuralShapes);

        // === PASS 2: build call graphs + run semantic analyzer ===
        var callGraphs = CallGraphBuilder.build(analyzedClasses);
        var anchorRegistry = SemanticAnchorRegistry.defaultRegistry();

        var allProfiles = new Object2ObjectOpenHashMap<MethodRef, SemanticProfile>();
        for (var cgEntry : callGraphs.values()) {
            var profiles = SemanticAnalyzer.analyze(cgEntry, anchorRegistry);
            allProfiles.putAll(profiles);
        }

        ComplexityAnalyzer.LOGGER.info("[Bytecode] Pass 2 complete: {} call graphs, {} semantic profiles",
                callGraphs.size(), allProfiles.size());

        // === PASS 3: detect events, machines, recipes ===
        var allEvents = new ObjectArrayList<EventNode>();
        var allMachines = new ObjectArrayList<MachineNode>();
        var allRecipeEdges = new ObjectArrayList<SemanticEdge>();
        allMachines.addAll(StructuralSemanticExporter.machinesFromShapes(structuralShapes));
        allRecipeEdges.addAll(StructuralSemanticExporter.edgesFromShapes(structuralShapes));

        for (var clazz : analyzedClasses.values()) {
            String modId = inferModId(clazz.className());

            var events = EventDetector.detectEvents(clazz, modId, allProfiles);
            if (!events.isEmpty()) for (var event : events) {
                var m = BytecodeAnalyzer.findMethod(clazz, event.methodName());
                if (m != null) {
                    collectDump(clazz.className(), m, "EVENT: " + event.eventType());
                    synchronized (allEvents) {
                        allEvents.add(enrichEvent(event, m, anchorRegistry));
                    }
                } else {
                    synchronized (allEvents) {
                        allEvents.add(event);
                    }
                }
            }

            var candidate = PatternEngine.classifyMachine(clazz, modId, allProfiles);
            if (candidate.isMachine()) {
                var tickM = BytecodeAnalyzer.findMethod(clazz, "tick");
                if (tickM == null) for (var tn : PatternEngine.TICK_NAMES) {
                    tickM = BytecodeAnalyzer.findMethod(clazz, tn);
                    if (tickM != null) break;
                }
                if (tickM != null) collectDump(clazz.className(), tickM, "MACHINE");
                collectClassDump(clazz.className(), clazz);
                synchronized (allMachines) {
                    allMachines.add(PatternEngine.extractMachineLogic(candidate, anchorRegistry));
                }
            }

            var recipeEdges = RecipeDetector.detect(clazz, modId, anchorRegistry);
            if (!recipeEdges.isEmpty()) {
                synchronized (allRecipeEdges) {
                    allRecipeEdges.addAll(recipeEdges);
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("[Bytecode] Pass 3 complete: {} events, {} machines, {} recipe edges",
                allEvents.size(), allMachines.size(), allRecipeEdges.size());

        var edges = SemanticGraphBuilder.build(allEvents, allMachines);
        edges.addAll(allRecipeEdges);

        int merged = 0;
        if (graph != null && !edges.isEmpty()) merged = GraphMerger.mergeInto(graph, edges);

        BytecodeCacheManager.saveCache(cache, worldDir);

        saveBytecodeDumps();

        long duration = System.currentTimeMillis() - start;
        ComplexityAnalyzer.LOGGER.info("[Bytecode] {} events, {} machines, {} edges ({} merged), {} scanned, {} skipped, {} failed, {}ms",
                allEvents.size(), allMachines.size(), edges.size(), merged, scanned.get(), skipped, failed.get(), duration);

        this.lastResult = new AnalysisResult(allEvents, allMachines, edges, scanned.get(), skipped, duration);
        return this.lastResult;
    }

    private static EventNode enrichEvent(EventNode event, BytecodeAnalyzer.AnalyzedMethod method, SemanticAnchorRegistry registry) {
        var conds = ConditionExtractor.extract(method, registry);
        var acts = EffectExtractor.extract(method, registry);
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

    private void collectClassDump(String className, BytecodeAnalyzer.AnalyzedClass clazz) {
        String dump = BytecodeAnalyzer.dumpClassFields(clazz);
        synchronized (bytecodeDumps) {
            bytecodeDumps.add("CLASS_FIELDS: " + className);
            bytecodeDumps.add(dump);
        }
    }

    private void collectDump(String className, BytecodeAnalyzer.AnalyzedMethod method, String context) {
        String dump = BytecodeAnalyzer.dumpMethod(className, method);
        synchronized (bytecodeDumps) {
            bytecodeDumps.add("CONTEXT: " + context + " | " + className + "." + method.name() + method.descriptor());
            bytecodeDumps.add(dump);
        }
    }

    private void saveBytecodeDumps() {
        try {
            Path dumpDir = worldDir.resolve("complexityanalyzer");
            Files.createDirectories(dumpDir);
            Path dumpFile = dumpDir.resolve("bytecode_dump.txt");

            var sb = new StringBuilder();
            sb.append("=== BYTECODE DUMP (").append(bytecodeDumps.size() / 2).append(" methods) ===\n\n");
            for (var line : bytecodeDumps) sb.append(line).append("\n");

            Files.writeString(dumpFile, sb.toString(), StandardCharsets.UTF_8);
            ComplexityAnalyzer.LOGGER.info("[Bytecode] Saved {} method dumps to {}", bytecodeDumps.size() / 2, dumpFile);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Bytecode] Failed to save dumps: {}", e.getMessage());
        }
    }

    private static AnalysisResult emptyResult(long start) {
        return new AnalysisResult(ObjectLists.emptyList(), ObjectLists.emptyList(),
                ObjectLists.emptyList(), 0, 0, System.currentTimeMillis() - start);
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    private record ExportedSummary(
        int events,
        int machines,
        int edges,
        int classesScanned,
        int classesSkipped,
        long durationMs
    ) {}

    private record ExportedEvent(
        String eventType,
        String className,
        String methodName,
        String modId,
        int conditions,
        int actions
    ) {}

    private record ExportedMachine(
        String className,
        String modId,
        java.util.List<String> inputs,
        java.util.List<String> outputs,
        boolean deterministic
    ) {}

    private record ExportedEdge(
        String from,
        String to,
        String action,
        double weight,
        String source,
        int conditions
    ) {}

    private record ExportedData(
        ExportedSummary summary,
        java.util.List<ExportedEvent> events,
        java.util.List<ExportedMachine> machines,
        java.util.List<ExportedEdge> edges
    ) {}

    public String exportResultsToJson() {
        if (lastResult == null) return "{}";
        try {
            var summary = new ExportedSummary(
                lastResult.events.size(),
                lastResult.machines.size(),
                lastResult.edges.size(),
                lastResult.classesScanned,
                lastResult.classesSkipped,
                lastResult.durationMs
            );
            
            var events = new java.util.ArrayList<ExportedEvent>();
            for (var e : lastResult.events) {
                events.add(new ExportedEvent(
                    e.eventType(),
                    e.className(),
                    e.methodName(),
                    e.modId(),
                    e.conditions().size(),
                    e.actions().size()
                ));
            }
            
            var machines = new java.util.ArrayList<ExportedMachine>();
            for (var m : lastResult.machines) {
                machines.add(new ExportedMachine(
                    m.className(),
                    m.modId(),
                    new java.util.ArrayList<>(m.inputItems()),
                    new java.util.ArrayList<>(m.outputItems()),
                    m.deterministic()
                ));
            }
            
            var edges = new java.util.ArrayList<ExportedEdge>();
            for (var edge : lastResult.edges) {
                edges.add(new ExportedEdge(
                    edge.from(),
                    edge.to(),
                    edge.action(),
                    edge.weight(),
                    edge.source() != null ? edge.source().name() : "UNKNOWN",
                    edge.conditions().size()
                ));
            }
            
            return GSON.toJson(new ExportedData(summary, events, machines, edges));
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Bytecode] Failed to export results to JSON", e);
            return "{}";
        }
    }
}