package org.complexityanalyzer.geoscan.config;

public final class ScanConfig {

    private ScanConfig() {
    }

    public enum ScanProfile {
        QUARTER(0.25f),
        HALF(0.50f),
        MOST(0.75f),
        FULL(1.00f);

        public final float threadFraction;

        ScanProfile(float threadFraction) {
            this.threadFraction = threadFraction;
        }

        public int getWorkerCount(int totalThreads) {
            return Math.max(1, Math.round(totalThreads * threadFraction));
        }
    }

    public static final int COUNTDOWN_SECONDS = 60;
    public static final int FULL_WORLD_THRESHOLD = 60;

    public static final int RADIUS_NORMAL = 6400;
    public static final int RADIUS_EXTENDED = 32000;
    public static final int RADIUS_MAX = 64000;
    public static final int RADIUS_FULL_WORLD = 1_000_000;

    public static final int RELOCATION_MIN_DISTANCE = 500;
    public static final int RELOCATION_ORIGINS_COUNT = 5;

    public static final int BATCH_SIZE = 64;
    public static final int MAX_ATTEMPTS_PER_CHUNK = 50;
    public static final int MIN_ATTEMPTS = 2000;
    public static final int MAX_ATTEMPTS = 20000;
    public static final int MAX_RELOCATIONS = 50;
    public static final int STUCK_THRESHOLD = 200;

    public static final int BATCH_SAVE_THRESHOLD = 200;
    public static final int CHUNK_SEARCH_BATCH = 500;

    public static final int SHUTDOWN_AWAIT_SECONDS = 1;
    public static final int SCAN_TIMEOUT_MINUTES = 60;
}