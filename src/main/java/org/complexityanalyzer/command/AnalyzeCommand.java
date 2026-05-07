/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;

import java.util.Comparator;

public class AnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready yet!")
                    .append(Component.literal("\nCurrent state: " + engine.getCurrentState())
                            .withStyle(ChatFormatting.GRAY)));
            return 0;
        }

        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == net.minecraft.world.item.Items.AIR && !itemId.toString().equals("minecraft:air")) {
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
        String itemName = item.getDescription().getString();

        output.sendInfo(source, Component.literal("").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY)));

        output.sendInfo(source, Component.literal("📊 ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Complexity Analysis")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        MutableComponent itemComponent = Component.literal("Item: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(itemName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        output.sendInfo(source, itemComponent);

        output.sendInfo(source, Component.literal(""));

        displayMainInfo(source, optimal, output);
        displaySourceInfo(source, item, optimal, engine, output);
        displayStatus(source, optimal, output, itemId);

        output.sendInfo(source, Component.literal("═══════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void displayMainInfo(CommandSourceStack source, ItemComplexity optimal, OutputManager output) {
        ComplexityCategory category = optimal.getCategory();
        double complexity = optimal.getComplexity();

        output.sendInfo(source, Component.literal("⚙ ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("Complexity").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        String icon = getCategoryIcon(category);

        MutableComponent categoryComponent = Component.literal("  Category: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(icon + " " + category.getDisplayName()).withStyle(category.getColor()));

        output.sendInfo(source, categoryComponent);

        ChatFormatting valueColor = getComplexityColor(complexity);
        String valueString = Double.isInfinite(complexity) ? "∞" : String.format("%.2f", complexity);
        MutableComponent valueComponent = Component.literal("  Value: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(valueString).withStyle(valueColor, ChatFormatting.BOLD));

        output.sendInfo(source, valueComponent);

        String progressBar = getComplexityBar(complexity);
        output.sendInfo(source, Component.literal("  " + progressBar).withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source, Component.literal(""));
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
        output.sendInfo(source, Component.literal("📦 ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("Source Information")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        if (optimal.hasRecipe()) {
            output.sendInfo(source, Component.literal("  ✓ ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("Has Recipe: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("Yes").withStyle(ChatFormatting.WHITE)));

            int depth = optimal.getDepth();
            ChatFormatting depthColor = depth <= 2 ? ChatFormatting.GREEN :
                    depth <= 4 ? ChatFormatting.YELLOW : ChatFormatting.RED;
            output.sendInfo(source, Component.literal("    Crafting Depth: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(depth)).withStyle(depthColor)));

            int usageCount = engine.getUsageCount(item);
            output.sendInfo(source, Component.literal("    Used in Recipes: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(usageCount)).withStyle(ChatFormatting.AQUA)));
        } else {
            output.sendInfo(source, Component.literal("  ⛏ ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Base Resource ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("(No crafting recipe)")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
        }

        ObjectList<BaseResourceData> allSources = engine.findAllSourcesForItem(item);
        if (!allSources.isEmpty()) {
            output.sendInfo(source, Component.literal("  🔍 Alternative Sources:")
                    .withStyle(ChatFormatting.YELLOW));

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

                MutableComponent sourceComponent = Component.literal("    " + icon + " ")
                        .withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(swc.data().getSourceType().getDisplayName())
                                .withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (Cost: ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(costString).withStyle(costColor, ChatFormatting.BOLD))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));

                output.sendInfo(source, sourceComponent);

                output.sendInfo(source, Component.literal("      → " + swc.data().getDetails())
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayStatus(CommandSourceStack source, ItemComplexity optimal, OutputManager output, ResourceLocation itemId) {
        output.sendInfo(source, Component.literal("ℹ ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Status").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        boolean isValid = optimal.isValid();
        output.sendInfo(source, Component.literal("  Valid: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(isValid ? "✓ Yes" : "✗ No")
                        .withStyle(isValid ? ChatFormatting.GREEN : ChatFormatting.RED)));

        if (optimal.hasCycle()) output.sendInfo(source, Component.literal("  ⚠ Warning: ")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal("Cyclic dependency detected!").withStyle(ChatFormatting.RED)));


        if (optimal.getErrorMessage() != null) output.sendInfo(source, Component.literal("  ❌ Error: ")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(optimal.getErrorMessage()).withStyle(ChatFormatting.RED)));


        if (optimal.hasRecipe()) {
            output.sendInfo(source, Component.literal(""));

            String treeCommand = "/complexity tree " + itemId;
            MutableComponent tipComponent = Component.literal("💡 Tip: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Click here")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE).withStyle(style -> style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, treeCommand))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("Click to view crafting tree")
                                                    .withStyle(ChatFormatting.GREEN)))))
                    .append(Component.literal(" to see full crafting tree").withStyle(ChatFormatting.GRAY));

            output.sendInfo(source, tipComponent);
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

    private static String getComplexityBar(double complexity) {
        int bars = Math.min(10, (int) (complexity / 10));
        return "[" + "██████████".substring(0, bars) + "░░░░░░░░░░".substring(bars) + "]";
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