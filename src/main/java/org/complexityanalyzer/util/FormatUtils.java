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
            return item.getDescription().getString();
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
        var b = Component.translatable("complexityanalyzer.unit.size.bytes").getString();
        var kb = Component.translatable("complexityanalyzer.unit.size.kilobytes").getString();
        var mb = Component.translatable("complexityanalyzer.unit.size.megabytes").getString();
        var gb = Component.translatable("complexityanalyzer.unit.size.gigabytes").getString();

        if (bytes < 1024) return bytes + " " + b;
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f %s", bytes / 1024.0, kb);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f %s", bytes / (1024.0 * 1024), mb);
        return String.format(Locale.US, "%.2f %s", bytes / (1024.0 * 1024 * 1024), gb);
    }

    public static String formatDurationSeconds(long seconds) {
        long s = Math.max(0, seconds);
        var sSuffix = Component.translatable("complexityanalyzer.unit.time.seconds_short").getString();
        var mSuffix = Component.translatable("complexityanalyzer.unit.time.minutes_short").getString();
        var hSuffix = Component.translatable("complexityanalyzer.unit.time.hours_short").getString();
        var dSuffix = Component.translatable("complexityanalyzer.unit.time.days_short").getString();

        if (s < 60) return s + sSuffix;
        long min = s / 60;
        if (min < 60) return min + mSuffix + " " + (s % 60) + sSuffix;
        long hours = min / 60;
        if (hours < 24) return hours + hSuffix + " " + (min % 60) + mSuffix;
        return (hours / 24) + dSuffix + " " + (hours % 24) + hSuffix;
    }

    public static String formatDurationMs(long ms) {
        return formatDurationSeconds(ms / 1000);
    }

    public static String formatAge(Duration d) {
        return formatDurationSeconds(d.getSeconds());
    }
}