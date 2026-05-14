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

import java.util.Locale;

public final class WebCommand {

    private static String publicIp = "127.0.0.1";
    private static boolean ipDetected = false;

    private WebCommand() {
    }

    public static void setPublicIp(String ip) {
        publicIp = ip;
        ipDetected = true;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("web")
                .then(Commands.literal("url").executes(WebCommand::executeUrl))
                .then(Commands.literal("status").executes(WebCommand::executeStatus))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(WebCommand::executeReload));
    }

    public static int executeUrl(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;

        String url = CabinNettyHandler.getUrl(player);

        if (url == null) {
            MutableComponent msg = Component.literal("⚠ Web Dashboard is not active.").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("\nTo use it in singleplayer, you must click 'Open to LAN' in the Escape menu.").withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> msg, false);
            return 0;
        }

        if ((url.contains("127.0.0.1") || url.contains("localhost")) && ipDetected) {
            url = url.replace("127.0.0.1", publicIp).replace("localhost", publicIp);
        }

        String finalUrl = url;
        MutableComponent msg = Component.literal("🌐 Web Dashboard: ").withStyle(ChatFormatting.GOLD);
        msg.append(Component.literal("[Open in Browser]").withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, finalUrl))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to open: " + finalUrl)))));

        if ((finalUrl.contains("127.0.0.1") || finalUrl.contains("localhost")) && ipDetected) msg.append(
                Component.literal("\n  For friends: ").withStyle(ChatFormatting.GRAY)).append(
                Component.literal("[Copy Public Link]").withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE).withStyle(style ->
                        style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, finalUrl)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to copy full link with your public IP")))));

        source.sendSuccess(() -> msg, false);
        return 1;
    }

    public static int executeStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CabinBackgroundService svc = CabinBackgroundService.getInstance();
        CabinBackgroundService.Snapshot snap = svc.getSnapshot();
        CabinBackgroundService.Status status = svc.getStatus();

        MutableComponent msg = Component.literal("📊 Web System Status: ").withStyle(ChatFormatting.GOLD);
        msg.append(Component.literal(status.name()).withStyle(statusColor(status)));

        msg.append(Component.literal("\n  Visitors: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(CabinNettyHandler.getVisitorCount())).withStyle(ChatFormatting.AQUA));

        if (snap != null) {
            long ageMs = System.currentTimeMillis() - snap.generatedAtMs();
            msg.append(Component.literal("\n  File Age: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(humanDuration(ageMs) + " ago").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("\n  Data: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(snap.itemCount() + " items, " + snap.mobCount() + " mobs, "
                            + snap.recipeCount() + " recipes").withStyle(ChatFormatting.WHITE));
        } else {
            msg.append(Component.literal("\n  (no binary data yet — generation may be in progress)")
                    .withStyle(ChatFormatting.GRAY));
        }

        Throwable err = svc.getLastError();
        if (err != null) msg.append(Component.literal("\n  Last error: ").withStyle(ChatFormatting.RED))
                .append(Component.literal(String.valueOf(err.getMessage())).withStyle(ChatFormatting.DARK_RED));

        source.sendSuccess(() -> msg, false);
        return 1;
    }

    public static int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) {
            source.sendFailure(Component.literal("Engine not ready: " + engine.getCurrentState()));
            return 0;
        }
        String modVersion = ModList.get().getModContainerById(ComplexityAnalyzer.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        source.sendSuccess(() -> Component.literal("🔄 Reloading web data in background...")
                .withStyle(ChatFormatting.AQUA), true);
        CabinBackgroundService.getInstance().regenerateAsync(server, engine, modVersion).whenComplete((snap, err) -> {
            MutableComponent done;
            if (err != null) {
                done = Component.literal("❌ Web reload failed: " + err.getMessage()).withStyle(ChatFormatting.RED);
            } else if (snap != null) {
                done = Component.literal("✅ Web data reloaded: " + humanBytes(snap.bytes().length)).withStyle(ChatFormatting.GREEN);
            } else {
                done = Component.literal("⚠ Web reload returned no data").withStyle(ChatFormatting.YELLOW);
            }
            server.execute(() -> source.sendSuccess(() -> done, true));
        });
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
        if (n < 1024) return n + " B";
        double k = n / 1024.0;
        if (k < 1024) return String.format(Locale.ROOT, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.ROOT, "%.1f MB", m);
        return String.format(Locale.ROOT, "%.2f GB", m / 1024.0);
    }

    private static String humanDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + "m " + (sec % 60) + "s";
        long hour = min / 60;
        if (hour < 24) return hour + "h " + (min % 60) + "m";
        return (hour / 24) + "d " + (hour % 24) + "h";
    }
}