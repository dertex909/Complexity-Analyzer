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
import org.complexityanalyzer.analyzer.DepthAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class TreeCommand {

    public static final int DEFAULT_MAX_DEPTH = 100;
    private enum DisplayMode { PLAYER_INSTRUCTION, ECONOMIC_COST }

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId, String mode, int maxDepth) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady() || engine.getDepthAnalyzer().isEmpty()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine or Depth Analyzer is not ready!")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        DisplayMode displayMode = "economic".equalsIgnoreCase(mode) ?
                DisplayMode.ECONOMIC_COST : DisplayMode.PLAYER_INSTRUCTION;

        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemOpt.isEmpty()) {
            output.sendFailure(source,
                    Component.literal("❌ Item not found: ")
                            .append(Component.literal(itemId.toString())
                                    .withStyle(ChatFormatting.YELLOW)));
            return 0;
        }

        Item item = itemOpt.get();
        DepthAnalyzer depthAnalyzer = engine.getDepthAnalyzer().get();

        try {
            displayTree(source, item, engine, depthAnalyzer, displayMode, maxDepth, output, itemId);
            return 1;
        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ Error building crafting tree: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error building tree for {}", itemId, e);
            return 0;
        }
    }

    private static void displayTree(
            CommandSourceStack source,
            Item item,
            AnalysisEngine engine,
            DepthAnalyzer depthAnalyzer,
            DisplayMode displayMode,
            int maxDepth,
            OutputManager output,
            ResourceLocation itemId
    ) {
        String itemName = item.getDescription().getString();
        double complexity = engine.getComplexity(item);

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("🌳 ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("CRAFTING TREE ANALYSIS")
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        ChatFormatting complexityColor = getComplexityColor(complexity);

        output.sendInfo(source,
                Component.literal("  🎯 Target: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(itemName)
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("  ⚖ Complexity: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format("%.2f", complexity))
                                .withStyle(complexityColor, ChatFormatting.BOLD)));

        String modeIcon = displayMode == DisplayMode.PLAYER_INSTRUCTION ? "👤" : "💰";
        String modeName = displayMode == DisplayMode.PLAYER_INSTRUCTION ? "Player View" : "Economic View";
        ChatFormatting modeColor = displayMode == DisplayMode.PLAYER_INSTRUCTION ?
                ChatFormatting.AQUA : ChatFormatting.GOLD;

        output.sendInfo(source,
                Component.literal("  " + modeIcon + " Mode: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(modeName)
                                .withStyle(modeColor)));

        output.sendInfo(source,
                Component.literal("  🔍 Max Depth: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(maxDepth))
                                .withStyle(ChatFormatting.YELLOW)));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("  ─────────────────────────────")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        Map<Item, Double> baseResources = new LinkedHashMap<>();
        Set<Item> uniqueItems = new HashSet<>();
        TreeStats stats = new TreeStats();

        double initialAmount = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ? Math.ceil(1.0) : 1.0;

        displayNodeRecursive(source, item, initialAmount, 0, "  ", true, new HashSet<>(),
                engine, depthAnalyzer, baseResources, displayMode, maxDepth, uniqueItems, stats, output);

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("  ─────────────────────────────")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        displayStatistics(source, stats, uniqueItems, output);

        displayBaseResources(source, baseResources, displayMode, engine, output);

        displayTips(source, displayMode, maxDepth, stats, output, itemId);

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void displayNodeRecursive(
            CommandSourceStack source,
            Item item,
            double neededAmount,
            int depth,
            String prefix,
            boolean isLast,
            Set<Item> visitedOnPath,
            AnalysisEngine engine,
            DepthAnalyzer depthAnalyzer,
            Map<Item, Double> baseResources,
            DisplayMode displayMode,
            int maxDepth,
            Set<Item> uniqueItems,
            TreeStats stats,
            OutputManager output
    ) {
        uniqueItems.add(item);
        stats.totalNodes++;

        if (depth >= maxDepth) {
            MutableComponent line = Component.literal(prefix)
                    .append(Component.literal(isLast ? "└─ " : "├─ ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("... ")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))
                    .append(Component.literal("[MAX DEPTH REACHED]")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

            output.sendInfo(source, line);
            calculateBaseResourcesFor(item, neededAmount, new HashSet<>(visitedOnPath),
                    engine, depthAnalyzer, baseResources, displayMode);
            return;
        }

        if (!visitedOnPath.add(item)) {
            stats.cyclesDetected++;
            MutableComponent line = Component.literal(prefix)
                    .append(Component.literal(isLast ? "└─ " : "├─ ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("🔄 ")
                            .withStyle(ChatFormatting.RED))
                    .append(Component.literal(item.getDescription().getString())
                            .withStyle(ChatFormatting.RED))
                    .append(Component.literal(" [CYCLE]")
                            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));

            output.sendInfo(source, line);
            return;
        }

        String quantityString;
        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            int displayAmount = (int) Math.ceil(neededAmount);
            quantityString = displayAmount >= 1 ? displayAmount + "x " : "";
        } else {
            quantityString = neededAmount > 0.001 ? String.format("%.2fx ", neededAmount) : "";
        }

        double complexity = engine.getComplexity(item);
        ChatFormatting complexityColor = getComplexityColor(complexity);
        Optional<RecipeNode> recipeOpt = depthAnalyzer.getRecipeToFollow(item);

        if (recipeOpt.isEmpty() || recipeOpt.get().isBaseRecipe()) {
            stats.baseResourcesCount++;
            double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ?
                    Math.ceil(neededAmount) : neededAmount;
            baseResources.merge(item, amountToAdd, Double::sum);

            MutableComponent line = Component.literal(prefix)
                    .append(Component.literal(isLast ? "└─ " : "├─ ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("⛏ ")
                            .withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(quantityString)
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(item.getDescription().getString())
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" (")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(String.format("%.2f", complexity))
                            .withStyle(complexityColor))
                    .append(Component.literal(") ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("[BASE]")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

            output.sendInfo(source, line);
            visitedOnPath.remove(item);
            return;
        }

        stats.craftingSteps++;

        MutableComponent line = Component.literal(prefix)
                .append(Component.literal(isLast ? "└─ " : "├─ ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("🔨 ")
                        .withStyle(ChatFormatting.GOLD))
                .append(Component.literal(quantityString)
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(item.getDescription().getString())
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.format("%.2f", complexity))
                        .withStyle(complexityColor))
                .append(Component.literal(")")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source, line);

        RecipeNode recipe = recipeOpt.get();
        String childPrefix = prefix + (isLast ? "   " : "│  ");

        double craftOperations;
        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            int neededPlayerAmount = (int) Math.ceil(neededAmount);
            craftOperations = Math.ceil((double) neededPlayerAmount / recipe.getResultCount());
        } else {
            craftOperations = neededAmount / recipe.getResultCount();
        }

        Map<Item, Integer> ingredientsForOneCraft = new LinkedHashMap<>();
        for (IngredientSlot slot : recipe.getIngredients()) {
            slot.getVariants().stream()
                    .min(Comparator.comparingDouble(engine::getComplexity))
                    .ifPresent(bestVariant -> ingredientsForOneCraft.merge(bestVariant, slot.getCount(), Integer::sum));
        }

        List<Item> ingredientItems = new ArrayList<>(ingredientsForOneCraft.keySet());
        for (int i = 0; i < ingredientItems.size(); i++) {
            Item ingredientItem = ingredientItems.get(i);
            int countForOneCraft = ingredientsForOneCraft.get(ingredientItem);
            double totalIngredientNeeded = craftOperations * countForOneCraft;

            displayNodeRecursive(source, ingredientItem, totalIngredientNeeded, depth + 1, childPrefix,
                    i == ingredientItems.size() - 1, visitedOnPath, engine, depthAnalyzer,
                    baseResources, displayMode, maxDepth, uniqueItems, stats, output);
        }
        visitedOnPath.remove(item);
    }

    private static void displayStatistics(
            CommandSourceStack source,
            TreeStats stats,
            Set<Item> uniqueItems,
            OutputManager output
    ) {
        output.sendInfo(source,
                Component.literal("  📊 ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Tree Statistics")
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("    Total Nodes: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(stats.totalNodes))
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source,
                Component.literal("    Unique Items: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(uniqueItems.size()))
                                .withStyle(ChatFormatting.AQUA)));

        output.sendInfo(source,
                Component.literal("    Crafting Steps: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(stats.craftingSteps))
                                .withStyle(ChatFormatting.GOLD)));

        output.sendInfo(source,
                Component.literal("    Base Resources: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(stats.baseResourcesCount))
                                .withStyle(ChatFormatting.GREEN)));

        if (stats.cyclesDetected > 0) {
            output.sendInfo(source,
                    Component.literal("    ⚠ Cycles Detected: ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(String.valueOf(stats.cyclesDetected))
                                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayBaseResources(
            CommandSourceStack source,
            Map<Item, Double> baseResources,
            DisplayMode displayMode,
            AnalysisEngine ignoredEngine,
            OutputManager output
    ) {
        if (baseResources.isEmpty()) {
            output.sendInfo(source,
                    Component.literal("  ⚠ No base resources needed (item might be unobtainable)")
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
            return;
        }

        String title = displayMode == DisplayMode.PLAYER_INSTRUCTION ?
                "Shopping List (What to Gather)" : "Precise Resource Requirements";

        output.sendInfo(source,
                Component.literal("  🎒 ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(title)
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            output.sendInfo(source,
                    Component.literal("    (Rounded up for actual gameplay)")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        } else {
            output.sendInfo(source,
                    Component.literal("    (Exact fractional amounts)")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        output.sendInfo(source, Component.literal(""));

        baseResources.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    Item item = entry.getKey();
                    double amount = entry.getValue();
                    String itemName = item.getDescription().getString();

                    if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
                        int amountForPlayer = (int) amount;
                        if (amountForPlayer > 0) {
                            int maxStackSize = item.getDefaultInstance().getMaxStackSize();
                            String stackInfo = getStackVisualization(amountForPlayer, maxStackSize);

                            MutableComponent resourceLine = Component.literal("    ✓ ")
                                    .withStyle(ChatFormatting.GREEN)
                                    .append(Component.literal(itemName)
                                            .withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal(" x")
                                            .withStyle(ChatFormatting.DARK_GRAY))
                                    .append(Component.literal(String.valueOf(amountForPlayer))
                                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                                    .append(Component.literal(" " + stackInfo)
                                            .withStyle(ChatFormatting.DARK_GRAY));

                            output.sendInfo(source, resourceLine);
                        }
                    } else {
                        MutableComponent resourceLine = Component.literal("    • ")
                                .withStyle(ChatFormatting.DARK_GRAY)
                                .append(Component.literal(itemName)
                                        .withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(" x")
                                        .withStyle(ChatFormatting.DARK_GRAY))
                                .append(Component.literal(String.format("%.2f", amount))
                                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

                        output.sendInfo(source, resourceLine);
                    }
                });

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayTips(
            CommandSourceStack source,
            DisplayMode displayMode,
            int ignoredMaxDepth,
            TreeStats stats,
            OutputManager output,
            ResourceLocation itemId
    ) {
        output.sendInfo(source,
                Component.literal("  💡 ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("Tips & Options")
                                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            String economicCommand = "/complexity tree " + itemId + " mode economic";
            MutableComponent tipLine = Component.literal("    • Try ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("economic mode")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.UNDERLINE)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, economicCommand))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("Click to switch to economic view")
                                                    .withStyle(ChatFormatting.AQUA)))))
                    .append(Component.literal(" for precise calculations")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendInfo(source, tipLine);
        } else {
            String playerCommand = "/complexity tree " + itemId + " mode player";
            MutableComponent tipLine = Component.literal("    • Try ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("player mode")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, playerCommand))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("Click to switch to player view")
                                                    .withStyle(ChatFormatting.GREEN)))))
                    .append(Component.literal(" for gameplay-friendly view")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendInfo(source, tipLine);
        }

        if (stats.totalNodes > 50) {
            String depthCommand = "/complexity tree " + itemId + " depth 5";
            MutableComponent tipLine = Component.literal("    • Complex tree! Use ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("limited depth")
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, depthCommand))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("Limit tree depth for better readability")
                                                    .withStyle(ChatFormatting.GOLD)))))
                    .append(Component.literal(" for simpler view")
                            .withStyle(ChatFormatting.DARK_GRAY));

            output.sendInfo(source, tipLine);
        }

        if (stats.cyclesDetected > 0) {
            output.sendInfo(source,
                    Component.literal("    ⚠ Cyclic dependencies detected!")
                            .withStyle(ChatFormatting.RED));
            output.sendInfo(source,
                    Component.literal("      This may indicate mod conflicts or loop recipes")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static void calculateBaseResourcesFor(
            Item item,
            double neededAmount,
            Set<Item> visited,
            AnalysisEngine engine,
            DepthAnalyzer depthAnalyzer,
            Map<Item, Double> baseResources,
            DisplayMode displayMode
    ) {
        if (!visited.add(item)) return;

        Optional<RecipeNode> recipeOpt = depthAnalyzer.getRecipeToFollow(item);

        if (recipeOpt.isEmpty() || recipeOpt.get().isBaseRecipe()) {
            double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ?
                    Math.ceil(neededAmount) : neededAmount;
            baseResources.merge(item, amountToAdd, Double::sum);
            visited.remove(item);
            return;
        }

        RecipeNode recipe = recipeOpt.get();
        double craftOperations;
        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            int neededPlayerAmount = (int) Math.ceil(neededAmount);
            craftOperations = Math.ceil((double) neededPlayerAmount / recipe.getResultCount());
        } else {
            craftOperations = neededAmount / recipe.getResultCount();
        }

        for (IngredientSlot slot : recipe.getIngredients()) {
            Item bestVariant = slot.getVariants().stream()
                    .min(Comparator.comparingDouble(engine::getComplexity))
                    .orElse(null);

            if (bestVariant != null) {
                double totalIngredientNeeded = craftOperations * slot.getCount();
                calculateBaseResourcesFor(bestVariant, totalIngredientNeeded, visited,
                        engine, depthAnalyzer, baseResources, displayMode);
            }
        }
        visited.remove(item);
    }

    private static class TreeStats {
        int totalNodes = 0;
        int craftingSteps = 0;
        int baseResourcesCount = 0;
        int cyclesDetected = 0;
    }

    private static ChatFormatting getComplexityColor(double complexity) {
        if (complexity >= 100) return ChatFormatting.DARK_RED;
        if (complexity >= 50) return ChatFormatting.RED;
        if (complexity >= 30) return ChatFormatting.GOLD;
        if (complexity >= 10) return ChatFormatting.YELLOW;
        if (complexity >= 5) return ChatFormatting.GREEN;
        return ChatFormatting.DARK_GREEN;
    }

    private static String getStackVisualization(int amount, int maxStackSize) {
        if (maxStackSize <= 0) return "";

        int stacks = amount / maxStackSize;
        int remainder = amount % maxStackSize;

        if (stacks == 0) {
            return String.format("(%.0f%%)", (amount / (double) maxStackSize) * 100);
        } else if (remainder == 0) {
            return String.format("(%d stacks)", stacks);
        } else {
            return String.format("(%d stacks + %d)", stacks, remainder);
        }
    }
}