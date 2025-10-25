package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.ComplexityExporter;

import java.nio.file.Path;

public class ExportCommand {

    public static int executeAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready yet!")
                            .withStyle(ChatFormatting.RED));
            output.sendInfo(source,
                    Component.literal("Current state: " + engine.getCurrentState())
                            .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        MinecraftServer server = source.getServer();
        String adminName = source.getTextName();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("💾 ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("FULL COMPLEXITY EXPORT")
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        var stats = engine.getStats();
        output.sendInfo(source,
                Component.literal("  📊 Items to export: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.itemCount()))
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("  🔄 Starting export process...")
                        .withStyle(ChatFormatting.YELLOW));

        output.sendInfo(source, Component.literal(""));

        output.sendToAdmins(
                Component.literal("Full export initiated by " + adminName +
                        " (" + stats.itemCount() + " items)"));

        try {
            Path exportPath = ComplexityExporter.exportAll(server, engine);

            output.sendInfo(source,
                    Component.literal("  ✓ Export completed successfully!")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

            output.sendInfo(source, Component.literal(""));

            output.sendInfo(source,
                    Component.literal("  📁 File Information:")
                            .withStyle(ChatFormatting.AQUA));

            output.sendInfo(source,
                    Component.literal("    Name: ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.literal(exportPath.getFileName().toString())
                                    .withStyle(ChatFormatting.WHITE)));

            output.sendInfo(source,
                    Component.literal("    Path: ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.literal(exportPath.toString())
                                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendToAdmins(
                    Component.literal("✓ Export completed: " + exportPath.getFileName()));

            return 1;

        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ Export failed!")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

            output.sendInfo(source,
                    Component.literal("  Error: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendToAdmins(
                    Component.literal("⚠ Export FAILED: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));

            ComplexityAnalyzer.LOGGER.error("Error during full export", e);
            return 0;
        }
    }

    public static int executeCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        String adminName = source.getTextName();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("📦 ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("CATEGORY EXPORT")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  📋 Category: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(categoryName)
                                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("  🔄 Exporting...")
                        .withStyle(ChatFormatting.AQUA));

        output.sendInfo(source, Component.literal(""));

        output.sendToAdmins(
                Component.literal("Category export started: " + categoryName + " by " + adminName));

        try {
            Path exportPath = ComplexityExporter.exportCategory(server, engine, categoryName);

            output.sendInfo(source,
                    Component.literal("  ✓ Export successful!")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

            output.sendInfo(source, Component.literal(""));

            output.sendInfo(source,
                    Component.literal("  📁 File: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(exportPath.getFileName().toString())
                                    .withStyle(ChatFormatting.WHITE)));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendToAdmins(
                    Component.literal("✓ Category export done: " + categoryName));

            return 1;

        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ Export failed: " + e.getMessage()));

            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendToAdmins(
                    Component.literal("⚠ Category export failed: " + categoryName));

            ComplexityAnalyzer.LOGGER.error("Error exporting category {}", categoryName, e);
            return 0;
        }
    }

    public static int executeTop(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        String adminName = source.getTextName();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("🏆 ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("TOP ITEMS EXPORT")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  🔢 Count: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(count))
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                        .append(Component.literal(" most complex items")
                                .withStyle(ChatFormatting.GRAY)));

        output.sendInfo(source,
                Component.literal("  🔄 Calculating and exporting...")
                        .withStyle(ChatFormatting.YELLOW));

        output.sendInfo(source, Component.literal(""));

        output.sendToAdmins(
                Component.literal("Top " + count + " export by " + adminName));

        try {
            Path exportPath = ComplexityExporter.exportTop(server, engine, count);

            output.sendInfo(source,
                    Component.literal("  ✓ Export successful!")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

            output.sendInfo(source, Component.literal(""));

            output.sendInfo(source,
                    Component.literal("  📁 File: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(exportPath.getFileName().toString())
                                    .withStyle(ChatFormatting.WHITE)));

            output.sendInfo(source,
                    Component.literal("  💡 Top " + count + " most complex items exported")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            return 1;

        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ Export failed: " + e.getMessage()));

            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            ComplexityAnalyzer.LOGGER.error("Error exporting top {}", count, e);
            return 0;
        }
    }

    public static int executeCSV(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        String adminName = source.getTextName();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("📊 ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("CSV EXPORT")
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        var stats = engine.getStats();
        output.sendInfo(source,
                Component.literal("  📋 Format: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("CSV (Comma-Separated Values)")
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source,
                Component.literal("  📊 Items: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.itemCount()))
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("  🔄 Exporting...")
                        .withStyle(ChatFormatting.YELLOW));

        output.sendInfo(source, Component.literal(""));

        output.sendToAdmins(
                Component.literal("CSV export initiated by " + adminName));

        try {
            Path exportPath = ComplexityExporter.exportCSV(server, engine);

            output.sendInfo(source,
                    Component.literal("  ✓ CSV export successful!")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

            output.sendInfo(source, Component.literal(""));

            output.sendInfo(source,
                    Component.literal("  📁 File: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(exportPath.getFileName().toString())
                                    .withStyle(ChatFormatting.WHITE)));

            output.sendInfo(source,
                    Component.literal("  💡 Use spreadsheet software to open")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendToAdmins(
                    Component.literal("✓ CSV export completed"));

            return 1;

        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ CSV export failed: " + e.getMessage()));

            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            ComplexityAnalyzer.LOGGER.error("Error exporting CSV", e);
            return 0;
        }
    }

    public static int executeSingle(CommandContext<CommandSourceStack> context, String itemId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready yet!"));
            return 0;
        }

        MinecraftServer server = source.getServer();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("📄 ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("SINGLE ITEM EXPORT")
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  🏷 Item: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(itemId)
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source,
                Component.literal("  🔄 Exporting...")
                        .withStyle(ChatFormatting.YELLOW));

        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportSingle(server, engine, itemId);

            output.sendInfo(source,
                    Component.literal("  ✓ Export successful!")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

            output.sendInfo(source, Component.literal(""));

            output.sendInfo(source,
                    Component.literal("  📁 File: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(exportPath.getFileName().toString())
                                    .withStyle(ChatFormatting.WHITE)));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            return 1;

        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ Export failed: " + e.getMessage()));

            output.sendInfo(source,
                    Component.literal("  Item might not exist or has no complexity data")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("═══════════════════════════════")
                            .withStyle(ChatFormatting.DARK_GRAY));

            ComplexityAnalyzer.LOGGER.error("Error exporting item {}", itemId, e);
            return 0;
        }
    }
}