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

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.world.entity.MobCategory;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.export.ComplexityExporter;

import java.util.Arrays;

import java.nio.file.Path;

public final class ExportCommand {
    private ExportCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("export").requires(source -> source.hasPermission(2))
                .then(Commands.literal("items").then(Commands.literal("all")
                        .executes(ExportCommand::executeAllItems)).then(Commands.literal("category").then(Commands.argument("category_name", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"Trivial", "Simple", "Moderate", "Complex", "Difficult", "Expert", "Master", "Mythical", "Transcendent", "Eternal"}, builder))
                        .executes(ctx -> ExportCommand.executeItemsByCategory(ctx, StringArgumentType.getString(ctx, "category_name"))))).then(Commands.literal("top").then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                        .executes(ctx -> ExportCommand.executeTopItems(ctx, IntegerArgumentType.getInteger(ctx, "count"))))).then(Commands.literal("single").then(Commands.argument("item_id", ResourceLocationArgument.id()).suggests(SharedSuggestions.ITEM)
                        .executes(ctx -> ExportCommand.executeSingleItem(ctx, ResourceLocationArgument.getId(ctx, "item_id").toString())))).then(Commands.literal("csv")
                        .executes(ExportCommand::executeItemsCSV)))
                .then(Commands.literal("mobs").then(Commands.literal("all")
                        .executes(ctx -> ExportCommand.executeAllMobs(ctx, "json")).then(Commands.literal("format").then(Commands.argument("format_type", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"csv", "json"}, builder))
                                .executes(ctx -> ExportCommand.executeAllMobs(ctx, StringArgumentType.getString(ctx, "format_type")))))).then(Commands.literal("category").then(Commands.argument("category_name", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(MobCategory.values()).map(MobCategory::getName), builder))
                        .executes(ctx -> ExportCommand.executeMobsByCategory(ctx, StringArgumentType.getString(ctx, "category_name"))))).then(Commands.literal("top").then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                        .executes(ctx -> ExportCommand.executeTopMobs(ctx, IntegerArgumentType.getInteger(ctx, "count"))))).then(Commands.literal("single").then(Commands.argument("mob_id", ResourceLocationArgument.id()).suggests(SharedSuggestions.ENTITY)
                        .executes(ctx -> ExportCommand.executeSingleMob(ctx, ResourceLocationArgument.getId(ctx, "mob_id").toString())))).then(Commands.literal("csv")
                        .executes(ctx -> ExportCommand.executeAllMobs(ctx, "csv"))));
    }

    public static int executeAllItems(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "💾", "FULL ITEM EXPORT", ChatFormatting.AQUA);
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "🔄", "Starting export process", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📦", "ITEM CATEGORY EXPORT", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "📋", "Category", categoryName, ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendStatusLine(source, "🔄", "Exporting", ChatFormatting.AQUA);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "🏆", "TOP ITEMS EXPORT", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🔢", "Count", String.valueOf(count), ChatFormatting.GRAY, ChatFormatting.AQUA);
        output.sendStatusLine(source, "🔄", "Calculating and exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "ITEMS CSV EXPORT", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "🔄", "Exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📄", "SINGLE ITEM EXPORT", ChatFormatting.AQUA);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🏷", "Item", itemId, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendStatusLine(source, "🔄", "Exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        if (!"csv".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
            output.sendFailure(source, Component.literal("Invalid format: " + format + ". Use 'csv' or 'json'."));
            return 0;
        }

        output.sendEmptyLine(source);
        output.sendHeader(source, "🧟", "ALL MOBS EXPORT", ChatFormatting.RED);
        output.sendEmptyLine(source);
        output.sendEntry(source, "📋", "Format", format.toUpperCase(), ChatFormatting.GRAY, ChatFormatting.AQUA);
        output.sendStatusLine(source, "🔄", "Exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📦", "MOB CATEGORY EXPORT", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "📋", "Category", categoryName, ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendStatusLine(source, "🔄", "Exporting", ChatFormatting.AQUA);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "🏆", "TOP MOBS EXPORT", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🔢", "Count", String.valueOf(count), ChatFormatting.GRAY, ChatFormatting.AQUA);
        output.sendStatusLine(source, "🔄", "Calculating and exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

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
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📄", "SINGLE MOB EXPORT", ChatFormatting.AQUA);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🧟", "Mob", mobId, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendStatusLine(source, "🔄", "Exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

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
        output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready yet!"));
        output.sendTip(source, "Current state: " + engine.getCurrentState());
        return 0;
    }

    private static void sendSuccess(OutputManager output, CommandSourceStack source, String exportType, Path exportPath) {
        output.sendSuccess(source, Component.literal("✓ Export successful!"));
        output.sendEmptyLine(source);
        output.sendEntry(source, "📁", "File", exportPath.getFileName().toString(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendFooter(source);
        output.sendToAdmins(Component.literal("✓ " + exportType + " completed."));
    }

    private static void sendFailure(OutputManager output, CommandSourceStack source, String exportType, Exception e) {
        output.sendFailure(source, Component.literal("❌ " + exportType + " export failed: " + e.getMessage()));
        output.sendFooter(source);
        ComplexityAnalyzer.LOGGER.error("Error exporting {}", exportType, e);
    }
}