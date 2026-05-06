/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.geoscan.GeoAnalysisManager;
import org.complexityanalyzer.geoscan.config.ScanConfig;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.scan.ScanSession;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.Map;

public class GeoScanCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("geoscan").then(Commands.literal("start")
                        .executes(GeoScanCommands::showProfileHelp)
                        .then(Commands.argument("profile", StringArgumentType.word())
                                .suggests((c, b) ->
                                        SharedSuggestionProvider.suggest(
                                                new String[]{"quarter", "half", "most", "full"}, b
                                        ))
                                .executes(ctx ->
                                        executeScan(ctx, 32, StringArgumentType.getString(ctx, "profile"),
                                                false
                                        ))
                                .then(Commands.argument("chunks", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
                                        .executes(ctx ->
                                                executeScan(ctx, IntegerArgumentType.getInteger(ctx, "chunks"),
                                                        StringArgumentType.getString(ctx, "profile"), false
                                                ))
                                        .then(Commands.argument("force", BoolArgumentType.bool())
                                                .executes(ctx ->
                                                        executeScan(ctx, IntegerArgumentType.getInteger(ctx, "chunks"),
                                                                StringArgumentType.getString(ctx, "profile"),
                                                                BoolArgumentType.getBool(ctx, "force"
                                                                )))))))
                .then(Commands.literal("stop").executes(GeoScanCommands::executeStop))
                .then(Commands.literal("status").executes(GeoScanCommands::executeStatus))
                .then(Commands.literal("clear").executes(GeoScanCommands::executeClear));
    }

    private static int showProfileHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        int totalThreads = ThreadPoolManager.getInstance().getParallelism();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.GOLD));
        output.sendInfo(source, Component.literal("📊 GEO-SCAN — SELECT PROFILE")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.GOLD));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source, Component.literal("  💻 Your system: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(totalThreads + " threads available")
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal(""));

        for (ScanProfile profile : ScanProfile.values()) {
            int workers = profile.getWorkerCount(totalThreads);
            int percent = Math.round(profile.threadFraction * 100);
            String icon = getProfileIcon(profile);
            ChatFormatting color = getProfileColor(profile);

            MutableComponent profileLine = Component.literal("  " + icon + " ")
                    .append(Component.literal(profile.name().toLowerCase()).withStyle(color, ChatFormatting.BOLD))
                    .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY));

            profileLine.append(Component.literal(workers + " workers").withStyle(ChatFormatting.WHITE));
            profileLine.append(Component.literal(" (" + percent + "% CPU)").withStyle(ChatFormatting.GRAY));

            if (profile.hasMsptLimit()) {
                profileLine.append(Component.literal(" | MSPT < " + (int) profile.msptLimit)
                        .withStyle(ChatFormatting.AQUA));
            } else {
                profileLine.append(Component.literal(" | NO LIMIT").withStyle(ChatFormatting.RED));
            }

            ObjectList<Component> tooltipLines = new ObjectArrayList<>();
            tooltipLines.add(Component.literal("═══ " + profile.name() + " PROFILE ═══")
                    .withStyle(color, ChatFormatting.BOLD));
            tooltipLines.add(Component.literal(""));
            tooltipLines.add(Component.literal("Workers: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(workers + "/" + totalThreads).withStyle(ChatFormatting.WHITE)));
            tooltipLines.add(Component.literal("CPU Usage: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(percent + "%").withStyle(ChatFormatting.WHITE)));
            tooltipLines.add(Component.literal("Max Parallel Chunks: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(profile.maxParallelChunks)).withStyle(ChatFormatting.WHITE)));

            if (profile.hasMsptLimit()) {
                tooltipLines.add(Component.literal(""));
                tooltipLines.add(Component.literal("⚡ Auto-throttle enabled").withStyle(ChatFormatting.AQUA));
                tooltipLines.add(Component.literal("Pauses when MSPT > "
                        + (int) profile.msptLimit + "ms").withStyle(ChatFormatting.GRAY));
                tooltipLines.add(Component.literal("Resumes when MSPT < " + (int)
                                (profile.msptLimit - ScanConfig.MSPT_RECOVERY_THRESHOLD_MS) + "ms")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                tooltipLines.add(Component.literal(""));
                tooltipLines.add(Component.literal("⚠ No throttle protection!").withStyle(ChatFormatting.RED));
                tooltipLines.add(Component.literal("May cause severe lag").withStyle(ChatFormatting.DARK_RED));
            }

            tooltipLines.add(Component.literal(""));
            tooltipLines.add(Component.literal("Click to select this profile")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));

            MutableComponent tooltip = Component.empty();
            for (int i = 0; i < tooltipLines.size(); i++) {
                tooltip.append(tooltipLines.get(i));
                if (i < tooltipLines.size() - 1) tooltip.append(Component.literal("\n"));
            }

            profileLine.setStyle(profileLine.getStyle()
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/complexity geoscan start " + profile.name().toLowerCase())));

            output.sendInfo(source, profileLine);
        }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source, Component.literal("  📝 Usage: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("/complexity geoscan start <profile> [chunks] [force]")
                        .withStyle(ChatFormatting.YELLOW)));

        output.sendInfo(source, Component.literal("  📝 Example: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("/complexity geoscan start quarter 32")
                        .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source, Component.literal(""));

        return 1;
    }

    private static int executeScan(CommandContext<CommandSourceStack> context,
                                   int chunks, String profileName, boolean force) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        int totalThreads = ThreadPoolManager.getInstance().getParallelism();

        final ScanProfile profile;
        try {
            profile = ScanProfile.valueOf(profileName.toUpperCase());
        } catch (IllegalArgumentException e) {
            output.sendFailure(source, Component.literal("❌ Unknown profile: ")
                    .append(Component.literal(profileName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
            output.sendInfo(source, Component.literal("Use ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("/complexity geoscan start").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" to see available profiles").withStyle(ChatFormatting.GRAY)));
            return 0;
        }

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            if (manager.isScanning() || manager.isCountdownActive()) {
                output.sendFailure(source, Component.literal("⚠ A scan is already in progress!")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                output.sendInfo(source, Component.literal("Use ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("/complexity geoscan stop").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" to cancel it first").withStyle(ChatFormatting.GRAY)));
                return 1;
            }

            String initiatorName = source.getTextName();
            int workers = profile.getWorkerCount(totalThreads);
            String icon = getProfileIcon(profile);
            ChatFormatting color = getProfileColor(profile);

            output.sendInfo(source, Component.literal(""));

            if (force) {
                output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                output.sendInfo(source, Component.literal("⚡ FORCE STARTING GEO-SCAN")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            } else {
                output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                        .withStyle(ChatFormatting.DARK_AQUA));
                output.sendInfo(source, Component.literal("📊 GEO-SCAN SCHEDULED")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            }

            output.sendInfo(source, Component.literal(""));

            output.sendInfo(source, Component.literal("  Profile: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(icon + " " + profile.name())
                            .withStyle(color, ChatFormatting.BOLD)));

            output.sendInfo(source, Component.literal("  Workers: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(workers + "/" + totalThreads + " threads")
                            .withStyle(ChatFormatting.WHITE)));

            output.sendInfo(source, Component.literal("  Chunks/biome: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(chunks))
                            .withStyle(ChatFormatting.AQUA)));

            if (profile.hasMsptLimit()) {
                output.sendInfo(source, Component.literal("  Protection: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("✓ Auto-throttle at MSPT > " + (int) profile.msptLimit)
                                .withStyle(ChatFormatting.GREEN)));
            } else {
                output.sendInfo(source, Component.literal("  Protection: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("✗ None (may cause lag)")
                                .withStyle(ChatFormatting.RED)));
            }

            output.sendInfo(source, Component.literal("  Initiator: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(initiatorName).withStyle(ChatFormatting.WHITE)));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                    .withStyle(ChatFormatting.DARK_GRAY));
            output.sendInfo(source, Component.literal(""));

            if (force) {
                if (profile == ScanProfile.FULL || profile == ScanProfile.MOST) {
                    output.broadcastSever(Component.literal("⚠⚠⚠ GEO-SCAN FORCE STARTED ⚠⚠⚠"));
                    output.broadcastSever(Component.literal("Profile: " + profile.name() +
                            " | " + workers + " workers | EXPECT LAG!"));
                } else {
                    output.broadcastWarning(Component.literal("⚡ Geo-scan started (" +
                            profile.name().toLowerCase() + " mode)"));
                }
                manager.startScanImmediately(chunks, initiatorName, profile);
            } else {
                if (profile == ScanProfile.FULL || profile == ScanProfile.MOST) {
                    output.broadcastWarning(Component.literal(
                            "⚠ Geo-scan scheduled! " + profile.name() + " mode — lag expected soon..."));
                } else {
                    output.broadcast(Component.literal("📊 Geo-scan starting soon (" +
                            profile.name().toLowerCase() + " mode)"));
                }
                manager.scheduleScan(chunks, initiatorName, profile);
            }

            output.sendToAdmins(Component.literal("[GeoScan] " + (force ? "Force started" : "Scheduled")
                    + " by " + initiatorName + " | Profile: " + profile.name() + " | Chunks: " + chunks));
        } else {
            output.sendFailure(source, Component.literal("❌ GeoAnalysisManager is not initialized!")
                    .withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int executeStop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        var manager = AnalysisEngine.getInstance().getGeoManager();
        if (manager != null) {
            if (!manager.isScanning() && !manager.isCountdownActive()) {
                output.sendFailure(source, Component.literal("❌ No scan is currently running or scheduled.")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }

            output.sendInfo(source, Component.literal("🛑 Stopping geo-scan...")
                    .withStyle(ChatFormatting.YELLOW));

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
            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                    .withStyle(ChatFormatting.GOLD));
            output.sendInfo(source, Component.literal("📊 GEO-SCAN STATUS")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                    .withStyle(ChatFormatting.GOLD));
            output.sendInfo(source, Component.literal(""));

            ScanSession session = manager.getCurrentSession();

            if (session != null && session.isValid()) {
                renderActiveStatus(output, source, manager, session);
            } else if (manager.isCountdownActive()) {
                String status = manager.getStatus();
                output.sendInfo(source, Component.literal("  ⏳ ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(status).withStyle(ChatFormatting.YELLOW)));
            } else {
                String status = manager.getStatus();
                ChatFormatting color = status.toLowerCase().contains("complete")
                        ? ChatFormatting.GREEN : ChatFormatting.GRAY;
                String icon = status.toLowerCase().contains("complete") ? "✓" : "💤";

                output.sendInfo(source, Component.literal("  " + icon + " ").withStyle(color)
                        .append(Component.literal(status).withStyle(color)));
            }

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source, Component.literal("═══════════════════════════════════════")
                    .withStyle(ChatFormatting.DARK_GRAY));
            output.sendInfo(source, Component.literal(""));
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
        int totalThreads = ThreadPoolManager.getInstance().getParallelism();

        output.sendInfo(source, Component.literal("  Mode: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(icon + " " + profile.name()).withStyle(color, ChatFormatting.BOLD)));

        int activeWorkers = manager.getActiveWorkerCount();
        int targetWorkers = manager.getTargetWorkerCount();
        output.sendInfo(source, Component.literal("  Workers: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(activeWorkers + "/" + targetWorkers).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (of " + totalThreads + " available)")
                        .withStyle(ChatFormatting.DARK_GRAY)));

        long scanned = session.getTotalChunksScanned();
        int total = session.getTotalChunksNeeded();
        int percent = session.getProgressPercent();
        String progressBar = getProgressBar(percent);

        output.sendInfo(source, Component.literal("  Progress: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(progressBar)
                        .withStyle(percent >= 100 ? ChatFormatting.GREEN : ChatFormatting.AQUA)));

        output.sendInfo(source, Component.literal("  Chunks: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(scanned + "/" + total).withStyle(ChatFormatting.WHITE)));

        float speed = session.getScanSpeed();
        output.sendInfo(source, Component.literal("  Speed: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", speed) + " chunks/sec")
                        .withStyle(ChatFormatting.AQUA)));

        long elapsed = session.getElapsedSeconds();
        String timeStr = formatTime(elapsed);
        output.sendInfo(source, Component.literal("  Elapsed: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(timeStr)
                        .withStyle(ChatFormatting.WHITE)));

        if (profile.hasMsptLimit()) {
            float mspt = manager.getCurrentMspt();
            boolean throttled = manager.isThrottled();

            MutableComponent msptLine = Component.literal("  MSPT: ").withStyle(ChatFormatting.GRAY);

            if (throttled) {
                msptLine.append(Component.literal(String.format("%.1f", mspt)).withStyle(ChatFormatting.RED))
                        .append(Component.literal(" ⚠ THROTTLED")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            } else {
                ChatFormatting msptColor = mspt < profile.msptLimit * 0.7f ? ChatFormatting.GREEN :
                        mspt < profile.msptLimit ? ChatFormatting.YELLOW : ChatFormatting.RED;
                msptLine.append(Component.literal(String.format("%.1f", mspt)).withStyle(msptColor))
                        .append(Component.literal("/" + (int) profile.msptLimit)
                                .withStyle(ChatFormatting.DARK_GRAY));
            }

            output.sendInfo(source, msptLine);
        }

        output.sendInfo(source, Component.literal(""));

        MutableComponent biomeStatus = Component.literal("  📋 Biome Details ")
                .withStyle(ChatFormatting.AQUA);

        MutableComponent tooltip = buildBiomeTooltip(session);

        biomeStatus.append(Component.literal("[hover for details]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.DARK_AQUA).withItalic(true)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip))));

        output.sendInfo(source, biomeStatus);
    }

    private static MutableComponent buildBiomeTooltip(ScanSession session) {
        MutableComponent tooltip = Component.empty();

        tooltip.append(Component.literal("═══ BIOME PROGRESS ═══\n")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

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

                    if (remaining > 0) {
                        tooltip.append(Component.literal("\n... and " + remaining + " more biomes")
                                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                    }
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
                    biomeLine.append(Component.literal(" (" + scanned + "/" + needed + ")")
                            .withStyle(ChatFormatting.DARK_GREEN));
                } else {
                    int biomePercent = needed > 0 ? (scanned * 100 / needed) : 0;
                    ChatFormatting biomeColor = biomePercent >= 75 ? ChatFormatting.YELLOW :
                            biomePercent >= 50 ? ChatFormatting.GOLD : ChatFormatting.WHITE;

                    biomeLine.append(Component.literal("○ ").withStyle(ChatFormatting.GRAY));
                    biomeLine.append(Component.literal(biomeId.getPath()).withStyle(biomeColor));
                    biomeLine.append(Component.literal(" (" + scanned + "/" + needed + ")")
                            .withStyle(ChatFormatting.GRAY));
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
                output.sendFailure(source, Component.literal("⚠ Cannot clear database during active scan!")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                output.sendInfo(source, Component.literal("Use ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("/complexity geoscan stop").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" first").withStyle(ChatFormatting.GRAY)));
                return 0;
            } else {
                output.sendInfo(source, Component.literal("🗑 Clearing geo-database...")
                        .withStyle(ChatFormatting.YELLOW));

                engine.clearGeoDatabase();

                output.sendSuccess(source, Component.literal("✓ Geo-database has been cleared!")
                        .withStyle(ChatFormatting.GREEN));
                output.sendToAdmins(Component.literal("[GeoScan] Database cleared by " +
                        source.getTextName()));
            }
        } else {
            engine.clearGeoDatabase();
            output.sendSuccess(source, Component.literal("✓ Geo-database has been cleared!")
                    .withStyle(ChatFormatting.GREEN));
        }
        return 1;
    }

    private static String getProfileIcon(ScanProfile profile) {
        return switch (profile) {
            case QUARTER -> "🟢";
            case HALF -> "🟡";
            case MOST -> "🟠";
            case FULL -> "🔴";
        };
    }

    private static ChatFormatting getProfileColor(ScanProfile profile) {
        return switch (profile) {
            case QUARTER -> ChatFormatting.GREEN;
            case HALF -> ChatFormatting.YELLOW;
            case MOST -> ChatFormatting.GOLD;
            case FULL -> ChatFormatting.RED;
        };
    }

    private static String getProgressBar(int percent) {
        int filled = Math.min(10, percent / 10);
        return "[" + "██████████".substring(0, filled) + "░░░░░░░░░░".substring(filled) + "] " + percent + "%";
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