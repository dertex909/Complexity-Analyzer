package org.complexityanalyzer.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
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
        final GeoAnalysisManager.ScanProfile profile;
        try {
            profile = GeoAnalysisManager.ScanProfile.valueOf(profileName.toUpperCase());
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal("§cUnknown profile: " + profileName + ". Available: lite, fast, extreme, atomic."));
            return 0;
        }

        AnalysisEngine.getInstance().getGeoManager().ifPresentOrElse(
                manager -> {
                    if (manager.isScanning() || manager.isCountdownActive()) {
                        context.getSource().sendFailure(Component.literal("§cA scan or countdown is already in progress."));
                        return;
                    }

                    String initiatorName = context.getSource().getDisplayName().getString();

                    if (force) {
                        manager.startScanImmediately(chunks, initiatorName, profile);
                        context.getSource().sendSuccess(() -> Component.literal("§aForce-starting geo-scan with profile '" + profileName + "'!"), true);
                    } else {
                        manager.scheduleScan(chunks, initiatorName, profile);
                        context.getSource().sendSuccess(() -> Component.literal("§aScan scheduled with profile '" + profileName + "'! Countdown initiated."), true);
                    }
                },
                () -> context.getSource().sendFailure(Component.literal("§cGeoAnalysisManager is not initialized."))
        );
        return 1;
    }

    private static int executeStop(CommandContext<CommandSourceStack> context) {
        AnalysisEngine.getInstance().getGeoManager().ifPresentOrElse(
                manager -> manager.stopScan(context.getSource()),
                () -> context.getSource().sendFailure(Component.literal("§cGeoAnalysisManager is not initialized."))
        );
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        AnalysisEngine.getInstance().getGeoManager().ifPresentOrElse(
                manager -> context.getSource().sendSuccess(() -> Component.literal("§aGeo-scan status: §f" + manager.getStatus()), false),
                () -> context.getSource().sendFailure(Component.literal("§cGeoAnalysisManager is not initialized."))
        );
        return 1;
    }

    private static int executeClear(CommandContext<CommandSourceStack> context) {
        AnalysisEngine engine = AnalysisEngine.getInstance();
        engine.getGeoManager().ifPresentOrElse(
                manager -> {
                    if (manager.isScanning() || manager.isCountdownActive()) {
                        context.getSource().sendFailure(Component.literal("§cCannot clear database while a scan is in progress. Use /chunk stop first."));
                    } else {
                        engine.clearGeoDatabase();
                        context.getSource().sendSuccess(() -> Component.literal("§aGeo-database has been cleared."), true);
                    }
                },
                () -> {
                    engine.clearGeoDatabase();
                    context.getSource().sendSuccess(() -> Component.literal("§aGeo-database has been cleared."), true);
                }
        );
        return 1;
    }
}