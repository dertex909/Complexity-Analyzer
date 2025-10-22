package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.DepthAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;

public class TreeCommand {

    public static final int DEFAULT_MAX_DEPTH = 100;
    private enum DisplayMode { PLAYER_INSTRUCTION, ECONOMIC_COST }

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId, String mode, int maxDepth) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady() || engine.getDepthAnalyzer().isEmpty()) {
            source.sendFailure(Component.literal("§cAnalysis engine or Depth Analyzer is not ready yet!"));
            return 0;
        }

        DisplayMode displayMode = "economic".equalsIgnoreCase(mode) ? DisplayMode.ECONOMIC_COST : DisplayMode.PLAYER_INSTRUCTION;

        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cItem not found: " + itemId));
            return 0;
        }

        Item item = itemOpt.get();
        DepthAnalyzer depthAnalyzer = engine.getDepthAnalyzer().get();

        try {
            displayTree(source, item, engine, depthAnalyzer, displayMode, maxDepth);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError building tree: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error building tree for {}", itemId, e);
            return 0;
        }
    }

    private static void displayTree(CommandSourceStack source, Item item, AnalysisEngine engine, DepthAnalyzer depthAnalyzer, DisplayMode displayMode, int maxDepth) {
        String modeName = displayMode == DisplayMode.PLAYER_INSTRUCTION ? "Player View" : "Economic View";
        source.sendSuccess(() -> Component.literal("§6§l=== Crafting Tree (" + modeName + ", Depth: " + maxDepth + ") ==="), false);
        source.sendSuccess(() -> Component.literal("§7Item: §f" + item.getDescription().getString()), false);
        source.sendSuccess(() -> Component.literal(""), false);

        Map<Item, Double> baseResources = new LinkedHashMap<>();

        double initialAmount = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ? Math.ceil(1.0) : 1.0;
        displayNodeRecursive(source, item, initialAmount, 0, "", true, new HashSet<>(), engine, depthAnalyzer, baseResources, displayMode, maxDepth);

        if (!baseResources.isEmpty()) {
            source.sendSuccess(() -> Component.literal(""), false);

            if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
                source.sendSuccess(() -> Component.literal("§e--- Base Resources Needed ---"), false);
                source.sendSuccess(() -> Component.literal("§7(Total amount to gather)"), false);
                baseResources.entrySet().stream()
                        .sorted(Map.Entry.<Item, Double>comparingByValue().reversed())
                        .forEach(entry -> {
                            int amountForPlayer = entry.getValue().intValue();
                            if (amountForPlayer > 0) {
                                source.sendSuccess(() -> Component.literal(
                                        String.format("§f- %s x %d", entry.getKey().getDescription().getString(), amountForPlayer)), false);
                            }
                        });
            } else {
                source.sendSuccess(() -> Component.literal("§e--- Base Resources Needed (Economic Cost) ---"), false);
                source.sendSuccess(() -> Component.literal("§7(Precise fractional amount required)"), false);
                baseResources.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                        .forEach(entry -> source.sendSuccess(() -> Component.literal(
                                String.format("§f- %s x %.2f", entry.getKey().getDescription().getString(), entry.getValue())), false));
            }
        }
    }

    private static void displayNodeRecursive(
            CommandSourceStack source, Item item, double neededAmount, int depth, String prefix,
            boolean isLast, Set<Item> visitedOnPath, AnalysisEngine engine, DepthAnalyzer depthAnalyzer,
            Map<Item, Double> baseResources, DisplayMode displayMode, int maxDepth) {

        if (depth >= maxDepth) {
            source.sendSuccess(() -> Component.literal(prefix + (isLast ? "└─" : "├─") + " §8... (max depth reached)"), false);
            calculateBaseResourcesFor(item, neededAmount, new HashSet<>(visitedOnPath), engine, depthAnalyzer, baseResources, displayMode);
            return;
        }

        if (!visitedOnPath.add(item)) {
            source.sendSuccess(() -> Component.literal(String.format("%s§8%s §c%s §8[CYCLE]", prefix, isLast ? "└─" : "├─", item.getDescription().getString())), false);
            return;
        }

        String quantityString;
        if (displayMode == DisplayMode.PLAYER_INSTRUCTION) {
            int displayAmount = (int) Math.ceil(neededAmount);
            quantityString = displayAmount >= 1 ? String.format("§e%dx §f", displayAmount) : "";
        } else {
            quantityString = neededAmount > 0.001 ? String.format("§e%.2fx §f", neededAmount) : "";
        }

        double complexity = engine.getComplexity(item);
        Optional<RecipeNode> recipeOpt = depthAnalyzer.getRecipeToFollow(item);

        if (recipeOpt.isEmpty() || recipeOpt.get().isBaseRecipe()) {
            double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ? Math.ceil(neededAmount) : neededAmount;
            baseResources.merge(item, amountToAdd, Double::sum);

            String line = String.format("%s§8%s %s%s §7(%.2f) §a[Base]", prefix, isLast ? "└─" : "├─", quantityString, item.getDescription().getString(), complexity);
            source.sendSuccess(() -> Component.literal(line), false);
            visitedOnPath.remove(item);
            return;
        }

        String line = String.format("%s%s %s%s §7(%.2f)", prefix, isLast ? "└─" : "├─", quantityString, item.getDescription().getString(), complexity);
        source.sendSuccess(() -> Component.literal(line), false);

        RecipeNode recipe = recipeOpt.get();
        String childPrefix = prefix + (isLast ? "   " : "§8│  ");

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
                    i == ingredientItems.size() - 1, visitedOnPath, engine, depthAnalyzer, baseResources, displayMode, maxDepth);
        }
        visitedOnPath.remove(item);
    }

    private static void calculateBaseResourcesFor(Item item, double neededAmount, Set<Item> visited, AnalysisEngine engine, DepthAnalyzer depthAnalyzer, Map<Item, Double> baseResources, DisplayMode displayMode) {
        if (!visited.add(item)) return;

        Optional<RecipeNode> recipeOpt = depthAnalyzer.getRecipeToFollow(item);

        if (recipeOpt.isEmpty() || recipeOpt.get().isBaseRecipe()) {
            double amountToAdd = (displayMode == DisplayMode.PLAYER_INSTRUCTION) ? Math.ceil(neededAmount) : neededAmount;
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
                calculateBaseResourcesFor(bestVariant, totalIngredientNeeded, visited, engine, depthAnalyzer, baseResources, displayMode);
            }
        }
        visited.remove(item);
    }
}