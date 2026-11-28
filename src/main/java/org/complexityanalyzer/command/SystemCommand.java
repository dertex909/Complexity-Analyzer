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

package org.complexityanalyzer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.complexityanalyzer.cache.util.ManagedCache;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.util.FileManager;
import org.complexityanalyzer.util.FormatUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static net.minecraft.ChatFormatting.*;

public final class SystemCommand {
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private SystemCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var cacheClear = Commands.literal("clear")
                .requires(source -> source.hasPermission(2))
                .executes(SystemCommand::executeCacheClearAll)
                .then(Commands.argument("cache", StringArgumentType.word()).suggests(SharedSuggestions.CACHE_ID)
                        .executes(SystemCommand::executeCacheClearOne));

        return Commands.literal("system")
                .then(Commands.literal("status").executes(SystemCommand::executeStatus))
                .then(Commands.literal("tps").executes(SystemCommand::executeTps))
                .then(Commands.literal("stats").executes(SystemCommand::executeStats))
                .then(Commands.literal("threads").requires(source -> source.hasPermission(2)).executes(SystemCommand::executeThreads))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(SystemCommand::executeReload))
                .then(Commands.literal("cache")
                        .then(Commands.literal("info").executes(SystemCommand::executeCacheInfo))
                        .then(cacheClear));
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (engine == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.engine_not_initialized"));
            return 0;
        }

        var state = engine.getCurrentState();
        var display = switch (state) {
            case READY -> new StateDisplay("✓", GREEN);
            case ANALYZING -> new StateDisplay("⏳", YELLOW);
            case FAILED -> new StateDisplay("✗", RED);
            case IDLE -> new StateDisplay("◆", GRAY);
        };

        output.sendEmptyLine(source);
        output.sendHeader(source, "⚙", "complexityanalyzer.command.system.status_header", GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, display.icon(), "complexityanalyzer.command.system.engine_state", state.toString(), GRAY, display.color());
        output.sendEmptyLine(source);

        if (engine.isReady()) {
            var stats = engine.getStats();
            output.sendStatusLine(source, "📊", "complexityanalyzer.command.system.data_overview", AQUA);
            output.sendSubEntry(source, "complexityanalyzer.command.system.items_with_recipes", stats.itemCount(), DARK_GRAY, WHITE);
            output.sendSubEntry(source, "complexityanalyzer.command.system.total_recipes", stats.recipeCount(), DARK_GRAY, WHITE);
            output.sendSubEntry(source, "complexityanalyzer.command.system.base_resources", stats.baseResourceCount(), DARK_GRAY, WHITE);
        } else {
            output.sendTip(source, "complexityanalyzer.command.system.stats_unavailable");
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (engine == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.engine_not_initialized"));
            return 0;
        }

        output.broadcastWarning(Component.translatable("complexityanalyzer.command.system.reloading_broadcast"));
        output.sendToAdmins(Component.translatable("complexityanalyzer.command.system.reload_initiated_admin", source.getTextName()));
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.system.reloading_header", YELLOW);
        output.sendTip(source, "complexityanalyzer.command.system.reload_warning");
        output.sendTip(source, "complexityanalyzer.command.system.running_background");
        engine.reloadAsync(source.getLevel());
        return 1;
    }

    private static int executeTps(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var server = source.getServer();
        var output = new OutputManager(server);

        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = Math.min(20.0, 1000.0 / Math.max(50.0, mspt));

        var tpsColor = tps >= 19.0 ? GREEN : tps >= 16.0 ? YELLOW : RED;
        var tpsIcon = tps >= 19.0 ? "✓" : tps >= 16.0 ? "⚠" : "✗";
        var msptColor = mspt <= 40.0 ? GREEN : mspt <= 50.0 ? YELLOW : RED;

        var runtime = Runtime.getRuntime();
        long maxMemory = Math.max(1L, runtime.maxMemory() / BYTES_PER_MB);
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB;
        double memoryPercent = Math.clamp((double) usedMemory / maxMemory * 100.0, 0.0, 100.0);
        var memoryColor = memoryPercent < 60.0 ? GREEN : memoryPercent < 80.0 ? YELLOW : RED;

        output.sendEmptyLine(source);
        output.sendHeader(source, "📈", "complexityanalyzer.command.system.perf_header", GOLD);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "complexityanalyzer.command.system.tps_label", "%.2f %s".formatted(tps, tpsIcon), GRAY, tpsColor);

        var msptValue = Component.literal("%.2f ".formatted(mspt)).append(Component.translatable("complexityanalyzer.unit.time.milliseconds_short"));
        output.sendSubEntry(source, "complexityanalyzer.command.system.mspt_label", msptValue, GRAY, msptColor);
        output.sendValueBar(source, (int) Math.clamp(mspt * 2.0, 0.0, 100.0), DARK_GRAY, "", WHITE);
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "💾", "complexityanalyzer.command.system.memory_usage", AQUA);

        var memoryValue = Component.literal("%d ".formatted(usedMemory))
                .append(Component.translatable("complexityanalyzer.unit.size.megabytes"))
                .append(" / %d ".formatted(maxMemory))
                .append(Component.translatable("complexityanalyzer.unit.size.megabytes"));
        output.sendSubEntry(source, "complexityanalyzer.command.system.used_label", memoryValue, GRAY, memoryColor);
        output.sendValueBar(source, (int) memoryPercent, DARK_GRAY, "%.1f%%".formatted(memoryPercent), memoryColor);

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static int executeStats(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (engine == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.engine_not_initialized"));
            return 0;
        }

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.engine_not_ready"));
            return 0;
        }

        var stats = engine.getStats();
        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.system.detailed_stats_header", GREEN);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "complexityanalyzer.command.system.items_label", stats.itemCount(), GRAY, WHITE);
        output.sendSubEntry(source, "complexityanalyzer.command.system.recipes_label", stats.recipeCount(), GRAY, WHITE);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "complexityanalyzer.command.system.cached_items", stats.baseResourceCount(), GRAY, WHITE);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static int executeCacheInfo(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var server = source.getServer();
        var output = new OutputManager(server);
        boolean enabled = ComplexityConfig.ENABLE_CACHE.get();

        var enabledKey = enabled ? "complexityanalyzer.command.system.cache.enabled" : "complexityanalyzer.command.system.cache.disabled";

        output.sendEmptyLine(source);
        output.sendHeader(source, "📦", "complexityanalyzer.command.system.cache.header", GOLD);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "complexityanalyzer.command.system.cache.config", enabledKey, GRAY, enabled ? GREEN : YELLOW);
        output.sendEmptyLine(source);

        int present = 0;
        for (var cache : ManagedCache.all()) present += reportCacheFile(source, output, cache.id(), cache.file(server));

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return present;
    }

    private static int reportCacheFile(CommandSourceStack source, OutputManager output, String label, Path file) {
        if (file == null) {
            output.sendSubEntry(source, label, "complexityanalyzer.command.system.cache.status.no_world", GRAY, RED);
            return 0;
        }

        if (!FileManager.isRegularFile(file)) {
            output.sendSubEntry(source, label, "complexityanalyzer.command.system.cache.status.not_built", GRAY, YELLOW);
            return 0;
        }

        try {
            var modified = FileManager.getLastModifiedTime(file).toInstant();
            long ageSeconds = Math.max(0L, Duration.between(modified, Instant.now()).getSeconds());
            var age = FormatUtils.formatDurationSeconds(ageSeconds);
            long size = FileManager.getSize(file);
            var status = Component.translatable("complexityanalyzer.command.system.cache.status.present").withStyle(GREEN);
            var details = Component.translatable("complexityanalyzer.command.system.cache.status.details", FormatUtils.humanBytes(size), age).withStyle(style -> style.withColor(GRAY).withBold(false));
            var path = Component.literal(file.toString()).withStyle(style -> style.withColor(DARK_GRAY).withBold(false));
            var fullValue = Component.empty().append(status).append(" ").append(details).append(" ").append(path);

            output.sendSubEntry(source, label, fullValue, GRAY, GREEN);
            return 1;
        } catch (Exception e) {
            var reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            output.sendSubEntry(source, label, Component.translatable("complexityanalyzer.command.system.cache.status.unreadable", reason), GRAY, RED);
            return 0;
        }
    }

    private static int executeCacheClearAll(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var server = source.getServer();
        var output = new OutputManager(server);

        boolean anyWorld = false;
        int removed = 0;

        for (var cache : ManagedCache.all()) {
            if (cache.file(server) == null) continue;
            anyWorld = true;
            if (cache.delete(server)) removed++;
        }

        if (!anyWorld) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.cache.no_world_loaded"));
            return 0;
        }

        if (removed > 0) {
            sendCacheDeleted(source, output, removed);
        } else {
            output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.nothing_to_delete").withStyle(YELLOW));
        }

        return removed;
    }

    private static int executeCacheClearOne(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var server = source.getServer();
        var output = new OutputManager(server);

        String id = StringArgumentType.getString(context, "cache");
        var cache = ManagedCache.get(id);
        if (cache == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.cache.unknown", id, String.join(", ", ManagedCache.ids())));
            return 0;
        }

        if (cache.file(server) == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.cache.no_world_loaded"));
            return 0;
        }

        if (cache.delete(server)) {
            sendCacheDeleted(source, output, 1);
            return 1;
        }

        output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.nothing_to_delete").withStyle(YELLOW));
        return 0;
    }

    private static void sendCacheDeleted(CommandSourceStack source, OutputManager output, int count) {
        String reloadCmd = ComplexityCommand.ROOT + " system reload";
        output.sendClickableSuccess(source,
                Component.translatable("complexityanalyzer.command.system.cache.deleted.prefix", count),
                reloadCmd, "complexityanalyzer.command.system.cache.deleted.suffix",
                reloadCmd, "complexityanalyzer.command.system.cache.deleted.hover");
    }

    private static int executeThreads(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var stats = ThreadPoolManager.getInstance().getStats();

        output.sendEmptyLine(source);
        output.sendHeader(source, "⚙", "complexityanalyzer.command.system.thread_pool_header", GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🔹", "complexityanalyzer.command.system.parallelism_target", stats.parallelism(), GRAY, WHITE);
        output.sendEntry(source, "🔹", "complexityanalyzer.command.system.compute_pool_active", stats.activeThreads(), GRAY, YELLOW);
        output.sendEntry(source, "🔹", "complexityanalyzer.command.system.queued_tasks", stats.queuedTasks(), GRAY, stats.queuedTasks() > 100 ? RED : GREEN);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private record StateDisplay(String icon, ChatFormatting color) {
    }
}