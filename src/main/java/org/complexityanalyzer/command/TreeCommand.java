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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.*;
import org.complexityanalyzer.command.util.SharedSuggestions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.tree.CraftingTreeBuilder;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.CraftingTreeData;
import org.complexityanalyzer.data.CraftingTreeData.*;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.core.GameRegistryManager;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;

public final class TreeCommand {
    private TreeCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("tree")
                .then(Commands.argument("item", ResourceLocationArgument.id()).suggests(SharedSuggestions.ITEM).executes(ctx ->
                                TreeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "item"), "player", TreeCommand.DEFAULT_MAX_DEPTH))
                        .then(Commands.literal("depth")
                                .then(Commands.argument("max_depth", IntegerArgumentType.integer(1)).executes(ctx ->
                                                TreeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "item"), "player", IntegerArgumentType.getInteger(ctx, "max_depth")))
                                        .then(Commands.literal("mode")
                                                .then(Commands.argument("mode_type", StringArgumentType.word()).suggests((ctx, builder) ->
                                                        SharedSuggestionProvider.suggest(new String[]{"player", "economic"}, builder)).executes(ctx ->
                                                        TreeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "item"), StringArgumentType.getString(ctx, "mode_type"), IntegerArgumentType.getInteger(ctx, "max_depth")))))))
                        .then(Commands.literal("mode")
                                .then(Commands.argument("mode_type", StringArgumentType.word()).suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(new String[]{"player", "economic"}, builder)).executes(ctx ->
                                                TreeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "item"), StringArgumentType.getString(ctx, "mode_type"), TreeCommand.DEFAULT_MAX_DEPTH))
                                        .then(Commands.literal("depth")
                                                .then(Commands.argument("max_depth", IntegerArgumentType.integer(1)).executes(ctx ->
                                                        TreeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "item"), StringArgumentType.getString(ctx, "mode_type"), IntegerArgumentType.getInteger(ctx, "max_depth"))))))));
    }

    public static final int DEFAULT_MAX_DEPTH = 100;

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId, String mode, int maxDepth) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady() || engine.getDepthAnalyzer() == null) {
            output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready!"));
            return 0;
        }

        DisplayMode displayMode = "economic".equalsIgnoreCase(mode) ? DisplayMode.ECONOMIC_COST : DisplayMode.PLAYER_INSTRUCTION;

        Item item = GameRegistryManager.getItem(itemId);
        if (item == Items.AIR && !itemId.equals(ResourceLocation.parse("minecraft:air"))) {
            output.sendFailure(source, Component.literal("❌ Item not found: ")
                    .append(Component.literal(itemId.toString()).withStyle(ChatFormatting.YELLOW)));
            return 0;
        }

        try {
            CraftingTreeBuilder builder = new CraftingTreeBuilder(engine);
            CraftingTreeData treeData = builder.build(item, displayMode, maxDepth);
            renderTree(source, treeData, itemId, output, engine);
            return 1;
        } catch (Exception e) {
            output.sendFailure(source, Component.literal("❌ Error building crafting tree: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error building tree for {}", itemId, e);
            return 0;
        }
    }

    private static void renderTree(CommandSourceStack source, CraftingTreeData data,
                                   ResourceLocation itemId, OutputManager output, AnalysisEngine engine) {
        renderHeader(source, data, output, engine);
        renderTreeNode(source, data.getRoot(), "  ", true, data.getDisplayMode(), output, engine);
        renderFooter(source, data, itemId, output);
    }

    private static void renderHeader(CommandSourceStack source, CraftingTreeData data,
                                     OutputManager output, AnalysisEngine engine) {
        String itemName = data.getRootItem().getDescription().getString();
        double complexity = engine.getComplexity(data.getRootItem());

        output.sendEmptyLine(source);
        output.sendHeader(source, "🌳", "CRAFTING TREE ANALYSIS", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🎯", "Target", itemName, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "⚖", "Complexity", String.format("%.2f", complexity), ChatFormatting.GRAY, getComplexityColor(complexity));

        String modeIcon = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? "👤" : "💰";
        String modeName = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? "Player View" : "Economic View";

        ChatFormatting modeColor = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? ChatFormatting.AQUA : ChatFormatting.GOLD;

        output.sendEntry(source, modeIcon, "Mode", modeName, ChatFormatting.GRAY, modeColor);
        output.sendEntry(source, "🔍", "Max Depth", String.valueOf(data.getMaxDepth()), ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendEmptyLine(source);
        output.sendThinSeparator(source);
        output.sendEmptyLine(source);
    }

    private static void renderTreeNode(CommandSourceStack source, TreeNode node, String prefix, boolean isLast,
                                       DisplayMode mode, OutputManager output, AnalysisEngine engine) {
        String branchChar = isLast ? "└─ " : "├─ ";
        MutableComponent line = Component.literal(prefix).append(Component.literal(branchChar).withStyle(ChatFormatting.DARK_GRAY));
        line.append(formatNode(node, mode, engine));
        output.sendInfo(source, line);

        if (node.getType() == NodeType.CRAFTING) {
            String childPrefix = prefix + (isLast ? "   " : "│  ");
            int totalChildren = node.getItemChildren().size() + node.getFluidChildren().size();
            int currentIndex = 0;
            for (TreeNode child : node.getItemChildren()) {
                currentIndex++;
                renderTreeNode(source, child, childPrefix, currentIndex == totalChildren, mode, output, engine);
            }
            for (FluidNode fluid : node.getFluidChildren()) {
                currentIndex++;
                boolean isLastChild = (currentIndex == totalChildren);
                String fluidBranch = isLastChild ? "└─ " : "├─ ";
                String displayAmount = formatFluidAmount(fluid.getAmount(), mode);
                String fluidDisplayName = getCleanResourceName(fluid.getFluidName());
                MutableComponent fluidLine = Component.literal(childPrefix)
                        .append(Component.literal(fluidBranch).withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("💧 ").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(displayAmount + " ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(fluidDisplayName).withStyle(ChatFormatting.WHITE));

                fluidLine.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, createFluidHoverText(fluid, node))));
                output.sendInfo(source, fluidLine);
            }
        }
    }

    private static String formatFluidAmount(double amount, DisplayMode mode) {
        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            return (int) Math.ceil(amount) + "mB";
        } else {
            return String.format("%.2fmB", amount);
        }
    }

    private static String getCleanResourceName(String resourceId) {
        int colonIdx = resourceId.indexOf(':');
        String cleanName = (colonIdx != -1) ? resourceId.substring(colonIdx + 1) : resourceId;

        char[] chars = cleanName.toCharArray();
        StringBuilder result = new StringBuilder(chars.length);
        boolean capitalizeNext = true;
        boolean lastWasSpace = false;

        for (char c : chars) {
            if (c == '_' || c == '-' || Character.isWhitespace(c)) {
                if (!lastWasSpace && !result.isEmpty()) {
                    result.append(' ');
                    lastWasSpace = true;
                }
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(c);
                }
                lastWasSpace = false;
            }
        }
        return result.toString().trim();
    }

    private static Component createFluidHoverText(FluidNode fluid, TreeNode parentNode) {
        MutableComponent hover = Component.empty();

        hover.append(Component.literal("💧 Fluid Resource").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        hover.append(Component.literal("\nName: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(getCleanResourceName(fluid.getFluidName())).withStyle(ChatFormatting.WHITE));

        hover.append(Component.literal("\nAmount: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format("%.2f mB", fluid.getAmount())).withStyle(ChatFormatting.YELLOW));

        if (parentNode.getMachineType() != null && !parentNode.getMachineType().isEmpty()) hover
                .append(Component.literal("\n🏭 Produced in: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(parentNode.getMachineType()).withStyle(ChatFormatting.GREEN));

        return hover;
    }

    private static MutableComponent formatNode(TreeNode node, DisplayMode mode, AnalysisEngine engine) {
        String quantityString;
        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            int displayAmount = (int) Math.ceil(node.getNeededAmount());
            quantityString = displayAmount >= 1 ? displayAmount + "x " : "";
        } else {
            quantityString = node.getNeededAmount() > 0.001 ? String.format("%.2fx ", node.getNeededAmount()) : "";
        }

        ChatFormatting complexityColor = getComplexityColor(node.getComplexity());
        MutableComponent component = Component.empty();

        switch (node.getType()) {
            case NO_DATA:
                component.append(Component.literal("❌ ").withStyle(ChatFormatting.RED))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(node.getItemName()).withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
                break;

            case MAX_DEPTH_REACHED:
                component.append(Component.literal("... ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))
                        .append(Component.literal("[DEPTH LIMIT]").withStyle(ChatFormatting.GRAY));
                break;

            case CYCLE:
                component.append(Component.literal("🔁 ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(node.getItemName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
                break;

            case BASE_RESOURCE:
                component.append(Component.literal("⛏ ").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(node.getItemName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
                break;

            case CRAFTING:
                component.append(Component.literal("🔨 ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(node.getItemName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
                break;
        }

        component.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, createDetailedHoverText(node, engine))));
        return component;
    }

    private static Component createDetailedHoverText(TreeNode node, AnalysisEngine engine) {
        MutableComponent hover = Component.empty();

        switch (node.getType()) {
            case NO_DATA:
                hover.append(Component.literal("❌ No Recipe Data").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                hover.append(Component.literal("\nThis item has no known recipes").withStyle(ChatFormatting.GRAY));
                break;

            case MAX_DEPTH_REACHED:
                hover.append(Component.literal("🔍 Depth Limit Reached").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
                hover.append(Component.literal("\nIncrease depth to see more").withStyle(ChatFormatting.GRAY));
                break;

            case CYCLE:
                hover.append(Component.literal("🔁 Recursive Recipe").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                hover.append(Component.literal("\nThis item creates a crafting loop").withStyle(ChatFormatting.GRAY));
                break;

            case BASE_RESOURCE:
                hover.append(Component.literal("⛏ Base Resource").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

                boolean wouldCreateCycle = (Boolean) node.getMetadata().getOrDefault("wouldCreateCycle", false);
                if (!wouldCreateCycle) {
                    String sourceTypeName = (String) node.getMetadata().get("sourceTypeName");
                    String specifier = (String) node.getMetadata().get("sourceSpecifier");

                    if (sourceTypeName != null) {
                        hover.append(Component.literal("\n📍 Source: ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(sourceTypeName).withStyle(ChatFormatting.AQUA));

                        if (specifier != null && !specifier.isBlank()) hover
                                .append(Component.literal("\n   ").withStyle(ChatFormatting.DARK_GRAY))
                                .append(Component.literal(specifier).withStyle(ChatFormatting.YELLOW));
                    }
                } else {
                    hover.append(Component.literal("\n⚠ Treated as base to avoid cycle").withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
                }
                break;

            case CRAFTING:
                hover.append(Component.literal("🔨 Crafting Recipe").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

                String machineType = node.getMachineType();
                if (machineType != null && !machineType.isEmpty()) hover
                        .append(Component.literal("\n🏭 Machine: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(machineType).withStyle(ChatFormatting.AQUA));
                break;
        }

        if (node.getItem() != null) {
            ItemComplexity complexity = engine.getComplexityResult(node.getItem());
            if (complexity != null) {
                hover.append(Component.literal("\n\n📊 Complexity: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format("%.2f", complexity.getComplexity())).withStyle(getComplexityColor(complexity.getComplexity()), ChatFormatting.BOLD));

                if (complexity.getOptimalRecipe() != null) hover
                        .append(Component.literal("\n✅ Has optimal recipe").withStyle(ChatFormatting.DARK_GREEN));
            }
        }

        return hover;
    }

    private static void renderFooter(CommandSourceStack source, CraftingTreeData data,
                                     ResourceLocation itemId, OutputManager output) {
        output.sendEmptyLine(source);
        output.sendThinSeparator(source);
        output.sendEmptyLine(source);
        renderStatistics(source, data.getStatistics(), output);
        renderBaseResources(source, data, output);
        renderTips(source, data, itemId, output);
        output.sendFooter(source);
    }

    private static void renderStatistics(CommandSourceStack source, TreeStatistics stats, OutputManager output) {
        output.sendStatusLine(source, "📊", "Tree Statistics", ChatFormatting.AQUA);
        output.sendSubEntry(source, "Total Nodes", String.valueOf(stats.getTotalNodes()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
        output.sendSubEntry(source, "Unique Items", String.valueOf(stats.getUniqueItems()), ChatFormatting.DARK_GRAY, ChatFormatting.AQUA);
        output.sendSubEntry(source, "Crafting Steps", String.valueOf(stats.getCraftingSteps()), ChatFormatting.DARK_GRAY, ChatFormatting.GOLD);
        output.sendSubEntry(source, "Base Resources", String.valueOf(stats.getBaseResourcesCount()), ChatFormatting.DARK_GRAY, ChatFormatting.GREEN);

        if (stats.getCyclesDetected() > 0) output
                .sendSubEntry(source, "⚠ Cycles Detected", String.valueOf(stats.getCyclesDetected()), ChatFormatting.YELLOW, ChatFormatting.RED);

        output.sendEmptyLine(source);
    }

    private static void renderBaseResources(CommandSourceStack source, CraftingTreeData data, OutputManager output) {
        Reference2DoubleMap<Item> baseResources = data.getBaseResources();

        if (baseResources.isEmpty()) {
            output.sendTip(source, "No base resources needed (item might be unobtainable)");
            return;
        }

        DisplayMode mode = data.getDisplayMode();
        String title = mode == DisplayMode.PLAYER_INSTRUCTION ? "Shopping List (What to Gather)" : "Precise Resource Requirements";
        output.sendStatusLine(source, "🎒", title, ChatFormatting.GREEN);

        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            output.sendTip(source, "Rounded up for actual gameplay");
        } else {
            output.sendTip(source, "Exact fractional amounts");
        }

        output.sendEmptyLine(source);

        for (var entry : Reference2DoubleMaps.fastIterable(baseResources)) {
            Item item = entry.getKey();
            double amount = entry.getDoubleValue();
            String itemName = item.getDescription().getString();

            if (mode == DisplayMode.PLAYER_INSTRUCTION) {
                int amountForPlayer = (int) Math.ceil(amount);
                if (amountForPlayer > 0) {
                    int maxStackSize = item.getDefaultInstance().getMaxStackSize();
                    String stackInfo = getStackVisualization(amountForPlayer, maxStackSize);
                    output.sendSubEntry(source, "✓", itemName, amountForPlayer + " " + stackInfo, ChatFormatting.WHITE, ChatFormatting.YELLOW);
                }
            } else {
                output.sendSubEntry(source, itemName, String.format("%.2f", amount), ChatFormatting.WHITE, ChatFormatting.AQUA);
            }
        }

        output.sendEmptyLine(source);
    }

    private static void renderTips(CommandSourceStack source, CraftingTreeData data, ResourceLocation itemId,
                                   OutputManager output) {
        output.sendStatusLine(source, "💡", "Tips & Options", ChatFormatting.YELLOW);

        DisplayMode mode = data.getDisplayMode();
        TreeStatistics stats = data.getStatistics();

        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            String economicCommand = "/complexity tree " + itemId + " mode economic";
            output.sendClickableTip(source, "Try ", "economic mode", " for precise calculations", economicCommand, "Click to switch to economic view");
        } else {
            String playerCommand = "/complexity tree " + itemId + " mode player";
            output.sendClickableTip(source, "Try ", "player mode", " for gameplay-friendly view", playerCommand, "Click to switch to player view");
        }

        if (stats.getCyclesDetected() > 0) {
            output.sendStatusLine(source, "⚠", "Cyclic dependencies detected!", ChatFormatting.RED);
            output.sendTip(source, "This may indicate mod conflicts or loop recipes");
        }

        if (stats.getTotalNodes() > 50) {
            output.sendClickableTip(source, "Complex tree! Use ", "limited depth", " to improve performance", "/complexity tree " + itemId + " depth 5", "Click to set depth limit to 5");
        }

        output.sendEmptyLine(source);
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
            return "(" + (int) ((amount / (double) maxStackSize) * 100) + "%)";
        } else if (remainder == 0) {
            return "(" + stacks + " stacks)";
        } else {
            return "(" + stacks + " stacks + " + remainder + ")";
        }
    }
}