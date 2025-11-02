package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.ComplexityExporter;

import java.nio.file.Path;

public class ExportCommand {

    public static int executeAllItems(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("💾 ").withStyle(ChatFormatting.AQUA).append(Component.literal("FULL ITEM EXPORT").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  🔄 Starting export process...").withStyle(ChatFormatting.YELLOW));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportAllItems(source.getServer(), engine);
            sendSuccess(output, source, "Full item export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "full item", e);
            return 0;
        }
    }

    public static int executeItemsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("📦 ").withStyle(ChatFormatting.GOLD).append(Component.literal("ITEM CATEGORY EXPORT").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  📋 Category: ").withStyle(ChatFormatting.GRAY).append(Component.literal(categoryName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("  🔄 Exporting...").withStyle(ChatFormatting.AQUA));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportItemsByCategory(source.getServer(), engine, categoryName);
            sendSuccess(output, source, "Item category export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "item category", e);
            return 0;
        }
    }

    public static int executeTopItems(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("🏆 ").withStyle(ChatFormatting.GOLD).append(Component.literal("TOP ITEMS EXPORT").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  🔢 Count: ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("  🔄 Calculating and exporting...").withStyle(ChatFormatting.YELLOW));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportTopItems(source.getServer(), engine, count);
            sendSuccess(output, source, "Top items export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "top items", e);
            return 0;
        }
    }

    public static int executeItemsCSV(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("📊 ").withStyle(ChatFormatting.GREEN).append(Component.literal("ITEMS CSV EXPORT").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  🔄 Exporting...").withStyle(ChatFormatting.YELLOW));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportItemsCSV(source.getServer(), engine);
            sendSuccess(output, source, "Items CSV export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "items CSV", e);
            return 0;
        }
    }

    public static int executeSingleItem(CommandContext<CommandSourceStack> context, String itemId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("📄 ").withStyle(ChatFormatting.AQUA).append(Component.literal("SINGLE ITEM EXPORT").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  🏷 Item: ").withStyle(ChatFormatting.GRAY).append(Component.literal(itemId).withStyle(ChatFormatting.WHITE)));
        output.sendInfo(source, Component.literal("  🔄 Exporting...").withStyle(ChatFormatting.YELLOW));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportSingleItem(source.getServer(), engine, itemId);
            sendSuccess(output, source, "Single item export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "single item", e);
            return 0;
        }
    }

    public static int executeAllMobs(CommandContext<CommandSourceStack> context, String format) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        if (!"csv".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
            output.sendFailure(source, Component.literal("Invalid format: " + format + ". Use 'csv' or 'json'."));
            return 0;
        }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("🧟 ").withStyle(ChatFormatting.RED).append(Component.literal("ALL MOBS EXPORT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  📋 Format: ").withStyle(ChatFormatting.GRAY).append(Component.literal(format.toUpperCase()).withStyle(ChatFormatting.AQUA)));
        output.sendInfo(source, Component.literal("  🔄 Exporting...").withStyle(ChatFormatting.YELLOW));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportAllMobs(source.getServer(), engine, format);
            sendSuccess(output, source, "All mobs export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "all mobs", e);
            return 0;
        }
    }

    public static int executeMobsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("📦 ").withStyle(ChatFormatting.GOLD).append(Component.literal("MOB CATEGORY EXPORT").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  📋 Category: ").withStyle(ChatFormatting.GRAY).append(Component.literal(categoryName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("  🔄 Exporting...").withStyle(ChatFormatting.AQUA));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportMobsByCategory(source.getServer(), engine, categoryName);
            sendSuccess(output, source, "Mob category export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "mob category", e);
            return 0;
        }
    }

    public static int executeTopMobs(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("🏆 ").withStyle(ChatFormatting.GOLD).append(Component.literal("TOP MOBS EXPORT").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  🔢 Count: ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("  🔄 Calculating and exporting...").withStyle(ChatFormatting.YELLOW));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportTopMobs(source.getServer(), engine, count);
            sendSuccess(output, source, "Top mobs export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "top mobs", e);
            return 0;
        }
    }

    public static int executeSingleMob(CommandContext<CommandSourceStack> context, String mobId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) { return sendEngineNotReady(output, source, engine); }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal("📄 ").withStyle(ChatFormatting.AQUA).append(Component.literal("SINGLE MOB EXPORT").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  🧟 Mob: ").withStyle(ChatFormatting.GRAY).append(Component.literal(mobId).withStyle(ChatFormatting.WHITE)));
        output.sendInfo(source, Component.literal("  🔄 Exporting...").withStyle(ChatFormatting.YELLOW));
        output.sendInfo(source, Component.literal(""));

        try {
            Path exportPath = ComplexityExporter.exportSingleMob(source.getServer(), engine, mobId);
            sendSuccess(output, source, "Single mob export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "single mob", e);
            return 0;
        }
    }


    private static int sendEngineNotReady(OutputManager output, CommandSourceStack source, AnalysisEngine engine) {
        output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready yet!").withStyle(ChatFormatting.RED));
        output.sendInfo(source, Component.literal("Current state: " + engine.getCurrentState()).withStyle(ChatFormatting.GRAY));
        return 0;
    }

    private static void sendSuccess(OutputManager output, CommandSourceStack source, String exportType, Path exportPath) {
        output.sendInfo(source, Component.literal("  ✓ Export successful!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("  📁 File: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(exportPath.getFileName().toString()).withStyle(ChatFormatting.WHITE)));
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        output.sendToAdmins(Component.literal("✓ " + exportType + " completed."));
    }

    private static void sendFailure(OutputManager output, CommandSourceStack source, String exportType, Exception e) {
        output.sendFailure(source, Component.literal("❌ " + exportType + " export failed: " + e.getMessage()));
        output.sendInfo(source, Component.literal("═══════════════════════════════").withStyle(ChatFormatting.DARK_GRAY));
        ComplexityAnalyzer.LOGGER.error("Error exporting {}", exportType, e);
    }
}