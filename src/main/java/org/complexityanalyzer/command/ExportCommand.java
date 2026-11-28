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

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.export.format.ComplexityExporter;
import org.complexityanalyzer.export.format.ExportFormats;
import org.complexityanalyzer.export.format.IExportFormat;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import static java.util.Locale.ROOT;
import static net.minecraft.ChatFormatting.*;
import static org.complexityanalyzer.util.LogMarkers.EXPORT_COMMAND;

public final class ExportCommand {

    private ExportCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("export")
                .requires(source -> source.hasPermission(2))
                .then(buildBranch("items", SharedSuggestions.EXPORT_CATEGORY, "item_id", SharedSuggestions.ITEM,
                        ExportCommand::executeAllItems,
                        ExportCommand::executeItemsByCategory,
                        ExportCommand::executeTopItems,
                        ExportCommand::executeSingleItem))
                .then(buildBranch("mobs", SharedSuggestions.EXPORT_MOB_CATEGORY, "mob_id", SharedSuggestions.ENTITY,
                        ExportCommand::executeAllMobs,
                        ExportCommand::executeMobsByCategory,
                        ExportCommand::executeTopMobs,
                        ExportCommand::executeSingleMob));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildBranch(String name, SuggestionProvider<CommandSourceStack> categorySuggestions,
                                                                      String idArg, SuggestionProvider<CommandSourceStack> idSuggestions,
                                                                      Command<CommandSourceStack> onAll, ArgCommand<String> onCategory,
                                                                      ArgCommand<Integer> onTop, ArgCommand<String> onSingle) {
        var category = Commands.argument("category_name", StringArgumentType.word()).suggests(categorySuggestions)
                .executes(ctx -> onCategory.run(ctx, StringArgumentType.getString(ctx, "category_name")));

        var top = Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                .executes(ctx -> onTop.run(ctx, IntegerArgumentType.getInteger(ctx, "count")));

        var single = Commands.argument(idArg, ResourceLocationArgument.id()).suggests(idSuggestions)
                .executes(ctx -> onSingle.run(ctx, ResourceLocationArgument.getId(ctx, idArg).toString()));

        var format = Commands.argument("format", StringArgumentType.word()).suggests(SharedSuggestions.EXPORT_FORMAT)
                .then(Commands.literal("all").executes(onAll))
                .then(Commands.literal("category").then(category))
                .then(Commands.literal("top").then(top))
                .then(Commands.literal("single").then(single));

        return Commands.literal(name).then(format);
    }

    public static int executeAllItems(CommandContext<CommandSourceStack> context) {
        return runAsyncExport(context, "💾", "complexityanalyzer.command.export.full_item_header", AQUA,
                null, "Full item export", ComplexityExporter::exportAllItems);
    }

    public static int executeItemsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        return runAsyncExport(context, "📦", "complexityanalyzer.command.export.item_category_header", GOLD,
                new ExportDetail("📋", "complexityanalyzer.command.export.category_label", categoryName, YELLOW),
                "Item category export", ComplexityExporter::exportItemsByCategory, categoryName);
    }

    public static int executeTopItems(CommandContext<CommandSourceStack> context, int count) {
        return runAsyncExport(context, "🏆", "complexityanalyzer.command.export.top_items_header", GOLD,
                new ExportDetail("🔢", "complexityanalyzer.command.export.count_label", count, AQUA),
                "Top items export", ComplexityExporter::exportTopItems, count);
    }

    public static int executeSingleItem(CommandContext<CommandSourceStack> context, String itemId) {
        return runAsyncExport(context, "📄", "complexityanalyzer.command.export.single_item_header", AQUA,
                new ExportDetail("🏷", "complexityanalyzer.command.export.item_label", itemId, WHITE),
                "Single item export", ComplexityExporter::exportSingleItem, itemId);
    }

    public static int executeAllMobs(CommandContext<CommandSourceStack> context) {
        var formatName = StringArgumentType.getString(context, "format").toUpperCase(ROOT);
        return runAsyncExport(context, "🧟", "complexityanalyzer.command.export.all_mobs_header", RED,
                new ExportDetail("📋", "complexityanalyzer.command.export.format_label", formatName, AQUA),
                "All mobs export", ComplexityExporter::exportAllMobs);
    }

    public static int executeMobsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        return runAsyncExport(context, "📦", "complexityanalyzer.command.export.mob_category_header", GOLD,
                new ExportDetail("📋", "complexityanalyzer.command.export.category_label", categoryName, YELLOW),
                "Mob category export", ComplexityExporter::exportMobsByCategory, categoryName);
    }

    public static int executeTopMobs(CommandContext<CommandSourceStack> context, int count) {
        return runAsyncExport(context, "🏆", "complexityanalyzer.command.export.top_mobs_header", GOLD,
                new ExportDetail("🔢", "complexityanalyzer.command.export.count_label", count, AQUA),
                "Top mobs export", ComplexityExporter::exportTopMobs, count);
    }

    public static int executeSingleMob(CommandContext<CommandSourceStack> context, String mobId) {
        return runAsyncExport(context, "📄", "complexityanalyzer.command.export.single_mob_header", AQUA,
                new ExportDetail("🧟", "complexityanalyzer.command.export.mob_label", mobId, WHITE),
                "Single mob export", ComplexityExporter::exportSingleMob, mobId);
    }

    private static <T> int runAsyncExport(CommandContext<CommandSourceStack> context, String icon, String headerKey,
                                          ChatFormatting headerColor, @Nullable ExportDetail detail,
                                          String exportType, ParameterizedExportAction<T> action, T arg) {
        return runAsyncExport(context, icon, headerKey, headerColor, detail, exportType,
                (server, engine, fmt) -> action.run(server, engine, fmt, arg));
    }

    private static int runAsyncExport(CommandContext<CommandSourceStack> context, String icon, String headerKey,
                                      ChatFormatting headerColor, @Nullable ExportDetail detail,
                                      String exportType, ExportAction action) {
        var source = context.getSource();
        var server = source.getServer();
        var output = new OutputManager(server);
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);

        var formatId = StringArgumentType.getString(context, "format");
        var format = ExportFormats.get(formatId);
        if (format == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.invalid_format", formatId));
            return 0;
        }

        output.sendEmptyLine(source);
        output.sendHeader(source, icon, headerKey, headerColor);
        output.sendEmptyLine(source);

        if (detail != null) {
            output.sendEntry(source, detail.icon(), detail.labelKey(), detail.value(), GRAY, detail.color());
        }

        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.starting", YELLOW);
        output.sendEmptyLine(source);

        var fullTypeDesc = "%s (%s)".formatted(exportType, format.getId().toUpperCase(ROOT));

        ThreadPoolManager.getInstance().getComputePool().execute(() -> {
            try {
                var exportPath = action.run(server, engine, format);
                server.execute(() -> sendSuccess(output, source, fullTypeDesc, exportPath));
            } catch (Exception e) {
                server.execute(() -> sendFailure(output, source, exportType, e));
            }
        });

        return 1;
    }

    private static int sendEngineNotReady(OutputManager output, CommandSourceStack source, AnalysisEngine engine) {
        output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.engine_not_ready"));
        output.sendTip(source, "complexityanalyzer.command.export.current_state", engine.getCurrentState().toString());
        return 0;
    }

    private static void sendSuccess(OutputManager output, CommandSourceStack source, String exportType, Path exportPath) {
        output.sendSuccess(source, Component.translatable("complexityanalyzer.command.export.success"));
        output.sendEmptyLine(source);
        output.sendEntry(source, "📁", "complexityanalyzer.command.export.file_label", exportPath.getFileName().toString(), GRAY, WHITE);
        output.sendFooter(source);
        output.sendToAdmins(Component.translatable("complexityanalyzer.command.export.completed_admin", exportType));
    }

    private static void sendFailure(OutputManager output, CommandSourceStack source, String exportType, Exception e) {
        var reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        output.sendFailure(source, Component.translatable("complexityanalyzer.command.export.failed", exportType, reason));
        output.sendFooter(source);
        ComplexityAnalyzer.LOGGER.error(EXPORT_COMMAND, "Error exporting {}", exportType, e);
    }

    @FunctionalInterface
    private interface ArgCommand<T> {
        int run(CommandContext<CommandSourceStack> context, T arg);
    }

    @FunctionalInterface
    private interface ExportAction {
        Path run(MinecraftServer server, AnalysisEngine engine, IExportFormat format) throws Exception;
    }

    @FunctionalInterface
    private interface ParameterizedExportAction<T> {
        Path run(MinecraftServer server, AnalysisEngine engine, IExportFormat format, T arg) throws Exception;
    }

    private record ExportDetail(String icon, String labelKey, Object value, ChatFormatting color) {
    }
}