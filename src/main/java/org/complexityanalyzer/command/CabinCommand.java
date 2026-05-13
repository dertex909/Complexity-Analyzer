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

package org.complexityanalyzer.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;
import org.complexityanalyzer.network.multiplex.CabinNettyHandler;

public final class CabinCommand {

    private CabinCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("cabin").executes(CabinCommand::executeGet).then(Commands.literal("get")
                .executes(CabinCommand::executeGet)).then(Commands.literal("status")
                        .executes(CabinCommand::executeStatus))
                .then(Commands.literal("regenerate")
                        .requires(source -> source.hasPermission(2))
                        .executes(CabinCommand::executeRegenerate))
                .then(Commands.literal("web")
                        .executes(CabinCommand::executeWeb));
    }

    public static int executeGet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal("This command must be run as a player"));
            return 0;
        }
        CabinBackgroundService svc = CabinBackgroundService.getInstance();
        if (svc.getSnapshot() == null && svc.getStatus() != CabinBackgroundService.Status.BUILDING) {
            source.sendSuccess(() -> Component.literal("📦 Cabin not ready, initiating build...")
                    .withStyle(ChatFormatting.YELLOW), false);
            executeRegenerate(ctx);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("📦 Cabin is ready. Use /complexity cabin web to view.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    public static int executeStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CabinBackgroundService svc = CabinBackgroundService.getInstance();
        CabinBackgroundService.Snapshot snap = svc.getSnapshot();
        CabinBackgroundService.Status status = svc.getStatus();
        MutableComponent msg = Component.literal("📊 Cabin status: ").withStyle(ChatFormatting.GOLD);
        msg.append(Component.literal(status.name()).withStyle(statusColor(status)));
        if (snap != null) {
            long ageMs = System.currentTimeMillis() - snap.generatedAtMs();
            msg.append(Component.literal("\n  Age: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(humanDuration(ageMs) + " ago").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("\n  Hash: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(Long.toHexString(snap.fileHash())).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("\n  Size: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(humanBytes(snap.bytes().length)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("\n  Data: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(snap.itemCount() + " items, " + snap.mobCount() + " mobs, "
                            + snap.recipeCount() + " recipes").withStyle(ChatFormatting.WHITE));
        } else {
            msg.append(Component.literal("\n  (no snapshot yet — generation may still be in progress)")
                    .withStyle(ChatFormatting.GRAY));
        }
        Throwable err = svc.getLastError();
        if (err != null)
            msg.append(Component.literal("\n  Last error: ").withStyle(ChatFormatting.RED))
                    .append(Component.literal(String.valueOf(err.getMessage())).withStyle(ChatFormatting.DARK_RED));
        source.sendSuccess(() -> msg, false);
        return 1;
    }

    public static int executeRegenerate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) {
            source.sendFailure(Component.literal("Engine not ready: " + engine.getCurrentState()));
            return 0;
        }
        String modVersion = ModList.get().getModContainerById(ComplexityAnalyzer.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        source.sendSuccess(() -> Component.literal("🔄 Regenerating cabin in background...")
                .withStyle(ChatFormatting.AQUA), true);
        CabinBackgroundService.getInstance().regenerateAsync(server, engine, modVersion).whenComplete((snap, err) -> {
            MutableComponent done;
            if (err != null) {
                done = Component.literal("❌ Cabin build failed: " + err.getMessage()).withStyle(ChatFormatting.RED);
            } else if (snap != null) {
                done = Component.literal("✅ Cabin built: " + humanBytes(snap.bytes().length) + ", hash="
                        + Long.toHexString(snap.fileHash())).withStyle(ChatFormatting.GREEN);
            } else {
                done = Component.literal("⚠ Cabin build returned no snapshot").withStyle(ChatFormatting.YELLOW);
            }
            server.execute(() -> source.sendSuccess(() -> done, true));
        });
        return 1;
    }

    public static int executeWeb(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;

        String url = CabinNettyHandler.getUrl(player);

        if (url == null) {
            MutableComponent msg = Component.literal("⚠ Web Dashboard is not active.").withStyle(ChatFormatting.YELLOW)
                    .append(Component
                            .literal("\nTo use it in singleplayer, you must click 'Open to LAN' in the Escape menu.")
                            .withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> msg, false);
            return 0;
        }

        MutableComponent msg = Component.literal("🌐 Web Dashboard: ").withStyle(ChatFormatting.GOLD);
        msg.append(Component.literal("[Open in Browser]").withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                .withStyle(
                        style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)).withHoverEvent(
                                new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to open: " + url)))));

        if (url.contains("127.0.0.1"))
            msg.append(Component.literal(
                    "\n  (Note: If you are on a remote server, replace 127.0.0.1 with the server's public IP address)")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

        source.sendSuccess(() -> msg, false);
        return 1;
    }

    private static ChatFormatting statusColor(CabinBackgroundService.Status s) {
        return switch (s) {
            case READY -> ChatFormatting.GREEN;
            case BUILDING -> ChatFormatting.AQUA;
            case FAILED -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }

    private static String humanBytes(long n) {
        if (n < 1024)
            return n + " B";
        double k = n / 1024.0;
        if (k < 1024)
            return String.format(java.util.Locale.ROOT, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024)
            return String.format(java.util.Locale.ROOT, "%.1f MB", m);
        return String.format(java.util.Locale.ROOT, "%.2f GB", m / 1024.0);
    }

    private static String humanDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60)
            return sec + "s";
        long min = sec / 60;
        if (min < 60)
            return min + "m " + (sec % 60) + "s";
        long hour = min / 60;
        if (hour < 24)
            return hour + "h " + (min % 60) + "m";
        return (hour / 24) + "d " + (hour % 24) + "h";
    }
}