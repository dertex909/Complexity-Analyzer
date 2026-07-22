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
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.complexityanalyzer.cache.util.ManagedCache;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

public final class SystemCommand {
    private static final SuggestionProvider<CommandSourceStack> CACHE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(ManagedCache.ids(), builder);

    private SystemCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("system")
                .then(Commands.literal("status").executes(SystemCommand::executeStatus))
                .then(Commands.literal("tps").executes(SystemCommand::executeTps))
                .then(Commands.literal("stats").executes(SystemCommand::executeStats))
                .then(Commands.literal("threads").requires(source -> source.hasPermission(2)).executes(SystemCommand::executeThreads))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(SystemCommand::executeReload))
                .then(Commands.literal("cache")
                        .then(Commands.literal("info").executes(SystemCommand::executeCacheInfo))
                        .then(Commands.literal("clear").requires(source -> source.hasPermission(2)).executes(SystemCommand::executeCacheClearAll)
                                .then(Commands.argument("cache", StringArgumentType.word()).suggests(CACHE_SUGGESTIONS).executes(SystemCommand::executeCacheClearOne))));
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

        output.sendEmptyLine(source);
        output.sendHeader(source, "⚙", "complexityanalyzer.command.system.status_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        String stateIcon;
        var stateColor = switch (state) {
            case READY -> {
                stateIcon = "✓";
                yield ChatFormatting.GREEN;
            }
            case ANALYZING -> {
                stateIcon = "⏳";
                yield ChatFormatting.YELLOW;
            }
            case FAILED -> {
                stateIcon = "✗";
                yield ChatFormatting.RED;
            }
            case IDLE -> {
                stateIcon = "◆";
                yield ChatFormatting.GRAY;
            }
        };

        output.sendEntry(source, stateIcon, "complexityanalyzer.command.system.engine_state", state.toString(), ChatFormatting.GRAY, stateColor);

        if (engine.isReady()) {
            var stats = engine.getStats();
            output.sendEmptyLine(source);
            output.sendStatusLine(source, "📊", "complexityanalyzer.command.system.data_overview", ChatFormatting.AQUA);
            output.sendSubEntry(source, "complexityanalyzer.command.system.items_with_recipes", String.valueOf(stats.itemCount()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
            output.sendSubEntry(source, "complexityanalyzer.command.system.total_recipes", String.valueOf(stats.recipeCount()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
            output.sendSubEntry(source, "complexityanalyzer.command.system.base_resources", String.valueOf(stats.baseResourceCount()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
        } else {
            output.sendEmptyLine(source);
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

        String adminName = source.getTextName();
        output.broadcastWarning(Component.translatable("complexityanalyzer.command.system.reloading_broadcast"));
        output.sendToAdmins(Component.translatable("complexityanalyzer.command.system.reload_initiated_admin", adminName));
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.system.reloading_header", ChatFormatting.YELLOW);
        output.sendTip(source, "complexityanalyzer.command.system.reload_warning");
        output.sendTip(source, "complexityanalyzer.command.system.running_background");
        engine.reloadAsync(source.getLevel());
        return 1;
    }

    private static int executeTps(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var server = source.getServer();

        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = 1000.0 / Math.max(50.0, mspt);
        double finalTps = Math.min(20.0, tps);

        var tpsColor = tps >= 19.0 ? ChatFormatting.GREEN : (tps >= 16.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        var msptColor = mspt <= 40.0 ? ChatFormatting.GREEN : (mspt <= 50.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        var runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long allocatedMemory = runtime.totalMemory() / 1024 / 1024;
        long usedMemory = allocatedMemory - (runtime.freeMemory() / 1024 / 1024);
        double memoryPercent = (double) usedMemory / maxMemory * 100;

        var memoryColor = memoryPercent < 60 ? ChatFormatting.GREEN : (memoryPercent < 80 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        output.sendEmptyLine(source);
        output.sendHeader(source, "📈", "complexityanalyzer.command.system.perf_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        String tpsIcon = finalTps >= 19.0 ? "✓" : finalTps >= 16.0 ? "⚠" : "✗";
        output.sendSubEntry(source, "complexityanalyzer.command.system.tps_label", String.format("%.2f %s", finalTps, tpsIcon), ChatFormatting.GRAY, tpsColor);
        output.sendSubEntry(source, "complexityanalyzer.command.system.mspt_label", String.format("%.2f %s", mspt, Component.translatable("complexityanalyzer.unit.time.milliseconds_short").getString()), ChatFormatting.GRAY, msptColor);
        output.sendValueBar(source, (int) Math.min(100, mspt * 2), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        String mbSuffix = Component.translatable("complexityanalyzer.unit.size.megabytes").getString();
        output.sendStatusLine(source, "💾", "complexityanalyzer.command.system.memory_usage", ChatFormatting.AQUA);
        output.sendSubEntry(source, "complexityanalyzer.command.system.used_label", String.format("%d %s / %d %s", usedMemory, mbSuffix, maxMemory, mbSuffix), ChatFormatting.GRAY, memoryColor);
        output.sendValueBar(source, (int) memoryPercent, ChatFormatting.DARK_GRAY, String.format("%.1f%%", memoryPercent), memoryColor);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static int executeStats(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.engine_not_ready"));
            return 0;
        }

        var stats = engine.getStats();
        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.system.detailed_stats_header", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "complexityanalyzer.command.system.items_label", String.valueOf(stats.itemCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendSubEntry(source, "complexityanalyzer.command.system.recipes_label", String.valueOf(stats.recipeCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        output.sendSubEntry(source, "complexityanalyzer.command.system.cached_items", String.valueOf(stats.baseResourceCount()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static int executeCacheInfo(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        boolean enabled = ComplexityConfig.ENABLE_CACHE.get();

        var enabledColor = enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        var enabledText = Component.translatable(enabled
                ? "complexityanalyzer.command.system.cache.enabled"
                : "complexityanalyzer.command.system.cache.disabled").withStyle(enabledColor);

        output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.header"));
        output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.config").append(enabledText));

        int present = 0;
        for (var cache : ManagedCache.all()) {
            present += reportCacheFile(source, output, cache.id(), cache.file(source.getServer()));
        }
        return present;
    }

    private static int reportCacheFile(CommandSourceStack source, OutputManager output, String label, Path file) {
        if (file == null) {
            output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.no_world", label));
            return 0;
        }
        if (!Files.isRegularFile(file)) {
            output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.not_built", label));
            return 0;
        }
        try {
            long size = Files.size(file);
            var modified = Files.getLastModifiedTime(file).toInstant();
            String age = formatAge(Duration.between(modified, Instant.now()));
            output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.present", label, humanSize(size), age, file.toString()));
            return 1;
        } catch (Throwable t) {
            output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.unreadable", label, t.getMessage()));
            return 0;
        }
    }

    private static int executeCacheClearAll(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var server = source.getServer();

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
            output.sendClickableSuccess(source,
                    Component.translatable("complexityanalyzer.command.system.cache.deleted.prefix", removed),
                    Component.literal(ComplexityCommand.ROOT + " system reload"),
                    Component.translatable("complexityanalyzer.command.system.cache.deleted.suffix"),
                    ComplexityCommand.ROOT + " system reload",
                    Component.translatable("complexityanalyzer.command.system.cache.deleted.hover")
            );
        } else {
            output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.nothing_to_delete"));
        }
        return removed;
    }

    private static int executeCacheClearOne(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var server = source.getServer();

        String id = StringArgumentType.getString(context, "cache");
        var cache = ManagedCache.get(id);
        if (cache == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.cache.unknown",
                    id, String.join(", ", ManagedCache.ids())));
            return 0;
        }
        if (cache.file(server) == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.system.cache.no_world_loaded"));
            return 0;
        }

        if (cache.delete(server)) {
            output.sendClickableSuccess(source,
                    Component.translatable("complexityanalyzer.command.system.cache.deleted.prefix", 1),
                    Component.literal(ComplexityCommand.ROOT + " system reload"),
                    Component.translatable("complexityanalyzer.command.system.cache.deleted.suffix"),
                    ComplexityCommand.ROOT + " system reload",
                    Component.translatable("complexityanalyzer.command.system.cache.deleted.hover")
            );
            return 1;
        }
        output.sendInfo(source, Component.translatable("complexityanalyzer.command.system.cache.nothing_to_delete"));
        return 0;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String formatAge(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        if (s < 86400) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        return (s / 86400) + "d " + ((s % 86400) / 3600) + "h";
    }

    private static int executeThreads(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var stats = ThreadPoolManager.getInstance().getStats();
        output.sendEmptyLine(source);
        output.sendHeader(source, "⚙", "complexityanalyzer.command.system.thread_pool_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🔹", "complexityanalyzer.command.system.parallelism_target", String.valueOf(stats.parallelism()), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🔹", "complexityanalyzer.command.system.compute_pool_active", String.valueOf(stats.activeThreads()), ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendEntry(source, "🔹", "complexityanalyzer.command.system.queued_tasks", String.valueOf(stats.queuedTasks()), ChatFormatting.GRAY, stats.queuedTasks() > 100 ? ChatFormatting.RED : ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }
}