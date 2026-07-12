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
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;

import java.util.Comparator;

public final class AnalyzeCommand {
    private AnalyzeCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("analyze")
                .then(Commands.literal("item").then(Commands.argument("item", ResourceLocationArgument.id()).suggests(SharedSuggestions.ITEM)
                        .executes(cmd -> AnalyzeCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "item")))))
                .then(Commands.literal("entity").then(Commands.argument("entity", ResourceLocationArgument.id()).suggests(SharedSuggestions.ENTITY)
                        .executes(cmd -> EntityAnalyzeCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "entity")))))
                .then(Commands.literal("loot").then(Commands.argument("loot_table", ResourceLocationArgument.id()).suggests(SharedSuggestions.LOOT_TABLE)
                        .executes(ctx -> LootAnalyzeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "loot_table")))));
    }

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.not_ready"));
            output.sendTipLiteral(source, Component.translatable("complexityanalyzer.command.analyze.current_state", engine.getCurrentState()));
            return 0;
        }

        var item = GameRegistryManager.getItem(itemId);
        if (item == null || (item == Items.AIR && !itemId.equals(ResourceLocation.parse("minecraft:air")))) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.item_not_found", itemId.toString()));
            return 0;
        }

        try {
            var optimal = engine.getComplexityResult(item);
            if (optimal == null) {
                output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.failed", itemId.toString()));
                return 0;
            }

            displayAnalysis(source, item, optimal, engine, output, itemId);
            return 1;

        } catch (Exception e) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.error", e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error analyzing item {}", itemId, e);
            return 0;
        }
    }

    private static void displayAnalysis(
            CommandSourceStack source,
            Item item,
            ItemComplexity optimal,
            AnalysisEngine engine,
            OutputManager output,
            ResourceLocation itemId
    ) {
        var itemName = (item != null) ? item.getDescription() : Component.literal(itemId.toString());

        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "complexityanalyzer.command.analyze.header", ChatFormatting.GOLD);

        output.sendEntry(source, "🏷", "complexityanalyzer.command.analyze.item_label", itemName, ChatFormatting.GRAY, ChatFormatting.WHITE);

        output.sendEmptyLine(source);

        displayMainInfo(source, optimal, output);
        displaySourceInfo(source, item, optimal, engine, output);
        displayStatus(source, optimal, output, itemId);

        output.sendFooter(source);
    }

    private static void displayMainInfo(CommandSourceStack source, ItemComplexity optimal, OutputManager output) {
        var category = optimal.getCategory();
        double complexity = optimal.getComplexity();
        output.sendStatusLine(source, "⚙", "complexityanalyzer.command.analyze.complexity_section", ChatFormatting.YELLOW);
        output.sendSubEntry(source, getCategoryIcon(category), "complexityanalyzer.command.analyze.category_label", category.getTranslationKey(), ChatFormatting.GRAY, category.getColor());
        var valueColor = getComplexityColor(complexity);
        String valueString;
        if (complexity < 0) {
            valueString = "—";
            valueColor = ChatFormatting.DARK_GRAY;
        } else {
            valueString = String.format("%.2f", complexity);
        }
        output.sendSubEntry(source, "complexityanalyzer.command.analyze.value_label", valueString, ChatFormatting.GRAY, valueColor);
        if (complexity >= 0) {
            output.sendValueBar(source, (int) Math.min(100, (complexity / 100.0) * 100), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        }
        output.sendEmptyLine(source);
    }

    private static void displaySourceInfo(CommandSourceStack source, Item item, ItemComplexity optimal,
                                          AnalysisEngine engine, OutputManager output) {
        output.sendStatusLine(source, "📦", "complexityanalyzer.command.analyze.source_section", ChatFormatting.YELLOW);

        if (optimal.hasRecipe()) {
            output.sendSubEntry(source, "complexityanalyzer.command.analyze.has_recipe", "complexityanalyzer.command.analyze.yes", ChatFormatting.GRAY, ChatFormatting.WHITE);
            int depth = optimal.getDepth();
            var depthColor = depth <= 2 ? ChatFormatting.GREEN : depth <= 4 ? ChatFormatting.YELLOW : ChatFormatting.RED;
            output.sendSubEntry(source, Component.literal("  ").append(Component.translatable("complexityanalyzer.command.analyze.depth")), String.valueOf(depth), ChatFormatting.DARK_GRAY, depthColor);
            int usageCount = engine.getUsageCount(item);
            output.sendSubEntry(source, Component.literal("  ").append(Component.translatable("complexityanalyzer.command.analyze.used_in")), Component.translatable("complexityanalyzer.command.analyze.used_in_count", usageCount), ChatFormatting.DARK_GRAY, ChatFormatting.AQUA);
        } else {
            output.sendSubEntry(source, "complexityanalyzer.command.analyze.type_label", "complexityanalyzer.command.analyze.base_resource", ChatFormatting.GRAY, ChatFormatting.GOLD);
        }

        var allSources = engine.findAllSourcesForItem(item);
        if (!allSources.isEmpty()) {
            output.sendTip(source, "complexityanalyzer.command.analyze.alt_sources");
            var sortedSources = new ObjectArrayList<SourceWithCost>(allSources.size());
            for (var data : allSources) {
                double fullEstimatedCost = data.getBaseFactor();
                var sourceItems = data.getSourceItems();
                if (!sourceItems.isEmpty()) for (var entry : Reference2DoubleMaps.fastIterable(sourceItems)) {
                    var sourceItem = entry.getKey();
                    double amount = entry.getDoubleValue();
                    var sourceComplexity = engine.getComplexityResult(sourceItem);
                    if (sourceComplexity != null && sourceComplexity.isValid()) {
                        fullEstimatedCost += sourceComplexity.getComplexity() * amount;
                    } else {
                        fullEstimatedCost = Double.POSITIVE_INFINITY;
                        break;
                    }
                }
                sortedSources.add(new SourceWithCost(data, fullEstimatedCost));
            }

            sortedSources.sort(Comparator.comparingDouble(SourceWithCost::fullCost));
            for (var swc : sortedSources) {
                String icon = getSourceIcon(swc.data().getSourceType());
                String costString = Double.isInfinite(swc.fullCost()) ? "∞" : String.format("%.2f", swc.fullCost());

                var costColor = Double.isInfinite(swc.fullCost()) ? ChatFormatting.RED
                        : swc.fullCost() < 10 ? ChatFormatting.GREEN : swc.fullCost() < 50 ? ChatFormatting.YELLOW : ChatFormatting.RED;

                output.sendSubEntry(source, icon, swc.data().getSourceType().getDisplayName(), Component.translatable("complexityanalyzer.command.analyze.cost", costString), ChatFormatting.WHITE, costColor);
                output.sendTipLiteral(source, swc.data().getDetails());
            }
        }

        output.sendEmptyLine(source);
    }

    private static void displayStatus(CommandSourceStack source, ItemComplexity optimal, OutputManager output, ResourceLocation itemId) {
        output.sendStatusLine(source, "ℹ", "complexityanalyzer.command.analyze.status_section", ChatFormatting.AQUA);

        boolean isValid = optimal.isValid();
        output.sendSubEntry(source, "complexityanalyzer.command.analyze.valid_label", isValid ? "complexityanalyzer.command.analyze.yes" : "complexityanalyzer.command.analyze.no", ChatFormatting.GRAY, isValid ? ChatFormatting.GREEN : ChatFormatting.RED);

        if (optimal.hasCycle())
            output.sendSubEntry(source, "complexityanalyzer.command.analyze.warning_label", "complexityanalyzer.command.analyze.cyclic_dependency", ChatFormatting.YELLOW, ChatFormatting.RED);

        if (optimal.getErrorMessage() != null)
            output.sendSubEntry(source, "complexityanalyzer.command.analyze.error_label", optimal.getErrorMessage(), ChatFormatting.RED, ChatFormatting.RED);

        if (optimal.hasRecipe()) {
            output.sendEmptyLine(source);
            output.sendClickableTip(source,
                    Component.translatable("complexityanalyzer.command.analyze.tree_tip_prefix"),
                    Component.translatable("complexityanalyzer.command.analyze.tree_tip_link"),
                    Component.translatable("complexityanalyzer.command.analyze.tree_tip_suffix"),
                    "/complexity tree " + itemId,
                    Component.translatable("complexityanalyzer.command.analyze.tree_tip_hover"));
        }
    }

    private static String getCategoryIcon(ComplexityCategory category) {
        return switch (category.name()) {
            case "TRIVIAL" -> "⬜";
            case "SIMPLE" -> "🟩";
            case "MODERATE" -> "🟨";
            case "COMPLEX" -> "🟧";
            case "DIFFICULT" -> "🟥";
            case "EXPERT" -> "🟪";
            case "MASTER" -> "⭐";
            case "MYTHICAL" -> "💎";
            case "TRANSCENDENT" -> "👑";
            case "UNOBTAINABLE" -> "🚫";
            default -> "❓";
        };
    }

    private static ChatFormatting getComplexityColor(double complexity) {
        if (complexity < 10) return ChatFormatting.GREEN;
        if (complexity < 30) return ChatFormatting.YELLOW;
        if (complexity < 50) return ChatFormatting.GOLD;
        if (complexity < 100) return ChatFormatting.RED;
        return ChatFormatting.DARK_RED;
    }

    private static String getSourceIcon(BaseResourceData.ResourceSourceType sourceType) {
        return switch (sourceType.name()) {
            case "MOB_DROP" -> "⚔";
            case "CHEST_LOOT" -> "📦";
            case "FISHING" -> "🎣";
            case "MINING" -> "⛏";
            case "TRADING" -> "💰";
            default -> "•";
        };
    }

    private record SourceWithCost(BaseResourceData data, double fullCost) {
    }
}