/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.event.DatapackSyncHandler;

import java.util.Map;
import java.util.Optional;

public class ResourceCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = DatapackSyncHandler.getEngine();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready yet!")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemOpt.isEmpty()) {
            output.sendFailure(source,
                    Component.literal("❌ Item not found: ")
                            .append(Component.literal(itemId.toString())
                                    .withStyle(ChatFormatting.YELLOW)));
            return 0;
        }

        Item item = itemOpt.get();

        try {
            Optional<BaseResourceData> resourceDataOpt = engine.getBaseResourceData(item);

            if (resourceDataOpt.isEmpty()) {
                output.sendFailure(source,
                        Component.literal("⚠ No base resource data available")
                                .withStyle(ChatFormatting.RED));

                output.sendInfo(source,
                        Component.literal("Item: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(itemId.toString())
                                        .withStyle(ChatFormatting.YELLOW)));

                output.sendInfo(source, Component.literal(""));
                output.sendInfo(source,
                        Component.literal("This item might only be obtainable through crafting.")
                                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

                return 0;
            }

            BaseResourceData data = resourceDataOpt.get();
            displayResourceInfo(source, item, data, engine, itemId, output);

            return 1;

        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ Error analyzing resource: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error analyzing resource {}", itemId, e);
            return 0;
        }
    }

    private static void displayResourceInfo(
            CommandSourceStack source,
            Item item,
            BaseResourceData data,
            AnalysisEngine engine,
            ResourceLocation itemId,
            OutputManager output
    ) {
        String itemName = item.getDescription().getString();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        String resourceIcon = getSourceIcon(data.getSourceType());
        output.sendInfo(source,
                Component.literal(resourceIcon + " ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("Base Resource Analysis")
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  Item: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(itemName)
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal(""));

        displaySourceInfo(source, data, output);

        displayBaseFactor(source, data, output);

        displaySourceItems(source, data, engine, output);

        displayAdditionalInfo(source, item, engine, itemId, output);

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void displaySourceInfo(
            CommandSourceStack source,
            BaseResourceData data,
            OutputManager output
    ) {
        output.sendInfo(source,
                Component.literal("  📍 Source Information")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        String sourceIcon = getSourceIcon(data.getSourceType());
        ChatFormatting sourceColor = getSourceColor(data.getSourceType());

        output.sendInfo(source,
                Component.literal("    Type: " + sourceIcon + " ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(data.getSourceType().getDisplayName())
                                .withStyle(sourceColor, ChatFormatting.BOLD)));

        if (!data.getDetails().isEmpty()) {
            output.sendInfo(source,
                    Component.literal("    Details: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(data.getDetails())
                                    .withStyle(ChatFormatting.WHITE)));
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayBaseFactor(
            CommandSourceStack source,
            BaseResourceData data,
            OutputManager output
    ) {
        double baseFactor = data.getBaseFactor();
        ChatFormatting factorColor = getFactorColor(baseFactor);
        String difficultyText = getFactorDifficulty(baseFactor);

        output.sendInfo(source,
                Component.literal("  ⚖ Base Factor")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Value: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format("%.2f", baseFactor))
                                .withStyle(factorColor, ChatFormatting.BOLD)));

        String factorBar = getFactorBar(baseFactor);
        output.sendInfo(source,
                Component.literal("    " + factorBar)
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("    Difficulty: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(difficultyText)
                                .withStyle(factorColor)));

        output.sendInfo(source, Component.literal(""));
    }

    private static void displaySourceItems(
            CommandSourceStack source,
            BaseResourceData data,
            AnalysisEngine engine,
            OutputManager output
    ) {
        Map<Item, Double> sourceItems = data.getSourceItems();

        output.sendInfo(source,
                Component.literal("  🔗 Required Source Items")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    (Items needed to obtain this resource)")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

        for (Map.Entry<Item, Double> entry : sourceItems.entrySet()) {
            Item sourceItem = entry.getKey();
            double amount = entry.getValue();
            String sourceItemName = sourceItem.getDescription().getString();

            Optional<Double> sourceComplexity = engine.getComplexityResult(sourceItem)
                    .map(ItemComplexity::getComplexity);

            MutableComponent itemLine = Component.literal("    • ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(sourceItemName)
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" x" + String.format("%.1f", amount))
                            .withStyle(ChatFormatting.YELLOW));

            if (sourceComplexity.isPresent()) {
                double complexity = sourceComplexity.get();
                ChatFormatting complexityColor = getComplexityColor(complexity);

                itemLine.append(Component.literal(" (")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", complexity))
                                .withStyle(complexityColor))
                        .append(Component.literal(")")
                                .withStyle(ChatFormatting.DARK_GRAY));
            }

            output.sendInfo(source, itemLine);
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayAdditionalInfo(
            CommandSourceStack source,
            Item item,
            AnalysisEngine engine,
            ResourceLocation itemId,
            OutputManager output
    ) {
        boolean hasRecipe = engine.hasRecipe(item);

        output.sendInfo(source,
                Component.literal("  ℹ Additional Information")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        if (hasRecipe) {
            output.sendInfo(source,
                    Component.literal("    ⚠ This item also has crafting recipes")
                            .withStyle(ChatFormatting.YELLOW));

            output.sendInfo(source, Component.literal(""));

            String analyzeCommand = "/complexity analyze item " + itemId;
            MutableComponent clickableLink = Component.literal("    💡 ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("[Click here]")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, analyzeCommand))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("View full complexity analysis")
                                                    .withStyle(ChatFormatting.GREEN)))))
                    .append(Component.literal(" for full analysis")
                            .withStyle(ChatFormatting.GRAY));

            output.sendInfo(source, clickableLink);

        } else {
            output.sendInfo(source,
                    Component.literal("    ✓ Pure base resource")
                            .withStyle(ChatFormatting.GREEN));

            output.sendInfo(source,
                    Component.literal("    No crafting recipes available")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        output.sendInfo(source, Component.literal(""));
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

    private static String getFactorBar(double baseFactor) {
        int filled = (int) Math.min(10, Math.ceil(baseFactor / 2.5));
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i < filled) {
                bar.append("█");
            } else {
                bar.append("░");
            }
        }
        bar.append("]");
        return bar.toString();
    }

    private static ChatFormatting getComplexityColor(double complexity) {
        if (complexity >= 100) return ChatFormatting.DARK_RED;
        if (complexity >= 50) return ChatFormatting.RED;
        if (complexity >= 30) return ChatFormatting.GOLD;
        if (complexity >= 10) return ChatFormatting.YELLOW;
        return ChatFormatting.GREEN;
    }
}