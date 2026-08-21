/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.geoscan.config;

import net.minecraft.ChatFormatting;

public final class ScanConfig {

    public static final int COUNTDOWN_SECONDS = 60;
    public static final int FULL_WORLD_THRESHOLD = 60;
    public static final int RADIUS_MAX = 64000;
    public static final int RADIUS_FULL_WORLD = 1_000_000;
    public static final int BATCH_SAVE_THRESHOLD = 16;
    public static final int MSPT_RECOVERY_THRESHOLD_MS = 5;
    public static final int MSPT_SAMPLE_COUNT = 5;

    private ScanConfig() {
    }

    public enum ScanProfile {
        NORMAL("normal", "Normal", "🟢", ChatFormatting.GREEN, 30.0f, 4, 16, 4, 2, 3, 128, 128, 128, 2),
        FAST("fast", "Fast", "🟡", ChatFormatting.YELLOW, 40.0f, 6, 24, 8, 4, 6, 256, 512, 512, 2),
        AGGRESSIVE("aggressive", "Aggressive", "🟠", ChatFormatting.GOLD, 50.0f, 10, 48, 12, 6, 9, 512, 1024, 1024, 3),
        UNLIMITED("unlimited", "Unlimited", "🔴", ChatFormatting.RED, -1.0f, 16, 96, 24, 10, 16, 1024, 2048, 2048, 4);

        public final String commandName;
        public final String displayName;
        public final String icon;
        public final ChatFormatting color;
        public final float msptLimit;
        private final int emptyBatchTolerance;
        private final int stagnantBase;
        private final int batchNoLimit;
        private final int batch70;
        private final int batch50;
        private final int budgetMin;
        private final int budgetMax;
        private final int budgetFixed;
        private final int maxPendingAnalysisBatches;

        ScanProfile(String commandName, String displayName, String icon, ChatFormatting color, float msptLimit,
                    int emptyBatchTolerance, int stagnantBase, int batchNoLimit, int batch70, int batch50,
                    int budgetMin, int budgetMax, int budgetFixed, int maxPendingAnalysisBatches) {
            this.commandName = commandName;
            this.displayName = displayName;
            this.icon = icon;
            this.color = color;
            this.msptLimit = msptLimit;
            this.emptyBatchTolerance = emptyBatchTolerance;
            this.stagnantBase = stagnantBase;
            this.batchNoLimit = batchNoLimit;
            this.batch70 = batch70;
            this.batch50 = batch50;
            this.budgetMin = budgetMin;
            this.budgetMax = budgetMax;
            this.budgetFixed = budgetFixed;
            this.maxPendingAnalysisBatches = maxPendingAnalysisBatches;
        }

        public static ScanProfile fromInput(String input) {
            for (var profile : values()) if (profile.commandName.equalsIgnoreCase(input)) return profile;
            throw new IllegalArgumentException("Unknown profile: " + input);
        }

        public boolean hasMsptLimit() {
            return msptLimit > 0;
        }

        public ScanPolicy policy(int chunksPerBiome, float currentMspt, boolean msptLimitEnabled) {
            int stagnantExtra = Math.clamp(chunksPerBiome / 32, 0, 256);
            int stagnantBatchTolerance = stagnantBase + stagnantExtra;
            int batchSize = getBatchSize(currentMspt, msptLimitEnabled);
            int maxScannedBudget = getMaxScannedBudget(chunksPerBiome);

            return new ScanPolicy(
                    batchSize,
                    emptyBatchTolerance,
                    stagnantBatchTolerance,
                    maxScannedBudget,
                    maxPendingAnalysisBatches
            );
        }

        private int getBatchSize(float currentMspt, boolean msptLimitEnabled) {
            if (!msptLimitEnabled || msptLimit <= 0) return batchNoLimit;
            float limit = msptLimit;
            if (currentMspt > limit * 0.9f) return 2;
            if (currentMspt > limit * 0.7f) return batch70;
            if (currentMspt > limit * 0.5f) return batch50;
            return batchNoLimit;
        }

        private int getMaxScannedBudget(int chunksPerBiome) {
            int chunks = Math.max(1, chunksPerBiome);
            if (this == NORMAL) return budgetFixed;
            long scaled = (long) chunks * 2L;
            return Math.clamp(scaled, budgetMin, budgetMax);
        }

        public record ScanPolicy(
                int batchSize,
                int emptyBatchTolerance,
                int stagnantBatchTolerance,
                int maxScannedBudget,
                int maxPendingAnalysisBatches
        ) {
        }
    }
}