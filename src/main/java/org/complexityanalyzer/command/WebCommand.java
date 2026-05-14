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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
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
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(WebCommand::executeReload));
    }

    public static int executeUrl(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        OutputManager output = new OutputManager(source.getServer());
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;

        String url = CabinNettyHandler.getUrl(player);

        if (url == null) {
            output.sendEmptyLine(source);
            output.sendFailure(source, Component.literal("Web Dashboard is not active."));
            output.sendTip(source, "To use it in singleplayer, you must click 'Open to LAN' in the Escape menu.");
            output.sendEmptyLine(source);
            return 0;
        }

        String remoteUrl = null;

        if ((url.contains("127.0.0.1") || url.contains("localhost")) && ipDetected) {
            remoteUrl = url.replace("127.0.0.1", publicIp).replace("localhost", publicIp);
        }

        output.sendEmptyLine(source);
        output.sendHeader(source, "🌐", "Web Dashboard", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        output.sendLink(source, "URL", "[Open in Browser]", url, ChatFormatting.AQUA, "Click to open: " + url);

        if (remoteUrl != null) {
            output.sendEmptyLine(source);
            output.sendCopyAction(source, "For Friends", "[Copy Public Link]", remoteUrl, ChatFormatting.YELLOW, "Click to copy link with your public IP for others to join");
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    public static int executeStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        OutputManager output = new OutputManager(source.getServer());
        CabinBackgroundService svc = CabinBackgroundService.getInstance();
        CabinBackgroundService.Snapshot snap = svc.getSnapshot();
        CabinBackgroundService.Status status = svc.getStatus();
        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "Web System Status", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        output.sendEntry(source, "⚙", "Status", status.name(), ChatFormatting.GRAY, statusColor(status));
        output.sendEntry(source, "👥", "Visitors", String.valueOf(CabinNettyHandler.getVisitorCount()), ChatFormatting.GRAY, ChatFormatting.AQUA);

        if (snap != null) {
            long ageMs = System.currentTimeMillis() - snap.generatedAtMs();
            output.sendEntry(source, "🕒", "File Age", humanDuration(ageMs) + " ago", ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "📂", "Items", String.valueOf(snap.itemCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "👾", "Mobs", String.valueOf(snap.mobCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "📜", "Recipes", String.valueOf(snap.recipeCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        } else {
            output.sendTip(source, "no binary data yet — generation may be in progress");
        }

        Throwable err = svc.getLastError();
        if (err != null) {
            output.sendEmptyLine(source);
            output.sendFailure(source, Component.literal("Last error: " + err.getMessage()));
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    public static int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        OutputManager output = new OutputManager(source.getServer());
        MinecraftServer server = source.getServer();
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) {
            output.sendFailure(source, Component.literal("Engine not ready: " + engine.getCurrentState()));
            return 0;
        }
        String modVersion = ModList.get().getModContainerById(ComplexityAnalyzer.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");

        output.sendSuccess(source, Component.literal("🔄 Reloading web data in background..."));

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