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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight progress indicator shown in the action-bar (above hotbar) while a
 * cabin transfer is active. Updates throttled to ~10 Hz to avoid spamming the
 * chat manager during high-speed local transfers.
 * <p>
 * Action-bar is preferred over BossEvent because BossEvent requires a server-side
 * packet that we'd have to forge locally; the action-bar message API
 * ({@code Player.displayClientMessage(text, true)}) is a single client call.
 */
public final class CabinProgressOverlay {

    private static final long MIN_UPDATE_INTERVAL_MS = 100L;
    private static final AtomicReference<Long> lastUpdateMs = new AtomicReference<>(0L);

    private CabinProgressOverlay() {
    }

    public static void beginReceive(CabinPayloads.ManifestS2C manifest) {
        lastUpdateMs.set(0L);
        showActionBar(Component.literal("⏬ Receiving cabin (0%) — ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(humanBytes(manifest.totalSize()))
                        .withStyle(ChatFormatting.GRAY)));
    }

    public static void update(long receivedBytes, long totalBytes) {
        long now = System.currentTimeMillis();
        Long last = lastUpdateMs.get();
        if (now - last < MIN_UPDATE_INTERVAL_MS && receivedBytes < totalBytes) return;
        if (!lastUpdateMs.compareAndSet(last, now)) return;

        double pct = totalBytes > 0 ? (100.0 * receivedBytes / totalBytes) : 0.0;
        int filled = (int) Math.round(pct / 5.0);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 20; i++) bar.append(i < filled ? '#' : '-');
        bar.append("]");
        showActionBar(Component.literal("⏬ " + bar + " ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(String.format(Locale.ROOT, "%.1f%%", pct))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" — " + humanBytes(receivedBytes) + " / "
                                + humanBytes(totalBytes))
                        .withStyle(ChatFormatting.GRAY)));
    }

    public static void complete(CabinReceiver.CachedSnapshot snapshot) {
        showActionBar(Component.literal("✅ Cabin ready — "
                        + snapshot.itemCount() + " items, " + snapshot.mobCount() + " mobs")
                .withStyle(ChatFormatting.GREEN));
    }

    public static void upToDate() {
        showActionBar(Component.literal("✅ Cabin is up-to-date").withStyle(ChatFormatting.GREEN));
    }

    public static void fail(String reason) {
        showActionBar(Component.literal("❌ Cabin failed: " + reason).withStyle(ChatFormatting.RED));
    }

    public static void dismiss() {
        showActionBar(Component.literal(""));
    }

    private static void showActionBar(Component component) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(component, true);
        } catch (Throwable ignored) {
        }
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        double k = n / 1024.0;
        if (k < 1024) return String.format(Locale.ROOT, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.ROOT, "%.1f MB", m);
        return String.format(Locale.ROOT, "%.2f GB", m / 1024.0);
    }
}
