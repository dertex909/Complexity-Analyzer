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
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.event.AnalysisBootstrap;

public final class SystemCommand {
    private SystemCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("system")
                .then(Commands.literal("status").executes(SystemCommand::executeStatus))
                .then(Commands.literal("tps").executes(SystemCommand::executeTps))
                .then(Commands.literal("stats").executes(SystemCommand::executeStats))
                .then(Commands.literal("threads").requires(source -> source.hasPermission(2)).executes(SystemCommand::executeThreads))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(SystemCommand::executeReload));
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisBootstrap.getEngine();

        if (engine == null) {
            output.sendFailure(source, Component.literal("❌ Analysis Engine is not initialized!"));
            return 0;
        }

        AnalysisEngine.State state = engine.getCurrentState();

        output.sendEmptyLine(source);
        output.sendHeader(source, "⚙", "Complexity Analyzer Status", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        String stateIcon;
        ChatFormatting stateColor = switch (state.toString()) {
            case "READY" -> {
                stateIcon = "✓";
                yield ChatFormatting.GREEN;
            }
            case "LOADING", "INITIALIZING" -> {
                stateIcon = "⏳";
                yield ChatFormatting.YELLOW;
            }
            case "ERROR", "FAILED" -> {
                stateIcon = "✗";
                yield ChatFormatting.RED;
            }
            default -> {
                stateIcon = "◆";
                yield ChatFormatting.GRAY;
            }
        };

        output.sendEntry(source, stateIcon, "Engine State", state.toString(), ChatFormatting.GRAY, stateColor);

        if (engine.isReady()) {
            var stats = engine.getStats();
            output.sendEmptyLine(source);
            output.sendStatusLine(source, "📊", "Data Overview", ChatFormatting.AQUA);
            output.sendSubEntry(source, "📦", "Items with recipes", String.valueOf(stats.itemCount()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
            output.sendSubEntry(source, "📜", "Total recipes", String.valueOf(stats.recipeCount()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
            output.sendSubEntry(source, "💎", "Base resources", String.valueOf(stats.baseResourceCount()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
        } else {
            output.sendEmptyLine(source);
            output.sendTip(source, "Engine not ready. Statistics unavailable.");
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);

        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (engine == null) {
            output.sendFailure(source, Component.literal("❌ Engine not initialized!"));
            return 0;
        }

        String adminName = source.getTextName();
        output.broadcastWarning(Component.literal("⚠ Analysis system is reloading... Possible lag!"));
        output.sendToAdmins(Component.literal("System reload initiated by " + adminName));
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "🔄", "Reloading Analysis Systems", ChatFormatting.YELLOW);
        output.sendTip(source, "This may take a few seconds and cause lag.");
        output.sendTip(source, "Running in background...");
        engine.reloadAsync(source.getLevel());
        return 1;
    }

    private static int executeTps(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        MinecraftServer server = source.getServer();

        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = 1000.0 / Math.max(50.0, mspt);
        double finalTps = Math.min(20.0, tps);

        ChatFormatting tpsColor = tps >= 19.0 ? ChatFormatting.GREEN : (tps >= 16.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        ChatFormatting msptColor = mspt <= 40.0 ? ChatFormatting.GREEN : (mspt <= 50.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        Runtime runtime = Runtime.getRuntime();

        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long usedMemory = totalMemory - (runtime.freeMemory() / 1024 / 1024);
        double memoryPercent = (double) usedMemory / totalMemory * 100;

        ChatFormatting memoryColor = memoryPercent < 60 ? ChatFormatting.GREEN : (memoryPercent < 80 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        output.sendEmptyLine(source);
        output.sendHeader(source, "📈", "Server Performance", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        String tpsIcon = finalTps >= 19.0 ? "✓" : finalTps >= 16.0 ? "⚠" : "✗";
        output.sendSubEntry(source, "TPS", String.format("%.2f %s", finalTps, tpsIcon), ChatFormatting.GRAY, tpsColor);
        output.sendSubEntry(source, "MSPT", String.format("%.2f ms", mspt), ChatFormatting.GRAY, msptColor);
        output.sendValueBar(source, (int) Math.min(100, mspt * 2), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "💾", "Memory Usage", ChatFormatting.AQUA);
        output.sendSubEntry(source, "Used", String.format("%d MB / %d MB", usedMemory, totalMemory), ChatFormatting.GRAY, memoryColor);
        output.sendValueBar(source, (int) memoryPercent, ChatFormatting.DARK_GRAY, String.format("%.1f%%", memoryPercent), memoryColor);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static int executeStats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.literal("⚠ Engine is not ready!"));
            return 0;
        }

        var stats = engine.getStats();
        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "Detailed Statistics", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "📦", "Items", String.valueOf(stats.itemCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendSubEntry(source, "📜", "Recipes", String.valueOf(stats.recipeCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "💎", "Cached Items", String.valueOf(stats.baseResourceCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static int executeThreads(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        ThreadPoolManager.PoolStats stats = ThreadPoolManager.getInstance().getStats();
        output.sendEmptyLine(source);
        output.sendHeader(source, "⚙", "Thread Pool Statistics", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🔹", "Parallelism Target", String.valueOf(stats.parallelism()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🔹", "Compute Pool Active", String.valueOf(stats.activeThreads()), ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendEntry(source, "🔹", "Queued Tasks", String.valueOf(stats.queuedTasks()), ChatFormatting.GRAY, stats.queuedTasks() > 100 ? ChatFormatting.RED : ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }
}