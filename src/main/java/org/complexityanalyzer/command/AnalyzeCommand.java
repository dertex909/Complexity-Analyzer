package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.data.PathType;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class AnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet! Current state: " + engine.getCurrentState()));
            return 0;
        }

        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cItem not found: " + itemId));
            return 0;
        }

        Item item = itemOpt.get();

        try {
            Optional<ItemComplexity> optimalOpt = engine.getComplexityResult(item, PathType.OPTIMAL);
            if (optimalOpt.isEmpty()) {
                source.sendFailure(Component.literal("§cFailed to analyze item: " + itemId));
                return 0;
            }

            displayAnalysis(source, item, optimalOpt.get(), engine);
            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError analyzing item: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error analyzing item {}", itemId, e);
            return 0;
        }
    }

    private static void displayAnalysis(
            CommandSourceStack source,
            Item item,
            ItemComplexity optimal,
            AnalysisEngine engine
    ) {
        String itemName = item.getDescription().getString();

        source.sendSuccess(() -> Component.literal("§6§l=== Complexity Analysis ==="), false);
        source.sendSuccess(() -> Component.literal("§7Item: §f" + itemName), false);
        source.sendSuccess(() -> Component.literal(""), false);

        displayMainInfo(source, optimal);
        displaySourceInfo(source, item, optimal, engine);
        displayStatus(source, optimal);
    }

    private static void displayMainInfo(CommandSourceStack source, ItemComplexity optimal) {
        ComplexityCategory category = optimal.getCategory();

        source.sendSuccess(() -> Component.literal("§e[Complexity]"), false);

        String categoryText = String.format("§7  Category: %s%s",
                category.getColor(),
                category.getDisplayName()
        );
        source.sendSuccess(() -> Component.literal(categoryText), false);

        source.sendSuccess(() -> Component.literal(String.format(
                "§7  Value: §a%.2f", optimal.getComplexity()
        )), false);

        source.sendSuccess(() -> Component.literal(""), false);
    }

    private record SourceWithCost(BaseResourceData data, double fullCost) {}

    private static void displaySourceInfo(
            CommandSourceStack source,
            Item item,
            ItemComplexity optimal,
            AnalysisEngine engine
    ) {
        source.sendSuccess(() -> Component.literal("§e[Source Info]"), false);

        if (optimal.hasRecipe()) {
            source.sendSuccess(() -> Component.literal("§7  §aHas Recipe:§f Yes"), false);
            source.sendSuccess(() -> Component.literal(String.format("§7    Crafting Depth: §f%d", optimal.getDepth())), false);
            source.sendSuccess(() -> Component.literal(String.format("§7    Used in Recipes: §f%d", engine.getUsageCount(item))), false);
        } else {
            source.sendSuccess(() -> Component.literal("§7  §cHas Recipe:§f No (Base Resource)"), false);
        }

        List<BaseResourceData> allSources = engine.findAllSourcesForItem(item);
        if (!allSources.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7  §eKnown Alternative Sources:"), false);

            allSources.stream()
                    .map(data -> {
                        double fullEstimatedCost = data.getBaseFactor();
                        if (!data.getSourceItems().isEmpty()) {
                            for (var entry : data.getSourceItems().entrySet()) {
                                Item sourceItem = entry.getKey();
                                double amount = entry.getValue();

                                Optional<ItemComplexity> sourceComplexity = engine.getComplexityResult(sourceItem, PathType.OPTIMAL);
                                if (sourceComplexity.isPresent() && sourceComplexity.get().isValid()) {
                                    fullEstimatedCost += sourceComplexity.get().getComplexity() * amount;
                                } else {
                                    fullEstimatedCost = Double.POSITIVE_INFINITY;
                                    break;
                                }
                            }
                        }
                        return new SourceWithCost(data, fullEstimatedCost);
                    })
                    .sorted(Comparator.comparingDouble(SourceWithCost::fullCost))
                    .forEach(swc -> {
                        String costString = Double.isInfinite(swc.fullCost()) ? "§cInfinity" : String.format("§a%.2f", swc.fullCost());

                        source.sendSuccess(() -> Component.literal(
                                String.format("§7    - §f%s (Est. Cost: %s§7): §f%s",
                                        swc.data().getSourceType().getDisplayName(),
                                        costString,
                                        swc.data().getDetails())
                        ), false);
                    });
        }

        source.sendSuccess(() -> Component.literal(""), false);
    }

    private static void displayStatus(CommandSourceStack source, ItemComplexity optimal) {
        source.sendSuccess(() -> Component.literal("§e[Status]"), false);

        boolean isValid = optimal.isValid();
        source.sendSuccess(() -> Component.literal(String.format(
                "§7  Valid: %s", isValid ? "§aYes" : "§cNo"
        )), false);

        if (optimal.hasCycle()) {
            source.sendSuccess(() -> Component.literal("§7  Warning: §cCyclic dependency detected!"), false);
        }

        if (optimal.getErrorMessage() != null) {
            source.sendSuccess(() -> Component.literal("§7  Error: §c" + optimal.getErrorMessage()), false);
        }

        if (optimal.hasRecipe()) {
            source.sendSuccess(() -> Component.literal(""), false);
            source.sendSuccess(() -> Component.literal("§7Tip: Use §f/complexity tree <item> §7to see crafting tree"), false);
        }
    }
}