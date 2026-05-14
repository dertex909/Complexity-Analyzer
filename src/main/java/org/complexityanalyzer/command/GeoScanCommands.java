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

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.scan.ScanSession;

import java.util.Map;

public class GeoScanCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("geoscan").then(Commands.literal("start")
                .executes(GeoScanCommands::showProfileHelp).then(Commands.argument("profile", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"normal", "fast", "ultra_fast", "maximum"}, b))
                        .executes(ctx -> executeScan(ctx, 32, StringArgumentType.getString(ctx, "profile"), false)).then(Commands.argument("chunks", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
                                .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "chunks"), StringArgumentType.getString(ctx, "profile"), false)).then(Commands.argument("force", BoolArgumentType.bool())
                                        .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "chunks"), StringArgumentType.getString(ctx, "profile"), BoolArgumentType.getBool(ctx, "force"))))))).then(Commands.literal("stop")
                .executes(GeoScanCommands::executeStop)).then(Commands.literal("status")
                .executes(GeoScanCommands::executeStatus)).then(Commands.literal("clear")
                .executes(GeoScanCommands::executeClear));
    }

    private static int showProfileHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "GEO-SCAN — SELECT PROFILE", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        for (ScanProfile profile : ScanProfile.values()) {
            String icon = getProfileIcon(profile);
            output.sendClickableTip(source, icon + " ", profile.displayName, " — " + (profile.hasMsptLimit() ? "limit " + (int) profile.msptLimit + " MSPT" : "no MSPT limit"),
                    "/complexity geoscan start " + profile.commandName, "Click to select " + profile.displayName + " profile");
        }

        output.sendEmptyLine(source);
        output.sendFooter(source);

        output.sendTip(source, "Usage: /complexity geoscan start <profile> [chunks] [force]");
        output.sendTip(source, "Example: /complexity geoscan start normal 32");
        output.sendEmptyLine(source);

        return 1;
    }

    private static int executeScan(CommandContext<CommandSourceStack> context,
                                   int chunks, String profileName, boolean force) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        final ScanProfile profile;
        try {
            profile = ScanProfile.fromInput(profileName);
        } catch (IllegalArgumentException e) {
            output.sendFailure(source, Component.literal("❌ Unknown profile: " + profileName));
            output.sendTip(source, "Use /complexity geoscan start to see available profiles");
            return 0;
        }

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            if (manager.isScanning() || manager.isCountdownActive()) {
                output.sendFailure(source, Component.literal("⚠ A scan is already in progress!"));
                output.sendTip(source, "Use /complexity geoscan stop to cancel it first");
                return 1;
            }

            String initiatorName = source.getTextName();
            String icon = getProfileIcon(profile);
            ChatFormatting color = getProfileColor(profile);

            output.sendEmptyLine(source);
            output.sendHeader(source, force ? "⚡" : "📊", force ? "FORCE STARTING GEO-SCAN" : "GEO-SCAN SCHEDULED", force ? ChatFormatting.RED : ChatFormatting.AQUA);
            output.sendEmptyLine(source);

            output.sendEntry(source, icon, "Profile", profile.displayName, ChatFormatting.GRAY, color);
            output.sendEntry(source, "🌍", "Chunks/biome", String.valueOf(chunks), ChatFormatting.GRAY, ChatFormatting.AQUA);

            if (profile.hasMsptLimit()) {
                output.sendSubEntry(source, "Protection", "Auto-pause at MSPT > " + (int) profile.msptLimit, ChatFormatting.GRAY, ChatFormatting.GREEN);
            } else {
                output.sendSubEntry(source, "Protection", "No limit (may lag)", ChatFormatting.GRAY, ChatFormatting.RED);
            }

            output.sendSubEntry(source, "Generation", "Vanilla chunk generation", ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEntry(source, "👤", "Initiator", initiatorName, ChatFormatting.GRAY, ChatFormatting.WHITE);
            output.sendEmptyLine(source);
            output.sendFooter(source);

            if (force) {
                if (profile == ScanProfile.MAXIMUM || profile == ScanProfile.ULTRA_FAST) {
                    output.broadcastSever(Component.literal("⚠⚠⚠ GEO-SCAN FORCE STARTED ⚠⚠⚠"));
                    output.broadcastSever(Component.literal("Profile: " + profile.displayName + " | lag expected!"));
                } else {
                    output.broadcastWarning(Component.literal("⚡ Geo-scan started (" + profile.displayName.toLowerCase() + ")"));
                }
                manager.startScanImmediately(chunks, initiatorName, profile);
            } else {
                if (profile == ScanProfile.MAXIMUM || profile == ScanProfile.ULTRA_FAST) {
                    output.broadcastWarning(Component.literal("⚠ Geo-scan scheduled! " + profile.displayName + " — lag may happen soon..."));
                } else {
                    output.broadcast(Component.literal("📊 Geo-scan starting soon (" + profile.displayName.toLowerCase() + ")"));
                }
                manager.scheduleScan(chunks, initiatorName, profile);
            }

            output.sendToAdmins(Component.literal("[GeoScan] " + (force ? "Force started" : "Scheduled")
                    + " by " + initiatorName + " | Profile: " + profile.displayName + " | Chunks: " + chunks));
        } else {
            output.sendFailure(source, Component.literal("❌ GeoAnalysisManager is not initialized!"));
        }
        return 1;
    }

    private static int executeStop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            if (!manager.isScanning() && !manager.isCountdownActive()) {
                output.sendFailure(source, Component.literal("❌ No scan is currently running or scheduled."));
                return 0;
            }

            output.sendSuccess(source, Component.literal("🛑 Stopping geo-scan..."));
            manager.stopScan(source);
            output.broadcast(Component.literal("✓ Geo-scan stopped").withStyle(ChatFormatting.GREEN));
            output.sendToAdmins(Component.literal("[GeoScan] Stopped by " + source.getTextName()));
        } else {
            output.sendFailure(source, Component.literal("❌ GeoAnalysisManager is not initialized."));
        }
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            output.sendEmptyLine(source);
            output.sendHeader(source, "📊", "GEO-SCAN STATUS", ChatFormatting.GOLD);
            output.sendEmptyLine(source);

            ScanSession session = manager.getCurrentSession();

            if (session != null && session.isValid()) {
                renderActiveStatus(output, source, manager, session);
            } else if (manager.isCountdownActive()) {
                output.sendStatusLine(source, "⏳", manager.getStatus(), ChatFormatting.YELLOW);
            } else {
                String status = manager.getStatus();
                ChatFormatting color = status.toLowerCase().contains("complete") ? ChatFormatting.GREEN : ChatFormatting.GRAY;
                String statusIcon = status.toLowerCase().contains("complete") ? "✓" : "💤";
                output.sendStatusLine(source, statusIcon, status, color);
            }

            output.sendEmptyLine(source);
            output.sendFooter(source);
        } else {
            output.sendFailure(source, Component.literal("❌ GeoAnalysisManager is not initialized."));
        }
        return 1;
    }

    private static void renderActiveStatus(OutputManager output, CommandSourceStack source,
                                           GeoAnalysisManager manager, ScanSession session) {
        ScanProfile profile = session.getProfile();
        String icon = getProfileIcon(profile);
        ChatFormatting color = getProfileColor(profile);

        output.sendSubEntry(source, icon, "Profile", profile.displayName, ChatFormatting.GRAY, color);
        output.sendSubEntry(source, "Generation", "Vanilla (background analysis)", ChatFormatting.GRAY, ChatFormatting.AQUA);

        long scanned = session.getTotalChunksScanned();
        int total = session.getTotalChunksNeeded();
        int percent = session.getProgressPercent();

        output.sendProgressBar(source, "Progress", percent, percent + "%", ChatFormatting.GRAY, percent >= 100 ? ChatFormatting.GREEN : ChatFormatting.AQUA);
        output.sendSubEntry(source, "Chunks", scanned + " / " + total, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendSubEntry(source, "Speed", String.format("%.1f claimed/sec", session.getScanSpeed()), ChatFormatting.GRAY, ChatFormatting.AQUA);

        long elapsed = session.getElapsedSeconds();
        output.sendSubEntry(source, "Elapsed", formatTime(elapsed), ChatFormatting.GRAY, ChatFormatting.WHITE);

        if (profile.hasMsptLimit()) {
            float mspt = manager.getCurrentMspt();

            MutableComponent msptLine = Component.literal("  MSPT: ").withStyle(ChatFormatting.GRAY);

            if (manager.isThrottled()) {
                msptLine.append(Component.literal(String.format("%.1f", mspt)).withStyle(ChatFormatting.RED))
                        .append(Component.literal(" ⚠ THROTTLED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            } else {
                ChatFormatting msptColor = mspt < profile.msptLimit * 0.7f ? ChatFormatting.GREEN :
                        mspt < profile.msptLimit ? ChatFormatting.YELLOW : ChatFormatting.RED;
                msptLine.append(Component.literal(String.format("%.1f", mspt)).withStyle(msptColor))
                        .append(Component.literal("/" + (int) profile.msptLimit).withStyle(ChatFormatting.DARK_GRAY));
            }

            output.sendInfo(source, msptLine);
        }

        output.sendEmptyLine(source);

        MutableComponent biomeStatus = Component.literal("  📋 Biome Details ").withStyle(ChatFormatting.AQUA);

        MutableComponent tooltip = buildBiomeTooltip(session);

        biomeStatus.append(Component.literal("[hover for details]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.DARK_AQUA).withItalic(true)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip))));

        output.sendInfo(source, biomeStatus);
    }

    private static MutableComponent buildBiomeTooltip(ScanSession session) {
        MutableComponent tooltip = Component.empty();

        tooltip.append(Component.literal("═══ BIOME PROGRESS ═══\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        Map<ResourceLocation, Map<ResourceLocation, int[]>> progress = session.getBiomeProgress();

        int biomesShown = 0;
        int maxBiomes = 25;

        for (var dimEntry : progress.entrySet()) {
            ResourceLocation dimId = dimEntry.getKey();

            tooltip.append(Component.literal("\n").append(Component.literal("▸ "
                    + formatDimensionName(dimId) + "\n").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

            for (var biomeEntry : dimEntry.getValue().entrySet()) {
                if (biomesShown >= maxBiomes) {
                    int remaining = progress.values().stream().mapToInt(Map::size).sum() - maxBiomes;
                    if (remaining > 0) tooltip.append(Component.literal("\n... and " + remaining + " more biomes")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                    return tooltip;
                }

                ResourceLocation biomeId = biomeEntry.getKey();
                int[] stats = biomeEntry.getValue();
                int scanned = stats[0];
                int needed = stats[1];
                boolean complete = scanned >= needed;

                MutableComponent biomeLine = Component.literal("  ");

                if (complete) {
                    biomeLine.append(Component.literal("✓ ").withStyle(ChatFormatting.GREEN));
                    biomeLine.append(Component.literal(biomeId.getPath()).withStyle(ChatFormatting.GREEN));
                    biomeLine.append(Component.literal(" (" + scanned + "/" + needed + ")").withStyle(ChatFormatting.DARK_GREEN));
                } else {
                    int biomePercent = needed > 0 ? (scanned * 100 / needed) : 0;
                    ChatFormatting biomeColor = biomePercent >= 75 ? ChatFormatting.YELLOW : biomePercent >= 50 ? ChatFormatting.GOLD : ChatFormatting.WHITE;

                    biomeLine.append(Component.literal("○ ").withStyle(ChatFormatting.GRAY));
                    biomeLine.append(Component.literal(biomeId.getPath()).withStyle(biomeColor));
                    biomeLine.append(Component.literal(" (" + scanned + "/" + needed + ")").withStyle(ChatFormatting.GRAY));
                }

                tooltip.append(biomeLine);
                tooltip.append(Component.literal("\n"));
                biomesShown++;
            }
        }

        return tooltip;
    }

    private static int executeClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        var manager = engine.getGeoManager();
        if (manager != null) {
            if (manager.isScanning() || manager.isCountdownActive()) {
                output.sendFailure(source, Component.literal("⚠ Cannot clear database during active scan!"));
                output.sendTip(source, "Use /complexity geoscan stop first");
                return 0;
            } else {
                output.sendSuccess(source, Component.literal("🗑 Clearing geo-database..."));
                engine.clearGeoDatabase();
                output.sendSuccess(source, Component.literal("✓ Geo-database has been cleared!"));
                output.sendToAdmins(Component.literal("[GeoScan] Database cleared by " + source.getTextName()));
            }
        } else {
            engine.clearGeoDatabase();
            output.sendSuccess(source, Component.literal("✓ Geo-database has been cleared!"));
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
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return hours + "h " + mins + "m";
        }
    }

    private static String formatDimensionName(ResourceLocation dimId) {
        String path = dimId.getPath();
        return switch (path) {
            case "overworld" -> "🌍 Overworld";
            case "the_nether" -> "🔥 Nether";
            case "the_end" -> "🌌 The End";
            default -> dimId.toString();
        };
    }
}