/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.geoscan.config;

public final class ScanConfig {

    private ScanConfig() {
    }

    public enum ScanProfile {
        QUARTER(0.25f, 4, 30.0f),
        HALF(0.50f, 8, 40.0f),
        MOST(0.75f, 16, 50.0f),
        FULL(1.00f, 32, -1.0f);

        public final float threadFraction;
        public final int maxParallelChunks;
        public final float msptLimit;

        ScanProfile(float threadFraction, int maxParallelChunks, float msptLimit) {
            this.threadFraction = threadFraction;
            this.maxParallelChunks = maxParallelChunks;
            this.msptLimit = msptLimit;
        }

        public int getWorkerCount(int totalThreads) {
            return Math.max(1, Math.round(totalThreads * threadFraction));
        }

        public boolean hasMsptLimit() {
            return msptLimit > 0;
        }
    }

    public static final int COUNTDOWN_SECONDS = 60;
    public static final int FULL_WORLD_THRESHOLD = 60;
    public static final int RADIUS_MAX = 64000;
    public static final int RADIUS_FULL_WORLD = 1_000_000;
    public static final int BATCH_SAVE_THRESHOLD = 32;
    public static final int MSPT_RECOVERY_THRESHOLD_MS = 5;
    public static final int MSPT_SAMPLE_COUNT = 5;
}