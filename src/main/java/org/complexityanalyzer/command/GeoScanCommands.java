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

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.scan.ScanSession;
import org.complexityanalyzer.util.FormatUtils;
import org.jetbrains.annotations.Nullable;

import static java.util.Locale.ROOT;
import static net.minecraft.ChatFormatting.*;
import static net.minecraft.world.level.Level.*;

public final class GeoScanCommands {
    private static final int DEFAULT_CHUNKS = 32;
    private static final int MAX_BIOMES_IN_TOOLTIP = 25;

    private GeoScanCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("geoscan")
                .then(buildStartCommand())
                .then(buildStopCommand())
                .then(buildStatusCommand())
                .then(buildClearCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildStartCommand() {
        var forceArg = Commands.argument("force", BoolArgumentType.bool())
                .executes(ctx -> executeScan(ctx,
                        IntegerArgumentType.getInteger(ctx, "chunks"),
                        StringArgumentType.getString(ctx, "profile"),
                        BoolArgumentType.getBool(ctx, "force")));

        var chunksArg = Commands.argument("chunks", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
                .executes(ctx -> executeScan(ctx,
                        IntegerArgumentType.getInteger(ctx, "chunks"),
                        StringArgumentType.getString(ctx, "profile"),
                        false))
                .then(forceArg);

        var profileArg = Commands.argument("profile", StringArgumentType.word())
                .suggests(SharedSuggestions.GEOSCAN_PROFILE)
                .executes(ctx -> executeScan(ctx,
                        DEFAULT_CHUNKS,
                        StringArgumentType.getString(ctx, "profile"),
                        false))
                .then(chunksArg);

        return Commands.literal("start")
                .requires(source -> source.hasPermission(2))
                .executes(GeoScanCommands::showProfileHelp)
                .then(profileArg);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildStopCommand() {
        return Commands.literal("stop")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> withManager(ctx, GeoScanCommands::executeStop));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildStatusCommand() {
        return Commands.literal("status")
                .executes(ctx -> withManager(ctx, GeoScanCommands::executeStatus));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildClearCommand() {
        return Commands.literal("clear")
                .requires(source -> source.hasPermission(2))
                .executes(GeoScanCommands::executeClear);
    }

    private static int showProfileHelp(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());

        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.geoscan.profile_header", GOLD);
        output.sendEmptyLine(source);

        for (var profile : ScanProfile.values()) {
            var msptInfo = profile.hasMsptLimit()
                    ? Component.translatable("complexityanalyzer.command.geoscan.profile.limit_mspt", (int) profile.msptLimit)
                    : "complexityanalyzer.command.geoscan.profile.no_limit";

            output.sendClickableTip(source,
                    profile.icon + " ",
                    profile.displayName,
                    msptInfo,
                    ComplexityCommand.ROOT + " geoscan start " + profile.commandName,
                    Component.translatable("complexityanalyzer.command.geoscan.profile.hover", profile.displayName));
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        output.sendTip(source, "complexityanalyzer.command.geoscan.usage", ComplexityCommand.ROOT);
        output.sendTip(source, "complexityanalyzer.command.geoscan.example", ComplexityCommand.ROOT);
        output.sendEmptyLine(source);

        return 1;
    }

    private static int executeScan(CommandContext<CommandSourceStack> context, int chunks, String profileName, boolean force) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());

        final ScanProfile profile;
        try {
            profile = ScanProfile.fromInput(profileName);
        } catch (IllegalArgumentException e) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.unknown_profile", profileName));
            output.sendTip(source, "complexityanalyzer.command.geoscan.profile_tip", ComplexityCommand.ROOT);
            return 0;
        }

        var manager = requireManager(source, output);
        if (manager == null) return 0;

        if (manager.isScanning() || manager.isCountdownActive()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.already_running"));
            output.sendTip(source, "complexityanalyzer.command.geoscan.stop_tip", ComplexityCommand.ROOT);
            return 0;
        }

        String initiatorName = source.getTextName();

        output.sendEmptyLine(source);
        output.sendHeader(source,
                force ? "⚡" : "📊",
                force ? "complexityanalyzer.command.geoscan.force_header" : "complexityanalyzer.command.geoscan.scheduled_header",
                force ? RED : AQUA);
        output.sendEmptyLine(source);
        output.sendEntry(source, profile.icon, "complexityanalyzer.command.geoscan.profile_label", profile.displayName, GRAY, profile.color);
        output.sendEntry(source, "🌍", "complexityanalyzer.command.geoscan.chunks_label", chunks, GRAY, AQUA);

        if (profile.hasMsptLimit()) {
            output.sendSubEntry(source, "complexityanalyzer.command.geoscan.auto_pause", (int) profile.msptLimit, GRAY, GREEN);
        } else {
            output.sendSubEntry(source, "complexityanalyzer.command.geoscan.no_mspt_limit", "", GRAY, RED);
        }

        output.sendSubEntry(source, "complexityanalyzer.command.geoscan.vanilla_gen", "", GRAY, WHITE);
        output.sendEntry(source, "👤", "complexityanalyzer.command.geoscan.initiator_label", initiatorName, GRAY, WHITE);
        output.sendFooter(source);

        boolean heavyProfile = profile == ScanProfile.UNLIMITED || profile == ScanProfile.AGGRESSIVE;
        if (force) {
            if (heavyProfile) {
                output.broadcastSever(Component.translatable("complexityanalyzer.command.geoscan.force_broadcast"));
                output.broadcastSever(Component.translatable("complexityanalyzer.command.geoscan.lag_expected", profile.displayName));
            } else {
                output.broadcastWarning(Component.translatable("complexityanalyzer.command.geoscan.started_broadcast", profile.displayName.toLowerCase(ROOT)));
            }
            manager.startScanImmediately(chunks, initiatorName, profile);
        } else {
            if (heavyProfile) {
                output.broadcastWarning(Component.translatable("complexityanalyzer.command.geoscan.scheduled_broadcast", profile.displayName));
            } else {
                output.broadcast(Component.translatable("complexityanalyzer.command.geoscan.starting_soon", profile.displayName.toLowerCase(ROOT)));
            }
            manager.scheduleScan(chunks, initiatorName, profile);
        }

        output.sendToAdmins(Component.translatable("complexityanalyzer.command.geoscan.admin_log",
                force ? "Force started" : "Scheduled", initiatorName, profile.displayName, chunks));
        return 1;
    }

    private static int executeStop(CommandSourceStack source, OutputManager output, GeoAnalysisManager manager) {
        if (!manager.isScanning() && !manager.isCountdownActive()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.no_scan_running"));
            return 0;
        }

        output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.stopping"));
        manager.stopScan(source);
        output.broadcast(Component.translatable("complexityanalyzer.command.geoscan.stopped_broadcast").withStyle(GREEN));
        output.sendToAdmins(Component.translatable("complexityanalyzer.command.geoscan.stopped_admin", source.getTextName()));
        return 1;
    }

    @Deprecated // TODO: replace key check with manager.isComplete()
    private static int executeStatus(CommandSourceStack source, OutputManager output, GeoAnalysisManager manager) {
        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.geoscan.status_header", GOLD);
        output.sendEmptyLine(source);

        var session = manager.getCurrentSession();
        if (session != null && session.isValid()) {
            renderActiveStatus(output, source, manager, session);
        } else if (manager.isCountdownActive()) {
            output.sendStatusLine(source, "⏳", manager.getStatus(), YELLOW);
        } else {
            var statusComponent = manager.getStatus();
            boolean isComplete = statusComponent.getContents() instanceof TranslatableContents tc && "complexityanalyzer.geoscan.status.complete".equals(tc.getKey());
            output.sendStatusLine(source, isComplete ? "✓" : "💤", statusComponent, isComplete ? GREEN : GRAY);
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);
        return 1;
    }

    private static void renderActiveStatus(OutputManager output, CommandSourceStack source,
                                           GeoAnalysisManager manager, ScanSession session) {
        var profile = session.getProfile();
        output.sendSubEntry(source, profile.icon, "complexityanalyzer.command.geoscan.profile_label", profile.displayName, GRAY, profile.color);
        output.sendSubEntry(source, "complexityanalyzer.command.geoscan.background_gen", "", GRAY, AQUA);

        long scanned = session.getTotalChunksScanned();
        int total = session.getTotalChunksNeeded();
        int percent = session.getProgressPercent();

        output.sendProgressBar(source, "complexityanalyzer.command.geoscan.progress_label", percent, percent + "%", GRAY, percent >= 100 ? GREEN : AQUA);
        output.sendSubEntry(source, "complexityanalyzer.command.geoscan.chunks_label_short", scanned + " / " + total, GRAY, WHITE);
        output.sendSubEntry(source, "complexityanalyzer.command.geoscan.speed_label",
                Component.translatable("complexityanalyzer.command.geoscan.speed_value", session.getScanSpeed()), GRAY, AQUA);

        output.sendSubEntry(source, "⌛", "complexityanalyzer.command.geoscan.elapsed_label",
                FormatUtils.formatDurationSeconds(session.getElapsedSeconds()), GRAY, WHITE);

        if (profile.hasMsptLimit()) {
            var mspt = manager.getCurrentMspt();
            var msptText = "%.1f".formatted(mspt);
            var msptLine = Component.literal("  MSPT: ").withStyle(GRAY);

            if (manager.isThrottled()) {
                msptLine.append(Component.literal(msptText).withStyle(RED))
                        .append(Component.translatable("complexityanalyzer.command.geoscan.throttled").withStyle(RED, BOLD));
            } else {
                var msptColor = mspt < profile.msptLimit * 0.7f ? GREEN : mspt < profile.msptLimit ? YELLOW : RED;
                msptLine.append(Component.literal(msptText).withStyle(msptColor))
                        .append(Component.literal("/" + (int) profile.msptLimit).withStyle(DARK_GRAY));
            }

            output.sendInfo(source, msptLine);
        }

        var tooltip = buildBiomeTooltip(session);
        if (tooltip != null) {
            output.sendEmptyLine(source);

            var hoverText = Component.translatable("complexityanalyzer.command.geoscan.hover_details").withStyle(style -> style
                    .withColor(DARK_AQUA)
                    .withItalic(true)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip)));

            var biomeStatus = Component.translatable("complexityanalyzer.command.geoscan.biome_details")
                    .withStyle(AQUA)
                    .append(" ").append(hoverText);

            output.sendInfo(source, biomeStatus);
        }
    }

    private static @Nullable MutableComponent buildBiomeTooltip(ScanSession session) {
        var progress = session.getBiomeProgress();

        int totalIncomplete = 0;
        for (var dimMap : progress.values()) {
            for (var stats : dimMap.values()) if (stats[0] < stats[1]) totalIncomplete++;
        }

        if (totalIncomplete == 0) return null;
        var tooltip = Component.empty().append(Component.translatable("complexityanalyzer.command.geoscan.biome_progress_header").withStyle(GOLD, BOLD));
        int biomesShown = 0;

        for (var dimEntry : progress.object2ObjectEntrySet()) {
            var dimId = dimEntry.getKey();
            var biomeMap = dimEntry.getValue();
            boolean dimHeaderAdded = false;

            for (var biomeEntry : biomeMap.object2ObjectEntrySet()) {
                int[] stats = biomeEntry.getValue();
                int scanned = stats[0], needed = stats[1];

                if (scanned >= needed) continue;
                if (biomesShown >= MAX_BIOMES_IN_TOOLTIP) {
                    int remaining = totalIncomplete - biomesShown;
                    tooltip.append(Component.translatable("complexityanalyzer.command.geoscan.more_biomes", remaining).withStyle(DARK_GRAY, ITALIC));
                    return tooltip;
                }

                if (!dimHeaderAdded) {
                    tooltip.append("\n").append(Component.literal("▸ ").append(formatDimensionName(dimId)).append("\n").withStyle(YELLOW, BOLD));
                    dimHeaderAdded = true;
                }

                var biomeId = biomeEntry.getKey();
                int biomePercent = needed > 0 ? (scanned * 100 / needed) : 0;
                var biomeColor = biomePercent >= 75 ? YELLOW : biomePercent >= 50 ? GOLD : WHITE;

                tooltip.append(Component.literal("  ○ ").withStyle(GRAY))
                        .append(Component.literal(biomeId.getPath()).withStyle(biomeColor))
                        .append(Component.literal(" (%d/%d)\n".formatted(scanned, needed)).withStyle(GRAY));

                biomesShown++;
            }
        }

        return tooltip;
    }

    private static int executeClear(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        var manager = engine.getGeoManager();

        if (manager == null) {
            engine.clearGeoDatabase();
            output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.cleared_broadcast"));
            return 1;
        }

        if (manager.isScanning() || manager.isCountdownActive()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.cannot_clear"));
            output.sendTip(source, "complexityanalyzer.command.geoscan.clear_tip", ComplexityCommand.ROOT);
            return 0;
        }

        output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.clearing"));
        engine.clearGeoDatabase();
        output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.cleared_broadcast"));
        output.sendToAdmins(Component.translatable("complexityanalyzer.command.geoscan.cleared_admin", source.getTextName()));
        return 1;
    }

    private static int withManager(CommandContext<CommandSourceStack> context, GeoAction action) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var manager = requireManager(source, output);
        return manager == null ? 0 : action.run(source, output, manager);
    }

    private static @Nullable GeoAnalysisManager requireManager(CommandSourceStack source, OutputManager output) {
        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.not_initialized"));
        }
        return manager;
    }

    private static Component formatDimensionName(ResourceLocation dimId) {
        if (dimId.equals(OVERWORLD.location())) {
            return Component.literal("🌍 ").append(Component.translatable("complexityanalyzer.dimension.overworld"));
        }
        if (dimId.equals(NETHER.location())) {
            return Component.literal("🔥 ").append(Component.translatable("complexityanalyzer.dimension.nether"));
        }
        if (dimId.equals(END.location())) {
            return Component.literal("🌌 ").append(Component.translatable("complexityanalyzer.dimension.the_end"));
        }
        return Component.literal(dimId.toString());
    }

    @FunctionalInterface
    private interface GeoAction {
        int run(CommandSourceStack source, OutputManager output, GeoAnalysisManager manager);
    }
}