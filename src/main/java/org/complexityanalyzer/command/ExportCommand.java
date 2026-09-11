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
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
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
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.export.format.ComplexityExporter;
import org.complexityanalyzer.export.format.ExportFormats;
import org.complexityanalyzer.export.format.IExportFormat;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static java.util.Locale.ROOT;

public final class ExportCommand {

    private static final ObjectList<String> CATEGORY_SUGGESTIONS;
    private static final ObjectList<String> MOB_CATEGORY_SUGGESTIONS;

    static {
        var catList = new ObjectArrayList<String>();
        for (var category : ComplexityCategory.values()) catList.add(category.getDisplayName());
        CATEGORY_SUGGESTIONS = ObjectLists.unmodifiable(catList);
        var mobCategories = MobCategory.values();
        var mobList = new ObjectArrayList<String>(mobCategories.length);
        for (var mobCategory : mobCategories) mobList.add(mobCategory.getName());
        MOB_CATEGORY_SUGGESTIONS = ObjectLists.unmodifiable(mobList);
    }

    private ExportCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("export").requires(source -> source.hasPermission(2))
                .then(buildItemsBranch())
                .then(buildMobsBranch());
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildItemsBranch() {
        return Commands.literal("items")
                .then(Commands.argument("format", StringArgumentType.word()).suggests(ExportCommand::suggestFormats)
                        .then(Commands.literal("all").executes(ExportCommand::executeAllItems))
                        .then(Commands.literal("category")
                                .then(Commands.argument("category_name", StringArgumentType.word()).suggests(ExportCommand::suggestCategories).executes(ctx -> executeItemsByCategory(ctx, StringArgumentType.getString(ctx, "category_name")))))
                        .then(Commands.literal("top")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000)).executes(ctx -> executeTopItems(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(Commands.literal("single")
                                .then(Commands.argument("item_id", ResourceLocationArgument.id()).suggests(SharedSuggestions.ITEM).executes(ctx -> executeSingleItem(ctx, ResourceLocationArgument.getId(ctx, "item_id").toString())))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildMobsBranch() {
        return Commands.literal("mobs")
                .then(Commands.argument("format", StringArgumentType.word()).suggests(ExportCommand::suggestFormats)
                        .then(Commands.literal("all").executes(ExportCommand::executeAllMobs))
                        .then(Commands.literal("category")
                                .then(Commands.argument("category_name", StringArgumentType.word()).suggests(ExportCommand::suggestMobCategories).executes(ctx -> executeMobsByCategory(ctx, StringArgumentType.getString(ctx, "category_name")))))
                        .then(Commands.literal("top")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000)).executes(ctx -> executeTopMobs(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(Commands.literal("single")
                                .then(Commands.argument("mob_id", ResourceLocationArgument.id()).suggests(SharedSuggestions.ENTITY).executes(ctx -> executeSingleMob(ctx, ResourceLocationArgument.getId(ctx, "mob_id").toString())))));
    }

    private static CompletableFuture<Suggestions> suggestFormats(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ExportFormats.getAvailableFormatIds(), builder);
    }

    private static CompletableFuture<Suggestions> suggestCategories(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(CATEGORY_SUGGESTIONS, builder);
    }

    private static CompletableFuture<Suggestions> suggestMobCategories(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(MOB_CATEGORY_SUGGESTIONS, builder);
    }

    private static IExportFormat getFormatOrReport(CommandContext<CommandSourceStack> context, OutputManager output) {
        String formatId = StringArgumentType.getString(context, "format");
        var format = ExportFormats.get(formatId);
        if (format == null) output.sendFailure(context.getSource(),
                Component.translatable("complexityanalyzer.command.export.invalid_format", formatId));
        return format;
    }

    private static int runAsyncExport(CommandContext<CommandSourceStack> context, String icon, String headerKey,
                                      ChatFormatting headerColor, @Nullable Consumer<OutputManager> extraDetails,
                                      String exportType, ExportAction action) {
        var source = context.getSource();
        var server = source.getServer();
        var output = new OutputManager(server);
        var engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return sendEngineNotReady(output, source, engine);
        var format = getFormatOrReport(context, output);
        if (format == null) return 0;
        output.sendEmptyLine(source);
        output.sendHeader(source, icon, headerKey, headerColor);
        output.sendEmptyLine(source);
        if (extraDetails != null) extraDetails.accept(output);
        output.sendStatusLine(source, "🔄", "complexityanalyzer.command.export.starting", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);
        String fullTypeDesc = "%s (%s)".formatted(exportType, format.getId().toUpperCase(ROOT));

        ThreadPoolManager.getInstance().getComputePool().execute(() -> {
            try {
                var exportPath = action.run(format);
                server.execute(() -> sendSuccess(output, source, fullTypeDesc, exportPath));
            } catch (Exception e) {
                server.execute(() -> sendFailure(output, source, exportType, e));
            }
        });

        return 1;
    }

    public static int executeAllItems(CommandContext<CommandSourceStack> context) {
        return runAsyncExport(context, "💾", "complexityanalyzer.command.export.full_item_header", ChatFormatting.AQUA,
                null, "Full item export",
                format -> ComplexityExporter.exportAllItems(context.getSource().getServer(), AnalysisEngine.getInstance(), format));
    }

    public static int executeItemsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        var source = context.getSource();
        return runAsyncExport(context, "📦", "complexityanalyzer.command.export.item_category_header", ChatFormatting.GOLD,
                out -> out.sendEntry(source, "📋", "complexityanalyzer.command.export.category_label", categoryName, ChatFormatting.GRAY, ChatFormatting.YELLOW),
                "Item category export",
                format -> ComplexityExporter.exportItemsByCategory(source.getServer(), AnalysisEngine.getInstance(), format, categoryName));
    }

    public static int executeTopItems(CommandContext<CommandSourceStack> context, int count) {
        var source = context.getSource();
        return runAsyncExport(context, "🏆", "complexityanalyzer.command.export.top_items_header", ChatFormatting.GOLD,
                out -> out.sendEntry(source, "🔢", "complexityanalyzer.command.export.count_label", String.valueOf(count), ChatFormatting.GRAY, ChatFormatting.AQUA),
                "Top items export",
                format -> ComplexityExporter.exportTopItems(source.getServer(), AnalysisEngine.getInstance(), format, count));
    }

    public static int executeSingleItem(CommandContext<CommandSourceStack> context, String itemId) {
        var source = context.getSource();
        return runAsyncExport(context, "📄", "complexityanalyzer.command.export.single_item_header", ChatFormatting.AQUA,
                out -> out.sendEntry(source, "🏷", "complexityanalyzer.command.export.item_label", itemId, ChatFormatting.GRAY, ChatFormatting.WHITE),
                "Single item export",
                format -> ComplexityExporter.exportSingleItem(source.getServer(), AnalysisEngine.getInstance(), format, itemId));
    }

    public static int executeAllMobs(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        return runAsyncExport(context, "🧟", "complexityanalyzer.command.export.all_mobs_header", ChatFormatting.RED,
                out -> {
                    String formatId = StringArgumentType.getString(context, "format");
                    out.sendEntry(source, "📋", "complexityanalyzer.command.export.format_label", formatId.toUpperCase(ROOT), ChatFormatting.GRAY, ChatFormatting.AQUA);
                },
                "All mobs export",
                format -> ComplexityExporter.exportAllMobs(source.getServer(), AnalysisEngine.getInstance(), format));
    }

    public static int executeMobsByCategory(CommandContext<CommandSourceStack> context, String categoryName) {
        var source = context.getSource();
        return runAsyncExport(context, "📦", "complexityanalyzer.command.export.mob_category_header", ChatFormatting.GOLD,
                out -> out.sendEntry(source, "📋", "complexityanalyzer.command.export.category_label", categoryName, ChatFormatting.GRAY, ChatFormatting.YELLOW),
                "Mob category export",
                format -> ComplexityExporter.exportMobsByCategory(source.getServer(), AnalysisEngine.getInstance(), format, categoryName));
    }

    public static int executeTopMobs(CommandContext<CommandSourceStack> context, int count) {
        var source = context.getSource();
        return runAsyncExport(context, "🏆", "complexityanalyzer.command.export.top_mobs_header", ChatFormatting.GOLD,
                out -> out.sendEntry(source, "🔢", "complexityanalyzer.command.export.count_label", String.valueOf(count), ChatFormatting.GRAY, ChatFormatting.AQUA),
                "Top mobs export",
                format -> ComplexityExporter.exportTopMobs(source.getServer(), AnalysisEngine.getInstance(), format, count));
    }

    public static int executeSingleMob(CommandContext<CommandSourceStack> context, String mobId) {
        var source = context.getSource();
        return runAsyncExport(context, "📄", "complexityanalyzer.command.export.single_mob_header", ChatFormatting.AQUA,
                out -> out.sendEntry(source, "🧟", "complexityanalyzer.command.export.mob_label", mobId, ChatFormatting.GRAY, ChatFormatting.WHITE),
                "Single mob export",
                format -> ComplexityExporter.exportSingleMob(source.getServer(), AnalysisEngine.getInstance(), format, mobId));
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

    @FunctionalInterface
    private interface ExportAction {
        Path run(IExportFormat format) throws Exception;
    }
}