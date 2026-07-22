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
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.scan.ScanSession;
import org.complexityanalyzer.util.ServerLanguage;

import static java.util.Locale.ROOT;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;
import static net.minecraft.network.chat.Style.EMPTY;

public class GeoScanCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("geoscan")
                .then(Commands.literal("start")
                        .requires(source -> source.hasPermission(2))
                        .executes(GeoScanCommands::showProfileHelp)
                        .then(Commands.argument("profile", StringArgumentType.word())
                                .suggests((c, b) -> suggest(new String[]{"normal", "fast", "ultra_fast", "maximum"}, b))
                                .executes(ctx -> executeScan(ctx, 32, StringArgumentType.getString(ctx, "profile"), false))
                                .then(Commands.argument("chunks", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
                                        .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "chunks"), StringArgumentType.getString(ctx, "profile"), false))
                                        .then(Commands.argument("force", BoolArgumentType.bool())
                                                .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "chunks"), StringArgumentType.getString(ctx, "profile"), BoolArgumentType.getBool(ctx, "force")))))))
                .then(Commands.literal("stop")
                        .requires(source -> source.hasPermission(2))
                        .executes(GeoScanCommands::executeStop))
                .then(Commands.literal("status")
                        .executes(GeoScanCommands::executeStatus))
                .then(Commands.literal("clear")
                        .requires(source -> source.hasPermission(2))
                        .executes(GeoScanCommands::executeClear));
    }

    private static int showProfileHelp(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());

        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.geoscan.profile_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        for (var profile : ScanProfile.values()) {
            String icon = getProfileIcon(profile);
            var msptInfo = profile.hasMsptLimit() ?
                    Component.translatable("complexityanalyzer.command.geoscan.profile.limit_mspt", (int) profile.msptLimit) :
                    Component.translatable("complexityanalyzer.command.geoscan.profile.no_limit");

            output.sendClickableTip(source, icon + " ", profile.displayName, msptInfo.getString(),
                    ComplexityCommand.ROOT + " geoscan start " + profile.commandName,
                    Component.translatable("complexityanalyzer.command.geoscan.profile.hover", profile.displayName).getString());
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);

        output.sendTip(source, "complexityanalyzer.command.geoscan.usage", ComplexityCommand.ROOT);
        output.sendTip(source, "complexityanalyzer.command.geoscan.example", ComplexityCommand.ROOT);
        output.sendEmptyLine(source);

        return 1;
    }

    private static int executeScan(CommandContext<CommandSourceStack> context,
                                   int chunks, String profileName, boolean force) {
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

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            if (manager.isScanning() || manager.isCountdownActive()) {
                output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.already_running"));
                output.sendTip(source, "complexityanalyzer.command.geoscan.stop_tip", ComplexityCommand.ROOT);
                return 1;
            }

            String initiatorName = source.getTextName();
            String icon = getProfileIcon(profile);
            var color = getProfileColor(profile);

            output.sendEmptyLine(source);
            output.sendHeader(source, force ? "⚡" : "📊", force ? "complexityanalyzer.command.geoscan.force_header" : "complexityanalyzer.command.geoscan.scheduled_header", force ? ChatFormatting.RED : ChatFormatting.AQUA);
            output.sendEmptyLine(source);

            output.sendEntry(source, icon, "complexityanalyzer.command.geoscan.profile_label", profile.displayName, ChatFormatting.GRAY, color);
            output.sendEntry(source, "🌍", "complexityanalyzer.command.geoscan.chunks_label", String.valueOf(chunks), ChatFormatting.GRAY, ChatFormatting.AQUA);

            if (profile.hasMsptLimit()) {
                output.sendSubEntry(source, "complexityanalyzer.command.geoscan.auto_pause", String.valueOf((int) profile.msptLimit), ChatFormatting.GRAY, ChatFormatting.GREEN);
            } else {
                output.sendSubEntry(source, "complexityanalyzer.command.geoscan.no_mspt_limit", "", ChatFormatting.GRAY, ChatFormatting.RED);
            }

            output.sendSubEntry(source, "complexityanalyzer.command.geoscan.vanilla_gen", "", ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "👤", "complexityanalyzer.command.geoscan.initiator_label", initiatorName, ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEmptyLine(source);
            output.sendFooter(source);

            if (force) {
                if (profile == ScanProfile.MAXIMUM || profile == ScanProfile.ULTRA_FAST) {
                    output.broadcastSever(Component.translatable("complexityanalyzer.command.geoscan.force_broadcast"));
                    output.broadcastSever(Component.translatable("complexityanalyzer.command.geoscan.lag_expected", profile.displayName));
                } else {
                    output.broadcastWarning(Component.translatable("complexityanalyzer.command.geoscan.started_broadcast", profile.displayName.toLowerCase(ROOT)));
                }
                manager.startScanImmediately(chunks, initiatorName, profile);
            } else {
                if (profile == ScanProfile.MAXIMUM || profile == ScanProfile.ULTRA_FAST) {
                    output.broadcastWarning(Component.translatable("complexityanalyzer.command.geoscan.scheduled_broadcast", profile.displayName));
                } else {
                    output.broadcast(Component.translatable("complexityanalyzer.command.geoscan.starting_soon", profile.displayName.toLowerCase(ROOT)));
                }
                manager.scheduleScan(chunks, initiatorName, profile);
            }

            output.sendToAdmins(Component.translatable("complexityanalyzer.command.geoscan.admin_log",
                    force ? "Force started" : "Scheduled", initiatorName, profile.displayName, String.valueOf(chunks)));
        } else {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.not_initialized"));
        }
        return 1;
    }

    private static int executeStop(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            if (!manager.isScanning() && !manager.isCountdownActive()) {
                output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.no_scan_running"));
                return 0;
            }

            output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.stopping"));
            manager.stopScan(source);
            output.broadcast(Component.translatable("complexityanalyzer.command.geoscan.stopped_broadcast").withStyle(ChatFormatting.GREEN));
            output.sendToAdmins(Component.translatable("complexityanalyzer.command.geoscan.stopped_admin", source.getTextName()));
        } else {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.not_initialized"));
        }
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            output.sendEmptyLine(source);
            output.sendHeader(source, "📊", "complexityanalyzer.command.geoscan.status_header", ChatFormatting.GOLD);
            output.sendEmptyLine(source);

            var session = manager.getCurrentSession();

            if (session != null && session.isValid()) {
                renderActiveStatus(output, source, manager, session);
            } else if (manager.isCountdownActive()) {
                output.sendStatusLine(source, "⏳", manager.getStatus(), ChatFormatting.YELLOW);
            } else {
                var statusComponent = manager.getStatus();
                String status = ServerLanguage.translateForPlayer(statusComponent, null).getString();
                var color = status.toLowerCase(ROOT).contains("complete") ? ChatFormatting.GREEN : ChatFormatting.GRAY;
                String statusIcon = status.toLowerCase(ROOT).contains("complete") ? "✓" : "💤";
                output.sendStatusLine(source, statusIcon, statusComponent, color);
            }

            output.sendEmptyLine(source);
            output.sendFooter(source);
        } else {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.not_initialized"));
        }
        return 1;
    }

    private static void renderActiveStatus(OutputManager output, CommandSourceStack source,
                                           GeoAnalysisManager manager, ScanSession session) {
        var profile = session.getProfile();
        String icon = getProfileIcon(profile);
        var color = getProfileColor(profile);

        output.sendSubEntry(source, icon, "complexityanalyzer.command.geoscan.profile_label", profile.displayName, ChatFormatting.GRAY, color);
        output.sendSubEntry(source, "complexityanalyzer.command.geoscan.background_gen", "", ChatFormatting.GRAY, ChatFormatting.AQUA);

        long scanned = session.getTotalChunksScanned();
        int total = session.getTotalChunksNeeded();
        int percent = session.getProgressPercent();

        output.sendProgressBar(source, "complexityanalyzer.command.geoscan.progress_label", percent, percent + "%", ChatFormatting.GRAY, percent >= 100 ? ChatFormatting.GREEN : ChatFormatting.AQUA);
        output.sendSubEntry(source, "complexityanalyzer.command.geoscan.chunks_label_short", scanned + " / " + total, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendSubEntry(source, "complexityanalyzer.command.geoscan.speed_label", Component.translatable("complexityanalyzer.command.geoscan.speed_value", session.getScanSpeed()).getString(), ChatFormatting.GRAY, ChatFormatting.AQUA);

        long elapsed = session.getElapsedSeconds();
        output.sendSubEntry(source, "⌛", "complexityanalyzer.command.geoscan.elapsed_label", formatTime(elapsed), ChatFormatting.GRAY, ChatFormatting.WHITE);

        if (profile.hasMsptLimit()) {
            float mspt = manager.getCurrentMspt();

            var msptLine = Component.literal("  MSPT: ").withStyle(ChatFormatting.GRAY);

            if (manager.isThrottled()) {
                msptLine.append(Component.literal(String.format("%.1f", mspt)).withStyle(ChatFormatting.RED))
                        .append(Component.translatable("complexityanalyzer.command.geoscan.throttled").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            } else {
                ChatFormatting msptColor = mspt < profile.msptLimit * 0.7f ? ChatFormatting.GREEN :
                        mspt < profile.msptLimit ? ChatFormatting.YELLOW : ChatFormatting.RED;
                msptLine.append(Component.literal(String.format("%.1f", mspt)).withStyle(msptColor))
                        .append(Component.literal("/" + (int) profile.msptLimit).withStyle(ChatFormatting.DARK_GRAY));
            }

            output.sendInfo(source, msptLine);
        }

        var tooltip = buildBiomeTooltip(session);
        if (tooltip != null) {
            output.sendEmptyLine(source);

            var biomeStatus = Component.translatable("complexityanalyzer.command.geoscan.biome_details").withStyle(ChatFormatting.AQUA);
            biomeStatus.append(Component.literal(" "));

            biomeStatus.append(Component.translatable("complexityanalyzer.command.geoscan.hover_details")
                    .withStyle(EMPTY.withColor(ChatFormatting.DARK_AQUA).withItalic(true)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip))));

            output.sendInfo(source, biomeStatus);
        }
    }

    private static MutableComponent buildBiomeTooltip(ScanSession session) {
        var progress = session.getBiomeProgress();

        var incompleteProgress = new Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<Object2ObjectMap.Entry<ResourceLocation, int[]>>>();
        int totalIncomplete = 0;

        for (var dimEntry : progress.object2ObjectEntrySet()) {
            var dimId = dimEntry.getKey();
            var incompleteBiomes = new ObjectArrayList<Object2ObjectMap.Entry<ResourceLocation, int[]>>();

            for (var biomeEntry : dimEntry.getValue().object2ObjectEntrySet()) {
                int[] stats = biomeEntry.getValue();
                int scanned = stats[0];
                int needed = stats[1];
                if (scanned < needed) {
                    incompleteBiomes.add(biomeEntry);
                    totalIncomplete++;
                }
            }
            if (!incompleteBiomes.isEmpty()) incompleteProgress.put(dimId, incompleteBiomes);
        }

        if (totalIncomplete == 0) return null;
        var tooltip = Component.empty();

        tooltip.append(Component.translatable("complexityanalyzer.command.geoscan.biome_progress_header").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        int biomesShown = 0;
        int maxBiomes = 25;

        for (var dimEntry : incompleteProgress.object2ObjectEntrySet()) {
            var dimId = dimEntry.getKey();
            var biomes = dimEntry.getValue();

            tooltip.append(Component.literal("\n").append(Component.literal("▸ ")
                    .append(formatDimensionName(dimId)).append("\n").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

            for (var biomeEntry : biomes) {
                if (biomesShown >= maxBiomes) {
                    int remaining = totalIncomplete - biomesShown;
                    if (remaining > 0) tooltip.append(Component
                            .translatable("complexityanalyzer.command.geoscan.more_biomes", remaining)
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                    return tooltip;
                }

                var biomeId = biomeEntry.getKey();
                int[] stats = biomeEntry.getValue();
                int scanned = stats[0];
                int needed = stats[1];

                var biomeLine = Component.literal("  ");

                int biomePercent = needed > 0 ? (scanned * 100 / needed) : 0;
                ChatFormatting biomeColor = biomePercent >= 75 ? ChatFormatting.YELLOW : biomePercent >= 50 ? ChatFormatting.GOLD : ChatFormatting.WHITE;

                biomeLine.append(Component.literal("○ ").withStyle(ChatFormatting.GRAY));
                biomeLine.append(Component.literal(biomeId.getPath()).withStyle(biomeColor));
                biomeLine.append(Component.literal(" (" + scanned + "/" + needed + ")").withStyle(ChatFormatting.GRAY));

                tooltip.append(biomeLine);
                tooltip.append(Component.literal("\n"));
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
        if (manager != null) {
            if (manager.isScanning() || manager.isCountdownActive()) {
                output.sendFailure(source, Component.translatable("complexityanalyzer.command.geoscan.cannot_clear"));
                output.sendTip(source, "complexityanalyzer.command.geoscan.clear_tip", ComplexityCommand.ROOT);
                return 0;
            } else {
                output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.clearing"));
                engine.clearGeoDatabase();
                output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.cleared_broadcast"));
                output.sendToAdmins(Component.translatable("complexityanalyzer.command.geoscan.cleared_admin", source.getTextName()));
            }
        } else {
            engine.clearGeoDatabase();
            output.sendSuccess(source, Component.translatable("complexityanalyzer.command.geoscan.cleared_broadcast"));
        }
        return 1;
    }

    private static String getProfileIcon(ScanProfile profile) {
        return switch (profile) {
            case NORMAL -> "🟢";
            case FAST -> "🟡";
            case ULTRA_FAST -> "🟠";
            case MAXIMUM -> "🔴";
        };
    }

    private static ChatFormatting getProfileColor(ScanProfile profile) {
        return switch (profile) {
            case NORMAL -> ChatFormatting.GREEN;
            case FAST -> ChatFormatting.YELLOW;
            case ULTRA_FAST -> ChatFormatting.GOLD;
            case MAXIMUM -> ChatFormatting.RED;
        };
    }

    private static String formatTime(long seconds) {
        var sSuffix = Component.translatable("complexityanalyzer.unit.time.seconds_short");
        var mSuffix = Component.translatable("complexityanalyzer.unit.time.minutes_short");
        var hSuffix = Component.translatable("complexityanalyzer.unit.time.hours_short");

        if (seconds < 60) {
            return seconds + sSuffix.getString();
        } else if (seconds < 3600) {
            return (seconds / 60) + mSuffix.getString() + " " + (seconds % 60) + sSuffix.getString();
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return hours + hSuffix.getString() + " " + mins + mSuffix.getString();
        }
    }

    private static Component formatDimensionName(ResourceLocation dimId) {
        String path = dimId.getPath();
        return switch (path) {
            case "overworld" -> Component.literal("🌍 ")
                    .append(Component.translatable("complexityanalyzer.dimension.overworld"));
            case "the_nether" -> Component.literal("🔥 ")
                    .append(Component.translatable("complexityanalyzer.dimension.nether"));
            case "the_end" -> Component.literal("🌌 ")
                    .append(Component.translatable("complexityanalyzer.dimension.the_end"));
            default -> Component.literal(dimId.toString());
        };
    }
}