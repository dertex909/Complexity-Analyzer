package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.ComplexityExporter;

import java.nio.file.Path;

public class ExportCommand {

    public static int executeAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet! Current state: " + engine.getCurrentState()));
            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("§e[Export] Starting full complexity export..."), true);

        try {
            Path exportPath = ComplexityExporter.exportAll(server, engine);

            source.sendSuccess(() -> Component.literal("§a[Export] ✓ Successfully exported all items!"), true);
            source.sendSuccess(() -> Component.literal("§7Path: §f" + exportPath), false);

            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[Export] Failed to export: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error during export", e);
            return 0;
        }
    }

    public static int executeCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("§e[Export] Exporting category: " + categoryName), false);

        try {
            Path exportPath = ComplexityExporter.exportCategory(server, engine, categoryName);

            source.sendSuccess(() -> Component.literal("§a[Export] ✓ Successfully exported!"), true);
            source.sendSuccess(() -> Component.literal("§7File: §f" + exportPath.getFileName()), false);

            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[Export] Failed: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error exporting category {}", categoryName, e);
            return 0;
        }
    }

    public static int executeTop(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("§e[Export] Exporting top " + count + " most complex items..."), false);

        try {
            Path exportPath = ComplexityExporter.exportTop(server, engine, count);

            source.sendSuccess(() -> Component.literal("§a[Export] ✓ Successfully exported!"), true);
            source.sendSuccess(() -> Component.literal("§7File: §f" + exportPath.getFileName()), false);

            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[Export] Failed: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error exporting top {}", count, e);
            return 0;
        }
    }

    public static int executeCSV(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("§e[Export] Exporting to CSV format..."), false);

        try {
            Path exportPath = ComplexityExporter.exportCSV(server, engine);

            source.sendSuccess(() -> Component.literal("§a[Export] ✓ Successfully exported to CSV!"), true);
            source.sendSuccess(() -> Component.literal("§7File: §f" + exportPath.getFileName()), false);

            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[Export] Failed: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error exporting CSV", e);
            return 0;
        }
    }

    public static int executeSingle(CommandContext<CommandSourceStack> context, String itemId) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("§e[Export] Exporting item: " + itemId), false);

        try {
            Path exportPath = ComplexityExporter.exportSingle(server, engine, itemId);

            source.sendSuccess(() -> Component.literal("§a[Export] ✓ Successfully exported!"), true);
            source.sendSuccess(() -> Component.literal("§7File: §f" + exportPath.getFileName()), false);

            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[Export] Failed: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error exporting item {}", itemId, e);
            return 0;
        }
    }
}