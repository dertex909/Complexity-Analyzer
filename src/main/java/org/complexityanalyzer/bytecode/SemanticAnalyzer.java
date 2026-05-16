package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.bytecode.graph.CallGraph;
import org.complexityanalyzer.bytecode.graph.MethodRef;

import java.util.*;

public final class SemanticAnalyzer {

    private static final String[] TICK_NAMES = {
            "tick", "serverTick", "clientTick", "onTick", "update",
            "blockEntityTick", "containerTick", "tickServer", "tickClient",
            "preTick", "postTick", "doTick", "onUpdate"
    };

    private SemanticAnalyzer() {
    }

    public static Object2ObjectMap<MethodRef, SemanticProfile> analyze(CallGraph graph, SemanticAnchorRegistry registry) {
        var profiles = new Object2ObjectOpenHashMap<MethodRef, SemanticProfile.Builder>();

        // Phase 1: find anchor nodes (external methods matching registry)
        var anchors = new Object2ObjectOpenHashMap<MethodRef, SemanticTag>();
        for (var node : graph.externalNodes()) {
            SemanticTag tag = registry.resolve(node);
            if (tag != null) {
                anchors.put(node, tag);
            }
        }

        // Phase 2: internal TICK anchors
        for (var node : graph.internalNodes()) {
            if (isTickMethod(node.name())) {
                anchors.put(node, SemanticTag.TICK);
            }
        }

        ComplexityAnalyzer.LOGGER.debug("[SemanticAnalyzer] Found {} anchors in graph (mod={})", anchors.size(), graph.modId());

        // Phase 3: reverse DFS from each anchor
        for (var entry : anchors.entrySet()) {
            MethodRef anchor = entry.getKey();
            SemanticTag tag = entry.getValue();
            propagateReverse(graph, anchor, tag, profiles);
        }

        // Phase 4: build immutable profiles
        var result = new Object2ObjectOpenHashMap<MethodRef, SemanticProfile>();
        for (var entry : profiles.entrySet()) {
            result.put(entry.getKey(), entry.getValue().build());
        }

        return result;
    }

    private static void propagateReverse(CallGraph graph, MethodRef anchor, SemanticTag tag,
                                         Object2ObjectMap<MethodRef, SemanticProfile.Builder> profiles) {
        var queue = new ArrayDeque<QueueNode>();
        var visited = new Object2ObjectOpenHashMap<MethodRef, Integer>();

        queue.add(new QueueNode(anchor, 0));
        visited.put(anchor, 0);

        while (!queue.isEmpty()) {
            QueueNode current = queue.poll();
            double confidence = ConfidenceModel.fromHopDistance(current.distance);

            // Update profile for current node
            var builder = profiles.computeIfAbsent(current.ref, k -> new SemanticProfile.Builder()
                    .className(current.ref.owner())
                    .methodName(current.ref.name() + current.ref.descriptor()));
            builder.addTag(tag, confidence);

            // Walk backwards to callers
            for (var caller : graph.callersOf(current.ref)) {
                int existingDist = visited.getOrDefault(caller, Integer.MAX_VALUE);
                int newDist = current.distance + 1;
                if (newDist < existingDist && newDist <= 10) { // max 10 hops
                    visited.put(caller, newDist);
                    queue.add(new QueueNode(caller, newDist));
                }
            }
        }
    }

    private static boolean isTickMethod(String name) {
        for (var tn : TICK_NAMES) {
            if (name.equals(tn)) return true;
        }
        return false;
    }

    private record QueueNode(MethodRef ref, int distance) {
    }
}
