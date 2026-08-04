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

package org.complexityanalyzer.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.core.GameRegistryManager;

import java.time.Duration;
import java.util.Locale;

public final class FormatUtils {

    private FormatUtils() {
    }

    public static String safeDisplayName(Item item) {
        if (item == null) return "unknown";
        try {
            return item.getDefaultInstance().getHoverName().getString();
        } catch (Throwable t) {
            var id = GameRegistryManager.getItemId(item);
            return id != null ? id.toString() : "unknown";
        }
    }

    public static String safeDisplayName(Fluid fluid) {
        if (fluid == null) return "unknown";
        try {
            return fluid.getFluidType().getDescription().getString();
        } catch (Throwable t) {
            var id = GameRegistryManager.getFluidId(fluid);
            return id != null ? id.toString() : "unknown";
        }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " " + Component.translatable("complexityanalyzer.unit.size.bytes").getString();

        if (bytes < (1L << 20)) {
            var kb = Component.translatable("complexityanalyzer.unit.size.kilobytes").getString();
            return String.format(Locale.US, "%.1f %s", bytes / 1024.0, kb);
        }

        if (bytes < (1L << 30)) {
            var mb = Component.translatable("complexityanalyzer.unit.size.megabytes").getString();
            return String.format(Locale.US, "%.1f %s", bytes / (1024.0 * 1024.0), mb);
        }

        var gb = Component.translatable("complexityanalyzer.unit.size.gigabytes").getString();
        return String.format(Locale.US, "%.2f %s", bytes / (1024.0 * 1024.0 * 1024.0), gb);
    }

    public static String formatDurationSeconds(long seconds) {
        long s = Math.max(0, seconds);

        if (s < 60) return s + Component.translatable("complexityanalyzer.unit.time.seconds_short").getString();

        long min = s / 60;
        var sSuffix = Component.translatable("complexityanalyzer.unit.time.seconds_short").getString();
        var mSuffix = Component.translatable("complexityanalyzer.unit.time.minutes_short").getString();

        if (min < 60) return min + mSuffix + " " + (s % 60) + sSuffix;

        long hours = min / 60;
        var hSuffix = Component.translatable("complexityanalyzer.unit.time.hours_short").getString();

        if (hours < 24) return hours + hSuffix + " " + (min % 60) + mSuffix;

        var dSuffix = Component.translatable("complexityanalyzer.unit.time.days_short").getString();
        return (hours / 24) + dSuffix + " " + (hours % 24) + hSuffix;
    }

    public static String formatDurationMs(long ms) {
        return formatDurationSeconds(ms / 1000);
    }

    public static String formatAge(Duration d) {
        return formatDurationSeconds(d.getSeconds());
    }
}