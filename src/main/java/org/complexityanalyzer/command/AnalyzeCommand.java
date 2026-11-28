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

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.util.ComplexityComparators;

import static net.minecraft.ChatFormatting.*;
import static org.complexityanalyzer.util.LogMarkers.ANALYZE_COMMAND;

public final class AnalyzeCommand {
    private static final ResourceLocation AIR_ID = ResourceLocation.withDefaultNamespace("air");

    private AnalyzeCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("analyze")
                .then(Commands.literal("item")
                        .then(Commands.argument("item", ResourceLocationArgument.id()).suggests(SharedSuggestions.ITEM)
                                .executes(cmd -> execute(cmd, ResourceLocationArgument.getId(cmd, "item")))))
                .then(Commands.literal("entity")
                        .then(Commands.argument("entity", ResourceLocationArgument.id()).suggests(SharedSuggestions.ENTITY)
                                .executes(cmd -> EntityAnalyzeCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "entity")))))
                .then(Commands.literal("loot")
                        .then(Commands.argument("loot_table", ResourceLocationArgument.id()).suggests(SharedSuggestions.LOOT_TABLE)
                                .executes(ctx -> LootAnalyzeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "loot_table")))));
    }

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.not_ready"));
            output.sendTipLiteral(source, Component.translatable("complexityanalyzer.command.analyze.current_state", engine.getCurrentState().toString()));
            return 0;
        }

        var item = GameRegistryManager.getItem(itemId);
        if (item == null || (item == Items.AIR && !itemId.equals(AIR_ID))) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.item_not_found", itemId.toString()));
            return 0;
        }

        try {
            var optimal = engine.getComplexityResult(item);
            if (optimal == null) {
                output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.failed", itemId.toString()));
                return 0;
            }

            displayAnalysis(source, item, optimal, engine, output);
            return 1;
        } catch (Exception e) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.error", e.getMessage()));
            ComplexityAnalyzer.LOGGER.error(ANALYZE_COMMAND, "Error analyzing item {}", itemId, e);
            return 0;
        }
    }

    private static void displayAnalysis(CommandSourceStack source, Item item, ItemComplexity optimal,
                                        AnalysisEngine engine, OutputManager output) {
        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.analyze.header", GOLD);
        output.sendEntry(source, "🏷", "complexityanalyzer.command.analyze.item_label", item.getDescription(), GRAY, WHITE);
        output.sendEmptyLine(source);

        displayMainInfo(source, optimal, output);
        displaySourceInfo(source, item, optimal, engine, output);
        displayStatus(source, optimal, output);

        output.sendFooter(source);
    }

    private static void displayMainInfo(CommandSourceStack source, ItemComplexity optimal, OutputManager output) {
        var category = optimal.getCategory();
        double complexity = optimal.getComplexity();
        var valueColor = category.getColor();
        String valueString;

        if (complexity < 0) {
            valueString = "—";
            valueColor = DARK_GRAY;
        } else {
            valueString = "%.2f".formatted(complexity);
        }

        output.sendStatusLine(source, "⚙", "complexityanalyzer.command.analyze.complexity_section", YELLOW);
        output.sendSubEntry(source, category.getIcon(), "complexityanalyzer.command.analyze.category_label", category.getTranslationKey(), GRAY, category.getColor());
        output.sendSubEntry(source, "complexityanalyzer.command.analyze.value_label", valueString, GRAY, valueColor);

        if (complexity >= 0) {
            int barPercent = (int) Math.clamp(complexity, 0.0, 100.0);
            output.sendValueBar(source, barPercent, DARK_GRAY, "", WHITE);
        }

        output.sendEmptyLine(source);
    }

    private static void displaySourceInfo(CommandSourceStack source, Item item, ItemComplexity optimal,
                                          AnalysisEngine engine, OutputManager output) {
        output.sendStatusLine(source, "📦", "complexityanalyzer.command.analyze.source_section", YELLOW);

        if (optimal.hasRecipe()) {
            output.sendSubEntry(source, "complexityanalyzer.command.analyze.has_recipe", "complexityanalyzer.command.analyze.yes", GRAY, WHITE);

            int depth = optimal.getDepth();
            var depthColor = depth <= 2 ? GREEN : depth <= 4 ? YELLOW : RED;
            output.sendSubEntry(source, Component.literal("  ").append(Component.translatable("complexityanalyzer.command.analyze.depth")), depth, DARK_GRAY, depthColor);

            int usageCount = engine.getUsageCount(item);
            output.sendSubEntry(source, Component.literal("  ").append(Component.translatable("complexityanalyzer.command.analyze.used_in")),
                    Component.translatable("complexityanalyzer.command.analyze.used_in_count", usageCount), DARK_GRAY, AQUA);
        } else {
            output.sendSubEntry(source, "complexityanalyzer.command.analyze.type_label", "complexityanalyzer.command.analyze.base_resource", GRAY, GOLD);
        }

        var allSources = engine.findAllSourcesForItem(item);
        if (!allSources.isEmpty()) {
            output.sendTip(source, "complexityanalyzer.command.analyze.alt_sources");
            displayAlternativeSources(source, allSources, engine, output);
        }

        output.sendEmptyLine(source);
    }

    private static void displayAlternativeSources(CommandSourceStack source, ObjectList<BaseResourceData> allSources,
                                                  AnalysisEngine engine, OutputManager output) {
        var sortedSources = new ObjectArrayList<SourceWithCost>(allSources.size());
        for (var data : allSources) sortedSources.add(new SourceWithCost(data, calculateSourceCost(data, engine)));
        sortedSources.sort(ComplexityComparators.comparingDoubleAscending(SourceWithCost::fullCost));

        for (var swc : sortedSources) {
            String icon = swc.data().getSourceType().getIcon();
            double cost = swc.fullCost();
            String costString = Double.isInfinite(cost) ? "∞" : "%.2f".formatted(cost);
            var costColor = Double.isInfinite(cost) ? RED : cost < 10 ? GREEN : cost < 50 ? YELLOW : RED;

            output.sendSubEntry(source, icon, swc.data().getSourceType().getDisplayName(), Component.translatable("complexityanalyzer.command.analyze.cost", costString), WHITE, costColor);
            output.sendTipLiteral(source, swc.data().getDetails());
        }
    }

    private static double calculateSourceCost(BaseResourceData data, AnalysisEngine engine) {
        double fullCost = data.getBaseFactor();
        var sourceItems = data.getSourceItems();
        if (sourceItems.isEmpty()) return fullCost;

        for (var entry : Reference2DoubleMaps.fastIterable(sourceItems)) {
            var sourceComplexity = engine.getComplexityResult(entry.getKey());
            if (sourceComplexity != null && sourceComplexity.isValid()) {
                fullCost += sourceComplexity.getComplexity() * entry.getDoubleValue();
            } else {
                return Double.POSITIVE_INFINITY;
            }
        }

        return fullCost;
    }

    private static void displayStatus(CommandSourceStack source, ItemComplexity optimal, OutputManager output) {
        boolean isValid = optimal.isValid();
        String validKey = isValid ? "complexityanalyzer.command.analyze.yes" : "complexityanalyzer.command.analyze.no";
        var statusColor = isValid ? GREEN : RED;

        output.sendStatusLine(source, "ℹ", "complexityanalyzer.command.analyze.status_section", AQUA);
        output.sendSubEntry(source, "complexityanalyzer.command.analyze.valid_label", validKey, GRAY, statusColor);

        if (optimal.getErrorMessage() != null) {
            output.sendSubEntry(source, "complexityanalyzer.command.analyze.error_label", optimal.getErrorMessage(), RED, RED);
        }
    }

    private record SourceWithCost(BaseResourceData data, double fullCost) {
    }
}