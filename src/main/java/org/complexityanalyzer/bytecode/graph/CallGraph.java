package org.complexityanalyzer.bytecode.graph;

import it.unimi.dsi.fastutil.objects.*;

public final class CallGraph {

    private final String modId;
    private final ObjectSet<MethodRef> internalNodes;
    private final ObjectSet<MethodRef> externalNodes;
    private final Object2ObjectMap<MethodRef, ObjectSet<MethodRef>> edges;
    private final Object2ObjectMap<MethodRef, ObjectSet<MethodRef>> reverseEdges;

    CallGraph(String modId,
              ObjectSet<MethodRef> internalNodes,
              ObjectSet<MethodRef> externalNodes,
              Object2ObjectMap<MethodRef, ObjectSet<MethodRef>> edges,
              Object2ObjectMap<MethodRef, ObjectSet<MethodRef>> reverseEdges) {
        this.modId = modId;
        this.internalNodes = new ObjectOpenHashSet<>(internalNodes);
        this.externalNodes = new ObjectOpenHashSet<>(externalNodes);
        this.edges = new Object2ObjectOpenHashMap<>(edges);
        this.reverseEdges = new Object2ObjectOpenHashMap<>(reverseEdges);
    }

    public String modId() {
        return modId;
    }

    public ObjectSet<MethodRef> allNodes() {
        var all = new ObjectOpenHashSet<MethodRef>(internalNodes);
        all.addAll(externalNodes);
        return all;
    }

    public ObjectSet<MethodRef> internalNodes() {
        return internalNodes;
    }

    public ObjectSet<MethodRef> externalNodes() {
        return externalNodes;
    }

    public ObjectSet<MethodRef> callersOf(MethodRef callee) {
        return reverseEdges.getOrDefault(callee, ObjectSets.emptySet());
    }

    public ObjectSet<MethodRef> calleesOf(MethodRef caller) {
        return edges.getOrDefault(caller, ObjectSets.emptySet());
    }

    public boolean hasNode(MethodRef ref) {
        return internalNodes.contains(ref) || externalNodes.contains(ref);
    }

    public boolean hasEdge(MethodRef from, MethodRef to) {
        var set = edges.get(from);
        return set != null && set.contains(to);
    }

    public int nodeCount() {
        return internalNodes.size() + externalNodes.size();
    }

    public int edgeCount() {
        int count = 0;
        for (var set : edges.values()) count += set.size();
        return count;
    }

    public static final class Builder {
        private String modId = "";
        private final ObjectSet<MethodRef> internalNodes = new ObjectOpenHashSet<>();
        private final ObjectSet<MethodRef> externalNodes = new ObjectOpenHashSet<>();
        private final Object2ObjectMap<MethodRef, ObjectSet<MethodRef>> edges = new Object2ObjectOpenHashMap<>();
        private final Object2ObjectMap<MethodRef, ObjectSet<MethodRef>> reverseEdges = new Object2ObjectOpenHashMap<>();

        public Builder modId(String v) {
            this.modId = v;
            return this;
        }

        public Builder addInternalNode(MethodRef ref) {
            internalNodes.add(ref);
            return this;
        }

        public Builder addExternalNode(MethodRef ref) {
            externalNodes.add(ref);
            return this;
        }

        public Builder addEdge(MethodRef from, MethodRef to) {
            edges.computeIfAbsent(from, k -> new ObjectOpenHashSet<>()).add(to);
            reverseEdges.computeIfAbsent(to, k -> new ObjectOpenHashSet<>()).add(from);
            return this;
        }

        public CallGraph build() {
            return new CallGraph(modId, internalNodes, externalNodes, edges, reverseEdges);
        }
    }
}
