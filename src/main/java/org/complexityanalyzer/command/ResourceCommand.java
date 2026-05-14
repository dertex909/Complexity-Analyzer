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

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.*;
import org.complexityanalyzer.command.util.SharedSuggestions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.event.AnalysisBootstrap;
import org.complexityanalyzer.core.GameRegistryManager;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;

public final class ResourceCommand {
    private ResourceCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("resource").then(Commands.argument("item", ResourceLocationArgument.id()).suggests(SharedSuggestions.ITEM)
                .executes(cmd -> ResourceCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "item"))));
    }

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisBootstrap.getEngine();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready yet!"));
            return 0;
        }

        Item item = GameRegistryManager.getItem(itemId);
        if (item == Items.AIR && !itemId.equals(ResourceLocation.parse("minecraft:air"))) {
            output.sendFailure(source, Component.literal("❌ Item not found: ")
                    .append(Component.literal(itemId.toString()).withStyle(ChatFormatting.YELLOW)));
            return 0;
        }

        try {
            BaseResourceData data = engine.getBaseResourceData(item);
            if (data == null) {
                output.sendFailure(source, Component.literal("⚠ No base resource data available"));
                output.sendEntry(source, "🏷", "Item", itemId.toString(), ChatFormatting.GRAY, ChatFormatting.YELLOW);
                output.sendEmptyLine(source);
                output.sendTip(source, "This item might only be obtainable through crafting.");
                return 0;
            }

            displayResourceInfo(source, item, data, engine, itemId, output);
            return 1;
        } catch (Exception e) {
            output.sendFailure(source, Component.literal("❌ Error analyzing resource: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error analyzing resource {}", itemId, e);
            return 0;
        }
    }

    private static void displayResourceInfo(CommandSourceStack source, Item item, BaseResourceData data,
                                            AnalysisEngine engine, ResourceLocation itemId, OutputManager output) {
        output.sendEmptyLine(source);
        output.sendHeader(source, getSourceIcon(data.getSourceType()), "Base Resource Analysis", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🏷", "Item", item.getDescription().getString(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        displaySourceInfo(source, data, output);
        displayBaseFactor(source, data, output);
        displaySourceItems(source, data, engine, output);
        displayAdditionalInfo(source, item, engine, itemId, output);
        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displaySourceInfo(CommandSourceStack source, BaseResourceData data, OutputManager output) {
        output.sendStatusLine(source, "📍", "Source Information", ChatFormatting.YELLOW);
        String sourceIcon = getSourceIcon(data.getSourceType());
        ChatFormatting sourceColor = getSourceColor(data.getSourceType());
        output.sendSubEntry(source, sourceIcon, "Type", data.getSourceType().getDisplayName(), ChatFormatting.GRAY, sourceColor);
        if (!data.getDetails().isEmpty())
            output.sendSubEntry(source, "Details", data.getDetails(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
    }

    private static void displayBaseFactor(CommandSourceStack source, BaseResourceData data, OutputManager output) {
        double baseFactor = data.getBaseFactor();
        ChatFormatting factorColor = getFactorColor(baseFactor);
        String difficultyText = getFactorDifficulty(baseFactor);
        output.sendStatusLine(source, "⚖", "Base Factor", ChatFormatting.YELLOW);
        output.sendSubEntry(source, "Value", String.format("%.2f", baseFactor), ChatFormatting.GRAY, factorColor);
        output.sendValueBar(source, (int) Math.min(100, baseFactor * 4), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        output.sendSubEntry(source, "Difficulty", difficultyText, ChatFormatting.GRAY, factorColor);
        output.sendEmptyLine(source);
    }

    private static void displaySourceItems(CommandSourceStack source, BaseResourceData data, AnalysisEngine engine, OutputManager output) {
        Reference2DoubleMap<Item> sourceItems = data.getSourceItems();
        output.sendStatusLine(source, "🔗", "Required Source Items", ChatFormatting.AQUA);
        output.sendTip(source, "Items needed to obtain this resource");
        for (var entry : Reference2DoubleMaps.fastIterable(sourceItems)) {
            Item sourceItem = entry.getKey();
            double amount = entry.getDoubleValue();
            String sourceItemName = sourceItem.getDescription().getString();
            String value = "x" + String.format("%.1f", amount);
            ItemComplexity result = engine.getComplexityResult(sourceItem);
            if (result != null) value += String.format(" (%.2f)", result.getComplexity());
            output.sendSubEntry(source, "📦", sourceItemName, value, ChatFormatting.WHITE, ChatFormatting.YELLOW);
        }
        output.sendEmptyLine(source);
    }

    private static void displayAdditionalInfo(CommandSourceStack source, Item item, AnalysisEngine engine, ResourceLocation itemId, OutputManager output) {
        boolean hasRecipe = engine.hasRecipe(item);
        output.sendStatusLine(source, "ℹ", "Additional Information", ChatFormatting.AQUA);
        if (hasRecipe) {
            output.sendSubEntry(source, "Note", "This item also has crafting recipes", ChatFormatting.YELLOW, ChatFormatting.YELLOW);
            output.sendEmptyLine(source);
            output.sendClickableTip(source, "Tip: ", "[Click here]", " for full analysis",
                    "/complexity analyze item " + itemId, "View full complexity analysis");
        } else {
            output.sendSubEntry(source, "Type", "Pure base resource", ChatFormatting.GRAY, ChatFormatting.GREEN);
        }
        output.sendEmptyLine(source);
    }

    private static String getSourceIcon(BaseResourceData.ResourceSourceType sourceType) {
        return switch (sourceType.name()) {
            case "MINING" -> "⛏";
            case "MOB_DROP" -> "⚔";
            case "CHEST_LOOT" -> "📦";
            case "FISHING" -> "🎣";
            case "TRADING" -> "💰";
            case "FARMING" -> "🌾";
            case "FORAGING" -> "🪓";
            default -> "📍";
        };
    }

    private static ChatFormatting getSourceColor(BaseResourceData.ResourceSourceType sourceType) {
        return switch (sourceType.name()) {
            case "MINING" -> ChatFormatting.GRAY;
            case "MOB_DROP" -> ChatFormatting.RED;
            case "CHEST_LOOT" -> ChatFormatting.GOLD;
            case "FISHING" -> ChatFormatting.AQUA;
            case "TRADING" -> ChatFormatting.GREEN;
            case "FARMING" -> ChatFormatting.YELLOW;
            case "FORAGING" -> ChatFormatting.DARK_GREEN;
            default -> ChatFormatting.WHITE;
        };
    }

    private static ChatFormatting getFactorColor(double baseFactor) {
        if (baseFactor >= 20.0) return ChatFormatting.DARK_RED;
        if (baseFactor >= 15.0) return ChatFormatting.RED;
        if (baseFactor >= 10.0) return ChatFormatting.GOLD;
        if (baseFactor >= 5.0) return ChatFormatting.YELLOW;
        return ChatFormatting.GREEN;
    }

    private static String getFactorDifficulty(double baseFactor) {
        if (baseFactor >= 20.0) return "Extremely Hard";
        if (baseFactor >= 15.0) return "Very Hard";
        if (baseFactor >= 10.0) return "Hard";
        if (baseFactor >= 5.0) return "Moderate";
        if (baseFactor >= 2.0) return "Easy";
        return "Very Easy";
    }
}