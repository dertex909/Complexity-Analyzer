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
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.command.util.SharedSuggestions;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.data.BaseResourceData;

import static net.minecraft.world.item.Items.AIR;

public final class ResourceCommand {
    private ResourceCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("resource")
                .then(Commands.argument("item", ResourceLocationArgument.id()).suggests(SharedSuggestions.ITEM).executes(cmd -> ResourceCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "item"))));
    }

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.tree.not_ready"));
            return 0;
        }

        var item = GameRegistryManager.getItem(itemId);
        if (item == AIR && !itemId.equals(ResourceLocation.parse("minecraft:air"))) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.resource.not_found", itemId.toString()));
            return 0;
        }

        try {
            var data = engine.getBaseResourceData(item);
            if (data == null) {
                output.sendFailure(source, Component.translatable("complexityanalyzer.command.resource.no_data"));
                output.sendEntry(source, "🏷", "complexityanalyzer.command.resource.item_label", itemId.toString(), ChatFormatting.GRAY, ChatFormatting.YELLOW);
                output.sendEmptyLine(source);
                output.sendTip(source, "complexityanalyzer.command.resource.no_data_tip");
                return 0;
            }

            displayResourceInfo(source, item, data, engine, itemId, output);
            return 1;
        } catch (Exception e) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.resource.failed", itemId.toString(), e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error analyzing resource {}", itemId, e);
            return 0;
        }
    }

    private static void displayResourceInfo(CommandSourceStack source, Item item, BaseResourceData data,
                                            AnalysisEngine engine, ResourceLocation itemId, OutputManager output) {
        output.sendEmptyLine(source);
        output.sendHeader(source, data.getSourceType().getIcon(), "complexityanalyzer.command.resource.header", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🏷", "complexityanalyzer.command.resource.item_label", item.getDescription(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
        displaySourceInfo(source, data, output);
        displayBaseFactor(source, data, output);
        displaySourceItems(source, data, engine, output);
        displayAdditionalInfo(source, item, engine, itemId, output);
        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displaySourceInfo(CommandSourceStack source, BaseResourceData data, OutputManager output) {
        var sourceType = data.getSourceType();
        output.sendStatusLine(source, "📍", "complexityanalyzer.command.resource.source_info", ChatFormatting.YELLOW);
        output.sendSubEntry(source, sourceType.getIcon(), "complexityanalyzer.command.resource.type_label", sourceType.getDisplayName(), ChatFormatting.GRAY, sourceType.getColor());
        if (!data.getDetails().isEmpty())
            output.sendSubEntry(source, "complexityanalyzer.command.resource.details_label", data.getDetails(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEmptyLine(source);
    }

    private static void displayBaseFactor(CommandSourceStack source, BaseResourceData data, OutputManager output) {
        double baseFactor = data.getBaseFactor();
        var tier = BaseResourceData.ResourceDifficultyTier.fromFactor(baseFactor);
        output.sendStatusLine(source, "⚖", "complexityanalyzer.command.resource.base_factor", ChatFormatting.YELLOW);
        output.sendSubEntry(source, "complexityanalyzer.command.resource.value_label", "%.2f".formatted(baseFactor), ChatFormatting.GRAY, tier.color);
        output.sendValueBar(source, (int) Math.min(100, baseFactor * 4), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        output.sendSubEntry(source, "complexityanalyzer.command.resource.difficulty_label", tier.translationKey, ChatFormatting.GRAY, tier.color);
        output.sendEmptyLine(source);
    }

    private static void displaySourceItems(CommandSourceStack source, BaseResourceData data, AnalysisEngine engine, OutputManager output) {
        var sourceItems = data.getSourceItems();
        output.sendStatusLine(source, "🔗", "complexityanalyzer.command.resource.required_source_items", ChatFormatting.AQUA);
        output.sendTip(source, "complexityanalyzer.command.resource.source_items_tip");
        for (var entry : Reference2DoubleMaps.fastIterable(sourceItems)) {
            var sourceItem = entry.getKey();
            double amount = entry.getDoubleValue();
            var sourceItemName = sourceItem.getDescription();
            var result = engine.getComplexityResult(sourceItem);
            String value = result != null ? "x%.1f (%.2f)".formatted(amount, result.getComplexity()) : "x%.1f".formatted(amount);
            output.sendSubEntry(source, "📦", sourceItemName, value, ChatFormatting.WHITE, ChatFormatting.YELLOW);
        }
        output.sendEmptyLine(source);
    }

    private static void displayAdditionalInfo(CommandSourceStack source, Item item, AnalysisEngine engine, ResourceLocation itemId, OutputManager output) {
        boolean hasRecipe = engine.hasRecipe(item);
        output.sendStatusLine(source, "ℹ", "complexityanalyzer.command.resource.additional_info", ChatFormatting.AQUA);
        if (hasRecipe) {
            output.sendSubEntry(source, "Note", "complexityanalyzer.command.resource.has_recipe_note", ChatFormatting.YELLOW, ChatFormatting.YELLOW);
            output.sendEmptyLine(source);
            output.sendClickableTip(source,
                    Component.translatable("complexityanalyzer.command.resource.full_analysis_prefix"),
                    Component.translatable("complexityanalyzer.command.resource.full_analysis_link"),
                    Component.translatable("complexityanalyzer.command.resource.full_analysis_suffix"),
                    ComplexityCommand.ROOT + " analyze item " + itemId,
                    Component.translatable("complexityanalyzer.command.resource.full_analysis_hover"));
        } else {
            output.sendSubEntry(source, "complexityanalyzer.command.resource.type_label", "complexityanalyzer.command.resource.pure_base_resource", ChatFormatting.GRAY, ChatFormatting.GREEN);
        }
        output.sendEmptyLine(source);
    }
}