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

package org.complexityanalyzer.geoscan.scan;

import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.config.ScanConfig;

import java.util.concurrent.atomic.AtomicBoolean;

public class MsptMonitor {

    private final MinecraftServer server;
    private final float msptLimit;
    private final float resumeThreshold;

    private final AtomicBoolean isThrottled = new AtomicBoolean(false);
    private final float[] msptSamples = new float[ScanConfig.MSPT_SAMPLE_COUNT];
    private int sampleIndex = 0;
    private boolean samplesInitialized = false;

    private volatile long lastLogTime = 0;
    private static final long LOG_COOLDOWN_MS = 10_000;

    public MsptMonitor(MinecraftServer server, ScanConfig.ScanProfile profile) {
        this.server = server;
        this.msptLimit = profile.msptLimit;
        this.resumeThreshold = profile.hasMsptLimit() ? profile.msptLimit - ScanConfig.MSPT_RECOVERY_THRESHOLD_MS : -1;
    }

    public void update() {
        if (msptLimit < 0) return;
        float currentMspt = getCurrentMspt();
        addSample(currentMspt);
        if (isThrottled.get()) {
            float avgMspt = getAverageMspt();
            if (avgMspt < resumeThreshold) {
                isThrottled.set(false);
                logThrottleChange(false, avgMspt);
            }
        } else {
            if (currentMspt > msptLimit) {
                isThrottled.set(true);
                logThrottleChange(true, currentMspt);
            }
        }
    }

    public boolean isThrottled() {
        return isThrottled.get();
    }

    public float getCurrentMspt() {
        long[] tickTimes = server.getTickTimesNanos();
        if (tickTimes.length == 0) return 0;

        long sum = 0;
        int count = Math.min(20, tickTimes.length);

        for (int i = 0; i < count; i++) {
            sum += tickTimes[i];
        }

        return (float) sum / count / 1_000_000.0f;
    }

    public float getAverageMspt() {
        if (!samplesInitialized) return getCurrentMspt();

        float sum = 0;
        for (float sample : msptSamples) {
            sum += sample;
        }
        return sum / msptSamples.length;
    }

    public float getMsptLimit() {
        return msptLimit;
    }

    public boolean hasLimit() {
        return msptLimit > 0;
    }

    private void addSample(float mspt) {
        msptSamples[sampleIndex] = mspt;
        sampleIndex = (sampleIndex + 1) % msptSamples.length;
        if (sampleIndex == 0) samplesInitialized = true;
    }

    private void logThrottleChange(boolean throttled, float avgMspt) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime < LOG_COOLDOWN_MS) return;
        lastLogTime = now;

        if (throttled) {
            ComplexityAnalyzer.LOGGER.info("[MsptMonitor] Scan PAUSED — MSPT {} > limit {}",
                    String.format("%.1f", avgMspt),
                    String.format("%.1f", msptLimit));
        } else {
            ComplexityAnalyzer.LOGGER.info("[MsptMonitor] Scan RESUMED — MSPT {} < threshold {}",
                    String.format("%.1f", avgMspt),
                    String.format("%.1f", resumeThreshold));
        }
    }
}