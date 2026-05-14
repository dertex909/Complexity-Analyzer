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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.core.GameRegistryManager;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import org.complexityanalyzer.command.util.SharedSuggestions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;

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
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready yet!"));
            output.sendTip(source, "Current state: " + engine.getCurrentState());
            return 0;
        }

        Item item = GameRegistryManager.getItem(itemId);
        if (item == null || (item == Items.AIR && !itemId.equals(ResourceLocation.parse("minecraft:air")))) {
            output.sendFailure(source, Component.literal("❌ Item not found: ")
                    .append(Component.literal(itemId.toString()).withStyle(ChatFormatting.YELLOW)));
            return 0;
        }

        try {
            ItemComplexity optimal = engine.getComplexityResult(item);
            if (optimal == null) {
                output.sendFailure(source, Component.literal("❌ Failed to analyze item: " + itemId));
                return 0;
            }

            displayAnalysis(source, item, optimal, engine, output, itemId);
            return 1;

        } catch (Exception e) {
            output.sendFailure(source, Component.literal("⚠ Error analyzing item: " + e.getMessage()));
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
        String itemName = (item != null) ? item.getDescription().getString() : itemId.toString();

        output.sendEmptyLine(source);
        output.sendHeader(source, "📊", "Complexity Analysis", ChatFormatting.GOLD);

        output.sendEntry(source, "🏷", "Item", itemName, ChatFormatting.GRAY, ChatFormatting.WHITE);

        output.sendEmptyLine(source);

        displayMainInfo(source, optimal, output);
        displaySourceInfo(source, item, optimal, engine, output);
        displayStatus(source, optimal, output, itemId);

        output.sendFooter(source);
    }

    private static void displayMainInfo(CommandSourceStack source, ItemComplexity optimal, OutputManager output) {
        ComplexityCategory category = optimal.getCategory();
        double complexity = optimal.getComplexity();

        output.sendStatusLine(source, "⚙", "Complexity", ChatFormatting.YELLOW);

        output.sendSubEntry(source, getCategoryIcon(category), "Category", category.getDisplayName(), ChatFormatting.GRAY, category.getColor());

        ChatFormatting valueColor = getComplexityColor(complexity);
        String valueString = Double.isInfinite(complexity) ? "∞" : String.format("%.2f", complexity);
        output.sendSubEntry(source, "Value", valueString, ChatFormatting.GRAY, valueColor);

        output.sendValueBar(source, (int) Math.min(100, (complexity / 100.0) * 100), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        output.sendEmptyLine(source);
    }

    private record SourceWithCost(BaseResourceData data, double fullCost) {
    }

    private static void displaySourceInfo(
            CommandSourceStack source,
            Item item,
            ItemComplexity optimal,
            AnalysisEngine engine,
            OutputManager output
    ) {
        output.sendStatusLine(source, "📦", "Source Information", ChatFormatting.YELLOW);

        if (optimal.hasRecipe()) {
            output.sendSubEntry(source, "Has Recipe", "Yes", ChatFormatting.GRAY, ChatFormatting.WHITE);

            int depth = optimal.getDepth();
            ChatFormatting depthColor = depth <= 2 ? ChatFormatting.GREEN :
                    depth <= 4 ? ChatFormatting.YELLOW : ChatFormatting.RED;
            output.sendSubEntry(source, "  Depth", String.valueOf(depth), ChatFormatting.DARK_GRAY, depthColor);

            int usageCount = engine.getUsageCount(item);
            output.sendSubEntry(source, "  Used In", usageCount + " recipes", ChatFormatting.DARK_GRAY, ChatFormatting.AQUA);
        } else {
            output.sendSubEntry(source, "Type", "Base Resource (No recipes)", ChatFormatting.GRAY, ChatFormatting.GOLD);
        }

        ObjectList<BaseResourceData> allSources = engine.findAllSourcesForItem(item);
        if (!allSources.isEmpty()) {
            output.sendTip(source, "Alternative Sources:");

            ObjectList<SourceWithCost> sortedSources = new ObjectArrayList<>(allSources.size());
            for (BaseResourceData data : allSources) {
                double fullEstimatedCost = data.getBaseFactor();
                var sourceItems = data.getSourceItems();

                if (!sourceItems.isEmpty()) for (var entry : Reference2DoubleMaps.fastIterable(sourceItems)) {
                    Item sourceItem = entry.getKey();
                    double amount = entry.getDoubleValue();

                    ItemComplexity sourceComplexity = engine.getComplexityResult(sourceItem);
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

            for (SourceWithCost swc : sortedSources) {
                String icon = getSourceIcon(swc.data().getSourceType());
                String costString = Double.isInfinite(swc.fullCost()) ? "∞" : String.format("%.2f", swc.fullCost());

                ChatFormatting costColor = Double.isInfinite(swc.fullCost()) ? ChatFormatting.RED
                        : swc.fullCost() < 10 ? ChatFormatting.GREEN : swc.fullCost() < 50 ? ChatFormatting.YELLOW
                                                                       : ChatFormatting.RED;

                output.sendSubEntry(source, icon, swc.data().getSourceType().getDisplayName(), "Cost: " + costString, ChatFormatting.WHITE, costColor);
                output.sendTip(source, swc.data().getDetails());
            }
        }

        output.sendEmptyLine(source);
    }

    private static void displayStatus(CommandSourceStack source, ItemComplexity optimal, OutputManager output, ResourceLocation itemId) {
        output.sendStatusLine(source, "ℹ", "Status", ChatFormatting.AQUA);

        boolean isValid = optimal.isValid();
        output.sendSubEntry(source, "Valid", isValid ? "Yes" : "No", ChatFormatting.GRAY, isValid ? ChatFormatting.GREEN : ChatFormatting.RED);

        if (optimal.hasCycle())
            output.sendSubEntry(source, "Warning", "Cyclic dependency detected!", ChatFormatting.YELLOW, ChatFormatting.RED);

        if (optimal.getErrorMessage() != null)
            output.sendSubEntry(source, "Error", optimal.getErrorMessage(), ChatFormatting.RED, ChatFormatting.RED);

        if (optimal.hasRecipe()) {
            output.sendEmptyLine(source);

            output.sendClickableTip(source, "Tip: ", "Click here", " to see full crafting tree", "/complexity tree " + itemId, "Click to view crafting tree");
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
}