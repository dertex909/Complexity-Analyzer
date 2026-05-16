package org.complexityanalyzer.bytecode.model;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.Collections;
import java.util.Map;

public final class SemanticEdge {
    public enum EdgeSource {
        DIRECT_CALL,
        SINGLE_HOP,
        HEURISTIC,
        WEAK_PATTERN
    }

    private final String from;
    private final String to;
    private final String action;
    private final ObjectList<String> conditions;
    private final double weight;
    private final EdgeSource source;
    private final Map<String, Object> context;

    private SemanticEdge(Builder builder) {
        this.from = builder.from;
        this.to = builder.to;
        this.action = builder.action;
        this.conditions = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.conditions));
        this.weight = builder.weight;
        this.source = builder.source;
        this.context = Collections.unmodifiableMap(new Object2ObjectOpenHashMap<>(builder.context));
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public String action() {
        return action;
    }

    public ObjectList<String> conditions() {
        return conditions;
    }

    public double weight() {
        return weight;
    }

    public EdgeSource source() {
        return source;
    }

    public Map<String, Object> context() {
        return context;
    }

    public String edgeKey() {
        return from + "->" + to + "@" + action;
    }

    public static final class Builder {
        private String from = "";
        private String to = "";
        private String action = "";
        private final ObjectList<String> conditions = new ObjectArrayList<>();
        private double weight = 1.0;
        private EdgeSource source = EdgeSource.DIRECT_CALL;
        private final Map<String, Object> context = new Object2ObjectOpenHashMap<>();

        public Builder from(String v) {
            this.from = v;
            return this;
        }

        public Builder to(String v) {
            this.to = v;
            return this;
        }

        public Builder action(String v) {
            this.action = v;
            return this;
        }

        public void addCondition(String v) {
            this.conditions.add(v);
        }

        public Builder weight(double v) {
            this.weight = v;
            return this;
        }

        public Builder source(EdgeSource v) {
            this.source = v;
            return this;
        }

        public Builder putContext(String k, Object v) {
            this.context.put(k, v);
            return this;
        }

        public SemanticEdge build() {
            return new SemanticEdge(this);
        }
    }

    @Override
    public String toString() {
        return "SemanticEdge{" + from + " -> " + to + " [" + action + "] w=" + weight + '}';
    }
}
