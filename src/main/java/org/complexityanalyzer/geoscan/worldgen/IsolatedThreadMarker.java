package org.complexityanalyzer.geoscan.worldgen;

public final class IsolatedThreadMarker {

    private static final ThreadLocal<Boolean> ISOLATED = ThreadLocal.withInitial(() -> false);

    private IsolatedThreadMarker() {
    }

    public static void markIsolated() {
        ISOLATED.set(true);
    }

    public static void unmarkIsolated() {
        ISOLATED.set(false);
    }

    public static boolean isIsolatedThread() {
        return ISOLATED.get();
    }
}