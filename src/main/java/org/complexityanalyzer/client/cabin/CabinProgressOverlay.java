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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.client.cabin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.complexityanalyzer.network.cabin.CabinPayloads;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class CabinProgressOverlay {
    private static final AtomicLong lastUpdate = new AtomicLong(0);

    private CabinProgressOverlay() {
    }

    public static void beginReceive(CabinPayloads.ManifestS2C m) {
        lastUpdate.set(0);
        show("⏬ Receiving cabin (0%) — " + human(m.totalSize()), ChatFormatting.AQUA);
    }

    public static void update(long received, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate.get() < 100 && received < total) return;
        lastUpdate.set(now);
        double pct = total > 0 ? (100.0 * received / total) : 0;
        int filled = (int) (pct / 5);
        String bar = "█".repeat(filled) + "░".repeat(20 - filled);
        show(String.format(Locale.ROOT, "⏬ [%s] %.1f%% — %s / %s", bar, pct, human(received), human(total)), ChatFormatting.AQUA);
    }

    public static void complete(CabinReceiver.CachedSnapshot snap) {
        show("✅ Cabin ready — " + snap.itemCount() + " items, " + snap.mobCount() + " mobs", ChatFormatting.GREEN);
    }

    public static void upToDate() {
        show("✅ Cabin is up-to-date", ChatFormatting.GREEN);
    }

    public static void fail(String reason) {
        show("❌ Cabin failed: " + reason, ChatFormatting.RED);
    }

    private static void show(String text, ChatFormatting style) {
        show(Component.literal(text).withStyle(style));
    }

    private static void show(Component comp) {
        try {
            var mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(comp, true);
        } catch (Throwable ignored) {
        }
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        int exp = (int) (Math.log(n) / Math.log(1024));
        return String.format(Locale.ROOT, "%.1f %sB", n / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }
}