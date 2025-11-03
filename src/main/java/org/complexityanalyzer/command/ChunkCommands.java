/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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
import net.minecraft.network.chat.Component;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;

public class ChunkCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("geoscan")
                .then(Commands.literal("start")
                        .executes(ctx -> executeScan(ctx, 32, "lite", false))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "chunks"), "lite", false))
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"lite", "fast", "extreme", "atomic"}, b))
                                        .executes(ctx -> executeScan(ctx,
                                                IntegerArgumentType.getInteger(ctx, "chunks"),
                                                StringArgumentType.getString(ctx, "profile"),
                                                false
                                        ))
                                        .then(Commands.argument("force", BoolArgumentType.bool())
                                                .executes(ctx -> executeScan(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "chunks"),
                                                        StringArgumentType.getString(ctx, "profile"),
                                                        BoolArgumentType.getBool(ctx, "force")
                                                ))
                                        )
                                )
                        )
                )
                .then(Commands.literal("stop").executes(ChunkCommands::executeStop))
                .then(Commands.literal("status").executes(ChunkCommands::executeStatus))
                .then(Commands.literal("clear").executes(ChunkCommands::executeClear));
    }

    private static int executeScan(CommandContext<CommandSourceStack> context, int chunks, String profileName, boolean force) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        final GeoAnalysisManager.ScanProfile profile;
        try {
            profile = GeoAnalysisManager.ScanProfile.valueOf(profileName.toUpperCase());
        } catch (IllegalArgumentException e) {
            output.sendFailure(source,
                    Component.literal("❌ Unknown scan profile: ")
                            .append(Component.literal(profileName)
                                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

            output.sendInfo(source,
                    Component.literal("Available profiles:")
                            .withStyle(ChatFormatting.GRAY));

            output.sendInfo(source,
                    Component.literal("  🟢 lite")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(" - Low impact, slower")
                                    .withStyle(ChatFormatting.DARK_GRAY)));
            output.sendInfo(source,
                    Component.literal("  🟡 fast")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(" - Balanced performance")
                                    .withStyle(ChatFormatting.DARK_GRAY)));
            output.sendInfo(source,
                    Component.literal("  🟠 extreme")
                            .withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(" - High performance, may cause lag")
                                    .withStyle(ChatFormatting.DARK_GRAY)));
            output.sendInfo(source,
                    Component.literal("  🔴 atomic")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                            .append(Component.literal(" - MAXIMUM SPEED, EXPECT HEAVY LAG!")
                                    .withStyle(ChatFormatting.DARK_RED)));

            return 0;
        }

        AnalysisEngine.getInstance().getGeoManager().ifPresentOrElse(
                manager -> {
                    if (manager.isScanning() || manager.isCountdownActive()) {
                        output.sendFailure(source,
                                Component.literal("⚠ A scan is already in progress!")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

                        output.sendInfo(source,
                                Component.literal("Use ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal("/complexity geoscan stop")
                                                .withStyle(ChatFormatting.YELLOW))
                                        .append(Component.literal(" to cancel it first")
                                                .withStyle(ChatFormatting.GRAY)));
                        return;
                    }

                    String initiatorName = source.getTextName();
                    String profileIcon = getProfileIcon(profile);
                    ChatFormatting profileColor = getProfileColor(profile);

                    if (force) {
                        output.sendInfo(source, Component.literal(""));
                        output.sendInfo(source,
                                Component.literal("═══════════════════════════════")
                                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));

                        output.sendInfo(source,
                                Component.literal("⚡ FORCE STARTING GEO-SCAN")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

                        output.sendInfo(source, Component.literal(""));
                        output.sendInfo(source,
                                Component.literal("  Profile: " + profileIcon + " ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(profileName.toUpperCase())
                                                .withStyle(profileColor, ChatFormatting.BOLD)));

                        output.sendInfo(source,
                                Component.literal("  Chunks: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(String.valueOf(chunks))
                                                .withStyle(ChatFormatting.YELLOW)));

                        output.sendInfo(source,
                                Component.literal("  Initiator: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(initiatorName)
                                                .withStyle(ChatFormatting.WHITE)));

                        output.sendInfo(source,
                                Component.literal("═══════════════════════════════")
                                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                        output.sendInfo(source, Component.literal(""));

                        if (profile == GeoAnalysisManager.ScanProfile.EXTREME ||
                                profile == GeoAnalysisManager.ScanProfile.ATOMIC) {
                            output.broadcastSever(
                                    Component.literal("⚠⚠⚠ GEO-SCAN FORCE STARTED ⚠⚠⚠"));
                            output.broadcastSever(
                                    Component.literal("EXPECT SEVERE LAG! Profile: " + profileName.toUpperCase()));
                        } else {
                            output.broadcastWarning(
                                    Component.literal("⚡ Geo-scan started. Possible lag!"));
                        }

                        output.sendToAdmins(
                                Component.literal("Force geo-scan initiated by " + initiatorName)
                                        .append(Component.literal(" | Chunks: " + chunks + " | Profile: " + profileName)));

                        manager.startScanImmediately(chunks, initiatorName, profile);

                    } else {
                        output.sendInfo(source, Component.literal(""));
                        output.sendInfo(source,
                                Component.literal("═══════════════════════════════")
                                        .withStyle(ChatFormatting.DARK_GRAY));

                        output.sendInfo(source,
                                Component.literal("📊 GEO-SCAN SCHEDULED")
                                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

                        output.sendInfo(source, Component.literal(""));
                        output.sendInfo(source,
                                Component.literal("  Profile: " + profileIcon + " ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(profileName.toUpperCase())
                                                .withStyle(profileColor)));

                        output.sendInfo(source,
                                Component.literal("  Chunks: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(String.valueOf(chunks))
                                                .withStyle(ChatFormatting.AQUA)));

                        output.sendInfo(source,
                                Component.literal("  Status: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal("⏳ Countdown initiated")
                                                .withStyle(ChatFormatting.YELLOW)));

                        output.sendInfo(source,
                                Component.literal("═══════════════════════════════")
                                        .withStyle(ChatFormatting.DARK_GRAY));
                        output.sendInfo(source, Component.literal(""));

                        if (profile == GeoAnalysisManager.ScanProfile.EXTREME ||
                                profile == GeoAnalysisManager.ScanProfile.ATOMIC) {
                            output.broadcastWarning(
                                    Component.literal("⚠ Geo-scan scheduled! Lag expected in 10 seconds..."));
                        } else {
                            output.broadcast(
                                    Component.literal("📊 Geo-scan starting soon..."));
                        }

                        output.sendToAdmins(
                                Component.literal("Geo-scan scheduled by " + initiatorName)
                                        .append(Component.literal(" | " + chunks + " chunks | " + profileName)));

                        manager.scheduleScan(chunks, initiatorName, profile);
                    }
                },
                () -> {
                    output.sendFailure(source,
                            Component.literal("❌ GeoAnalysisManager is not initialized!")
                                    .withStyle(ChatFormatting.RED));
                    output.sendInfo(source,
                            Component.literal("This feature may be disabled in the config.")
                                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
        );
        return 1;
    }

    private static int executeStop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        AnalysisEngine.getInstance().getGeoManager().ifPresentOrElse(
                manager -> {
                    output.sendInfo(source,
                            Component.literal("🛑 Stopping geo-scan...")
                                    .withStyle(ChatFormatting.YELLOW));

                    manager.stopScan(source);

                    output.broadcast(
                            Component.literal("✓ Geo-scan stopped by admin")
                                    .withStyle(ChatFormatting.GREEN));

                    output.sendToAdmins(
                            Component.literal("Scan stopped by " + source.getTextName()));
                },
                () -> output.sendFailure(source,
                        Component.literal("❌ GeoAnalysisManager is not initialized."))
        );
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        AnalysisEngine.getInstance().getGeoManager().ifPresentOrElse(
                manager -> {
                    String status = manager.getStatus();

                    output.sendInfo(source, Component.literal(""));
                    output.sendInfo(source,
                            Component.literal("═══════════════════════════════")
                                    .withStyle(ChatFormatting.DARK_GRAY));

                    output.sendInfo(source,
                            Component.literal("📊 GEO-SCAN STATUS")
                                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

                    output.sendInfo(source, Component.literal(""));

                    if (status.contains("IDLE") || status.contains("idle")) {
                        output.sendInfo(source,
                                Component.literal("  Status: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal("💤 IDLE")
                                                .withStyle(ChatFormatting.GREEN)));
                        output.sendInfo(source,
                                Component.literal("  No active scans")
                                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

                    } else if (status.contains("SCANNING") || status.contains("scanning")) {
                        output.sendInfo(source,
                                Component.literal("  Status: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal("⚙ SCANNING")
                                                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

                        try {
                            if (status.contains("%")) {
                                String percent = status.substring(status.indexOf("(") + 1, status.indexOf("%"));
                                int percentValue = Integer.parseInt(percent.trim());
                                String progressBar = getProgressBar(percentValue);

                                output.sendInfo(source,
                                        Component.literal("  Progress: ")
                                                .withStyle(ChatFormatting.GRAY)
                                                .append(Component.literal(percent + "%")
                                                        .withStyle(ChatFormatting.AQUA)));

                                output.sendInfo(source,
                                        Component.literal("  " + progressBar)
                                                .withStyle(ChatFormatting.DARK_GRAY));
                            }
                        } catch (Exception e) {
                            output.sendInfo(source,
                                    Component.literal("  " + status)
                                            .withStyle(ChatFormatting.WHITE));
                        }

                    } else if (status.contains("COUNTDOWN")) {
                        output.sendInfo(source,
                                Component.literal("  Status: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal("⏳ COUNTDOWN")
                                                .withStyle(ChatFormatting.GOLD)));
                        output.sendInfo(source,
                                Component.literal("  Starting soon...")
                                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));

                    } else {
                        output.sendInfo(source,
                                Component.literal("  Status: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(status)
                                                .withStyle(ChatFormatting.WHITE)));
                    }

                    output.sendInfo(source,
                            Component.literal("═══════════════════════════════")
                                    .withStyle(ChatFormatting.DARK_GRAY));
                    output.sendInfo(source, Component.literal(""));
                },
                () -> output.sendFailure(source,
                        Component.literal("❌ GeoAnalysisManager is not initialized."))
        );
        return 1;
    }

    private static int executeClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        engine.getGeoManager().ifPresentOrElse(
                manager -> {
                    if (manager.isScanning() || manager.isCountdownActive()) {
                        output.sendFailure(source,
                                Component.literal("⚠ Cannot clear database during active scan!")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

                        output.sendInfo(source,
                                Component.literal("Use ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal("/complexity geoscan stop")
                                                .withStyle(ChatFormatting.YELLOW))
                                        .append(Component.literal(" first")
                                                .withStyle(ChatFormatting.GRAY)));
                    } else {
                        output.sendInfo(source,
                                Component.literal("🗑 Clearing geo-database...")
                                        .withStyle(ChatFormatting.YELLOW));

                        engine.clearGeoDatabase();

                        output.sendSuccess(source,
                                Component.literal("✓ Geo-database has been cleared!")
                                        .withStyle(ChatFormatting.GREEN));

                        output.sendToAdmins(
                                Component.literal("Geo-database cleared by " + source.getTextName()));
                    }
                },
                () -> {
                    output.sendInfo(source,
                            Component.literal("🗑 Clearing geo-database...")
                                    .withStyle(ChatFormatting.YELLOW));

                    engine.clearGeoDatabase();

                    output.sendSuccess(source,
                            Component.literal("✓ Geo-database has been cleared!")
                                    .withStyle(ChatFormatting.GREEN));
                }
        );
        return 1;
    }

    private static String getProfileIcon(GeoAnalysisManager.ScanProfile profile) {
        return switch (profile) {
            case LITE -> "🟢";
            case FAST -> "🟡";
            case EXTREME -> "🟠";
            case ATOMIC -> "🔴";
        };
    }

    private static ChatFormatting getProfileColor(GeoAnalysisManager.ScanProfile profile) {
        return switch (profile) {
            case LITE -> ChatFormatting.GREEN;
            case FAST -> ChatFormatting.YELLOW;
            case EXTREME -> ChatFormatting.GOLD;
            case ATOMIC -> ChatFormatting.RED;
        };
    }

    private static String getProgressBar(int percent) {
        int filled = percent / 10;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i < filled) {
                bar.append("█");
            } else {
                bar.append("░");
            }
        }
        bar.append("] ");
        bar.append(percent).append("%");
        return bar.toString();
    }
}