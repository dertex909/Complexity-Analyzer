package org.complexityanalyzer.bytecode;

public final class ConfidenceModel {

    public static final double DIRECT_CALL = 1.0;
    public static final double SINGLE_HOP = 0.7;
    public static final double HEURISTIC = 0.4;
    public static final double WEAK_PATTERN = 0.1;

    private ConfidenceModel() {
    }

    public static double fromHopDistance(int distance) {
        if (distance <= 0) return DIRECT_CALL;
        if (distance == 1) return SINGLE_HOP;
        if (distance <= 3) return HEURISTIC;
        return Math.max(WEAK_PATTERN, 1.0 / (1.0 + distance));
    }

    public static double fromProfileMatch(double avgConfidence, int matchedRequired, int totalRequired) {
        if (totalRequired == 0) return HEURISTIC;
        double ratio = (double) matchedRequired / totalRequired;
        return avgConfidence * ratio;
    }
}
