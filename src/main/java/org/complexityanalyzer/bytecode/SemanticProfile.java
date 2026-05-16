package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;

public final class SemanticProfile {

    private final String className;
    private final String methodName;
    private final Object2DoubleOpenHashMap<SemanticTag> tagConfidences;

    private SemanticProfile(Builder builder) {
        this.className = builder.className;
        this.methodName = builder.methodName;
        this.tagConfidences = new Object2DoubleOpenHashMap<>(builder.tagConfidences);
        this.tagConfidences.defaultReturnValue(0.0);
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public double confidence(SemanticTag tag) {
        return tagConfidences.getOrDefault(tag, 0.0);
    }

    public boolean hasTag(SemanticTag tag) {
        return tagConfidences.getDouble(tag) > 0.0;
    }

    public ObjectSet<SemanticTag> tags() {
        return tagConfidences.keySet();
    }

    public boolean matchesProfile(SemanticProfileDefinition definition, double minThreshold) {
        for (SemanticTag req : definition.required()) {
            if (confidence(req) < minThreshold) return false;
        }
        int matched = definition.required().size();
        for (SemanticTag opt : definition.optional()) {
            if (confidence(opt) >= minThreshold) matched++;
        }
        return matched >= definition.required().size();
    }

    @Override
    public String toString() {
        return "SemanticProfile{" + className + "." + methodName + " tags=" + tagConfidences + '}';
    }

    public static final class Builder {
        private String className = "";
        private String methodName = "";
        private final Object2DoubleOpenHashMap<SemanticTag> tagConfidences = new Object2DoubleOpenHashMap<>();

        public Builder className(String v) {
            this.className = v;
            return this;
        }

        public Builder methodName(String v) {
            this.methodName = v;
            return this;
        }

        public Builder addTag(SemanticTag tag, double confidence) {
            double existing = tagConfidences.getOrDefault(tag, 0.0);
            if (confidence > existing) {
                tagConfidences.put(tag, confidence);
            }
            return this;
        }

        public SemanticProfile build() {
            return new SemanticProfile(this);
        }
    }

    public record SemanticProfileDefinition(
            String name,
            java.util.Set<SemanticTag> required,
            java.util.Set<SemanticTag> optional,
            double minConfidence
    ) {
    }
}
