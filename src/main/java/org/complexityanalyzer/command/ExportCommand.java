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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.MobCategory;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.export.ComplexityExporter;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static java.util.Locale.ROOT;

public final class ExportCommand {
    private ExportCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("export")
                .requires(source -> source.hasPermission(2))
                .then(buildItemsBranch())
                .then(buildMobsBranch());
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildItemsBranch() {
        return Commands.literal("items")
                .then(Commands.literal("all")
                        .executes(ExportCommand::executeAllItems))
                .then(Commands.literal("category")
                        .then(Commands.argument("category_name", StringArgumentType.word())
                                .suggests(ExportCommand::suggestCategories)
                                .executes(ctx -> executeItemsByCategory(ctx, StringArgumentType.getString(ctx, "category_name")))))
                .then(Commands.literal("top")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> executeTopItems(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("single")
                        .then(Commands.argument("item_id", ResourceLocationArgument.id())
                                .suggests(SharedSuggestions.ITEM)
                                .executes(ctx -> executeSingleItem(ctx, ResourceLocationArgument.getId(ctx, "item_id").toString()))))
                .then(Commands.literal("csv")
                        .executes(ExportCommand::executeItemsCSV));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildMobsBranch() {
        return Commands.literal("mobs")
                .then(Commands.literal("all")
                        .executes(ctx -> executeAllMobs(ctx, "json")))
                .then(Commands.literal("format")
                        .then(Commands.argument("format_type", StringArgumentType.word())
                                .suggests(ExportCommand::suggestFormats)
                                .executes(ctx -> executeAllMobs(ctx, StringArgumentType.getString(ctx, "format_type")))))
                .then(Commands.literal("category")
                        .then(Commands.argument("category_name", StringArgumentType.word())
                                .suggests(ExportCommand::suggestMobCategories)
                                .executes(ctx -> executeMobsByCategory(ctx, StringArgumentType.getString(ctx, "category_name")))))
                .then(Commands.literal("top")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> executeTopMobs(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("single")
                        .then(Commands.argument("mob_id", ResourceLocationArgument.id())
                                .suggests(SharedSuggestions.ENTITY)
                                .executes(ctx -> executeSingleMob(ctx, ResourceLocationArgument.getId(ctx, "mob_id").toString()))))
                .then(Commands.literal("csv")
                        .executes(ctx -> executeAllMobs(ctx, "csv")));
    }

    private static CompletableFuture<Suggestions> suggestCategories(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        var catList = new ObjectArrayList<String>();
        for (var category : ComplexityCategory.values()) {
            if (category != ComplexityCategory.UNCALCULABLE && category != ComplexityCategory.ABSOLUTE) {
                catList.add(category.getDisplayName());
            }
        }
        return SharedSuggestionProvider.suggest(catList, builder);
    }

    private static CompletableFuture<Suggestions> suggestMobCategories(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        var mobCategories = MobCategory.values();
        var list = new ObjectArrayList<String>(mobCategories.length);
        for (var mobCategory : mobCategories) list.add(mobCategory.getName());
        return SharedSuggestionProvider.suggest(list, builder);
    }

    private static CompletableFuture<Suggestions> suggestFormats(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(new String[]{"csv", "json"}, builder);
    }

    public static int executeAllItems(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "💾", "complexityanalyzer.command.export.full_item_header", ChatFormatting.AQUA);
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.starting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportAllItems(source.getServer(), engine);
            sendSuccess(output, source, "Full item export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "full item", e);
            return 0;
        }
    }

    public static int executeItemsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📦", "complexityanalyzer.command.export.item_category_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "📋", "complexityanalyzer.command.export.category_label", categoryName, ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.exporting", ChatFormatting.AQUA);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportItemsByCategory(source.getServer(), engine, categoryName);
            sendSuccess(output, source, "Item category export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "item category", e);
            return 0;
        }
    }

    public static int executeTopItems(CommandContext<CommandSourceStack> context, int count) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "🏆", "complexityanalyzer.command.export.top_items_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🔢", "complexityanalyzer.command.export.count_label", String.valueOf(count), ChatFormatting.GRAY, ChatFormatting.AQUA);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.calculating", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportTopItems(source.getServer(), engine, count);
            sendSuccess(output, source, "Top items export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "top items", e);
            return 0;
        }
    }

    public static int executeItemsCSV(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.export.items_csv_header", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportItemsCSV(source.getServer(), engine);
            sendSuccess(output, source, "Items CSV export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "items CSV", e);
            return 0;
        }
    }

    public static int executeSingleItem(CommandContext<CommandSourceStack> context, String itemId) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📄", "complexityanalyzer.command.export.single_item_header", ChatFormatting.AQUA);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🏷", "complexityanalyzer.command.export.item_label", itemId, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportSingleItem(source.getServer(), engine, itemId);
            sendSuccess(output, source, "Single item export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "single item", e);
            return 0;
        }
    }

    public static int executeAllMobs(CommandContext<CommandSourceStack> context, String format) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        if (!"csv".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.invalid_format", format));
            return 0;
        }

        output.sendEmptyLine(source);
        output.sendHeader(source, "🧟", "complexityanalyzer.command.export.all_mobs_header", ChatFormatting.RED);
        output.sendEmptyLine(source);
        output.sendEntry(source, "📋", "complexityanalyzer.command.export.format_label", format.toUpperCase(ROOT), ChatFormatting.GRAY, ChatFormatting.AQUA);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportAllMobs(source.getServer(), engine, format);
            sendSuccess(output, source, "All mobs export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "all mobs", e);
            return 0;
        }
    }

    public static int executeMobsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📦", "complexityanalyzer.command.export.mob_category_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "📋", "complexityanalyzer.command.export.category_label", categoryName, ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.exporting", ChatFormatting.AQUA);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportMobsByCategory(source.getServer(), engine, categoryName);
            sendSuccess(output, source, "Mob category export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "mob category", e);
            return 0;
        }
    }

    public static int executeTopMobs(CommandContext<CommandSourceStack> context, int count) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "🏆", "complexityanalyzer.command.export.top_mobs_header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🔢", "complexityanalyzer.command.export.count_label", String.valueOf(count), ChatFormatting.GRAY, ChatFormatting.AQUA);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.calculating", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportTopMobs(source.getServer(), engine, count);
            sendSuccess(output, source, "Top mobs export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "top mobs", e);
            return 0;
        }
    }

    public static int executeSingleMob(CommandContext<CommandSourceStack> context, String mobId) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        output.sendEmptyLine(source);
        output.sendHeader(source, "📄", "complexityanalyzer.command.export.single_mob_header", ChatFormatting.AQUA);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🧟", "complexityanalyzer.command.export.mob_label", mobId, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.exporting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        try {
            var exportPath = ComplexityExporter.exportSingleMob(source.getServer(), engine, mobId);
            sendSuccess(output, source, "Single mob export", exportPath);
            return 1;
        } catch (Exception e) {
            sendFailure(output, source, "single mob", e);
            return 0;
        }
    }

    private static int sendEngineNotReady(OutputManager output, CommandSourceStack source, AnalysisEngine engine) {
        output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.engine_not_ready"));
        output.sendTip(source, "complexityanalyzer.command.export.current_state", engine.getCurrentState().toString());
        return 0;
    }

    private static void sendSuccess(OutputManager output, CommandSourceStack source, String exportType, Path exportPath) {
        output.sendSuccess(source, Component.translatable("complexityanalyzer.command.export.success"));
        output.sendEmptyLine(source);
        output.sendEntry(source, "📁", "complexityanalyzer.command.export.file_label", exportPath.getFileName().toString(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendFooter(source);
        output.sendToAdmins(Component.translatable("complexityanalyzer.command.export.completed_admin", exportType));
    }

    private static void sendFailure(OutputManager output, CommandSourceStack source, String exportType, Exception e) {
        output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.failed", exportType, e.getMessage()));
        output.sendFooter(source);
        ComplexityAnalyzer.LOGGER.error("Error exporting {}", exportType, e);
    }
}