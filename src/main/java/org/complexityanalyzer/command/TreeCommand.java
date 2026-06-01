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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.complexityanalyzer.command.util.SharedSuggestions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.tree.CraftingTreeBuilder;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.CraftingTreeData;
import org.complexityanalyzer.data.CraftingTreeData.*;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.harvest.ItemStackIdentity;

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
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady() || engine.getDepthAnalyzer() == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.tree.not_ready"));
            return 0;
        }

        var displayMode = "economic".equalsIgnoreCase(mode) ? DisplayMode.ECONOMIC_COST : DisplayMode.PLAYER_INSTRUCTION;

        var item = GameRegistryManager.getItem(itemId);
        if (item == Items.AIR && !itemId.equals(ResourceLocation.parse("minecraft:air"))) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.tree.item_not_found", itemId.toString()));
            return 0;
        }

        try {
            var builder = new CraftingTreeBuilder(engine);
            var treeData = builder.build(item, displayMode, maxDepth);
            renderTree(source, treeData, itemId, output, engine);
            return 1;
        } catch (Exception e) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.tree.failed", e.getMessage()));
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
        var itemName = data.getRootItem().getDescription();
        double complexity = engine.getComplexity(data.getRootItem());

        output.sendEmptyLine(source);
        output.sendHeader(source, "🌳", "complexityanalyzer.command.tree.header", ChatFormatting.GREEN);
        output.sendEmptyLine(source);
        output.sendEntry(source, "🎯", "complexityanalyzer.command.tree.target_label", itemName, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "⚖", "complexityanalyzer.command.tree.complexity_label", String.format("%.2f", complexity), ChatFormatting.GRAY, getComplexityColor(complexity));

        String modeKey = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? "complexityanalyzer.command.tree.mode.player" : "complexityanalyzer.command.tree.mode.economic";
        var modeName = Component.translatable(modeKey);

        var modeColor = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? ChatFormatting.AQUA : ChatFormatting.GOLD;

        output.sendEntry(source, data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? "👤" : "💰", "complexityanalyzer.command.tree.mode_label", modeName, ChatFormatting.GRAY, modeColor);
        output.sendEntry(source, "🔍", "complexityanalyzer.command.tree.max_depth_label", String.valueOf(data.getMaxDepth()), ChatFormatting.GRAY, ChatFormatting.YELLOW);
        output.sendEmptyLine(source);
        output.sendThinSeparator(source);
        output.sendEmptyLine(source);
    }

    private static void renderTreeNode(CommandSourceStack source, TreeNode node, String prefix, boolean isLast,
                                       DisplayMode mode, OutputManager output, AnalysisEngine engine) {
        String branchChar = isLast ? "└─ " : "├─ ";
        var line = Component.literal(prefix).append(Component.literal(branchChar).withStyle(ChatFormatting.DARK_GRAY));
        line.append(formatNode(node, mode, engine));
        output.sendInfo(source, line);

        if (node.getType() == NodeType.CRAFTING) {
            String childPrefix = prefix + (isLast ? "   " : "│  ");
            int totalChildren = node.getItemChildren().size() + node.getFluidChildren().size();
            int currentIndex = 0;
            for (var child : node.getItemChildren()) {
                currentIndex++;
                renderTreeNode(source, child, childPrefix, currentIndex == totalChildren, mode, output, engine);
            }
            for (var fluid : node.getFluidChildren()) {
                currentIndex++;
                boolean isLastChild = (currentIndex == totalChildren);
                String fluidBranch = isLastChild ? "└─ " : "├─ ";
                String displayAmount = formatFluidAmount(fluid.getAmount(), mode);
                var fluidDisplayName = fluid.getFluidName();
                var fluidLine = Component.literal(childPrefix)
                        .append(Component.literal(fluidBranch).withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("💧 ").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(displayAmount + " ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(fluidDisplayName.copy().withStyle(ChatFormatting.WHITE));

                fluidLine.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, createFluidHoverText(fluid, node))));
                output.sendInfo(source, fluidLine);
            }
        }
    }

    private static String formatFluidAmount(double amount, DisplayMode mode) {
        var suffix = Component.translatable("complexityanalyzer.unit.fluid.millibuckets");
        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            return (int) Math.ceil(amount) + suffix.getString();
        } else {
            return String.format("%.2f%s", amount, suffix.getString());
        }
    }

    private static Component createFluidHoverText(FluidNode fluid, TreeNode parentNode) {
        var hover = Component.empty();

        hover.append(Component.translatable("complexityanalyzer.command.tree.fluid_resource").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        hover.append(Component.literal("\n").append(Component.translatable("complexityanalyzer.command.tree.name_label")).append(": ").withStyle(ChatFormatting.GRAY))
                .append(fluid.getFluidName().copy().withStyle(ChatFormatting.WHITE));

        hover.append(Component.literal("\n").append(Component.translatable("complexityanalyzer.command.tree.amount_label")).append(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format("%.2f %s", fluid.getAmount(), Component.translatable("complexityanalyzer.unit.fluid.millibuckets").getString())).withStyle(ChatFormatting.YELLOW));

        if (parentNode.getMachineType() != null && !parentNode.getMachineType().getString().isEmpty()) hover
                .append(Component.literal("\n🏭 ").append(Component.translatable("complexityanalyzer.command.tree.produced_in")).append(": ").withStyle(ChatFormatting.GRAY))
                .append(parentNode.getMachineType().copy().withStyle(ChatFormatting.GREEN));

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

        var complexityColor = getComplexityColor(node.getComplexity());
        var component = Component.empty();

        switch (node.getType()) {
            case NO_DATA:
                component.append(Component.literal("❌ ").withStyle(ChatFormatting.RED))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.GRAY))
                        .append(node.getItemName().copy().withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
                break;

            case MAX_DEPTH_REACHED:
                component.append(Component.literal("... ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))
                        .append(Component.translatable("complexityanalyzer.command.tree.depth_limit_tag").withStyle(ChatFormatting.GRAY));
                break;

            case CYCLE:
                component.append(Component.literal("🔁 ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(node.getItemName().copy().withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
                break;

            case BASE_RESOURCE:
                component.append(Component.literal("⛏ ").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(node.getItemName().copy().withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
                break;

            case CRAFTING:
                component.append(Component.literal("🔨 ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(node.getItemName().copy().withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
                break;
        }

        component.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, createDetailedHoverText(node, engine))));
        return component;
    }

    private static Component toComponent(Object obj) {
        if (obj instanceof Component c) return c;
        if (obj == null) return Component.empty();
        String str = obj.toString();
        if (str.startsWith("complexityanalyzer.") || str.startsWith("item.") || str.startsWith("block.") || str.startsWith("entity.")) {
            return Component.translatable(str);
        }
        return Component.literal(str);
    }

    private static Component createDetailedHoverText(TreeNode node, AnalysisEngine engine) {
        var hover = Component.empty();

        switch (node.getType()) {
            case NO_DATA:
                hover.append(Component.translatable("complexityanalyzer.command.tree.no_recipe_data").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                hover.append(Component.literal("\n").append(Component.translatable("complexityanalyzer.command.tree.no_recipe_desc")).withStyle(ChatFormatting.GRAY));
                break;

            case MAX_DEPTH_REACHED:
                hover.append(Component.translatable("complexityanalyzer.command.tree.depth_limit_reached").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
                hover.append(Component.literal("\n").append(Component.translatable("complexityanalyzer.command.tree.depth_limit_desc")).withStyle(ChatFormatting.GRAY));
                break;

            case CYCLE:
                hover.append(Component.translatable("complexityanalyzer.command.tree.recursive_recipe").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                hover.append(Component.literal("\n").append(Component.translatable("complexityanalyzer.command.tree.recursive_desc")).withStyle(ChatFormatting.GRAY));
                break;

            case BASE_RESOURCE:
                hover.append(Component.translatable("complexityanalyzer.command.tree.base_resource_label").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

                boolean wouldCreateCycle = (Boolean) node.getMetadata().getOrDefault("wouldCreateCycle", false);
                if (!wouldCreateCycle) {
                    String sourceTypeName = (String) node.getMetadata().get("sourceTypeName");
                    String specifier = (String) node.getMetadata().get("sourceSpecifier");

                    if (sourceTypeName != null) {
                        hover.append(Component.literal("\n📍 ").append(Component.translatable("complexityanalyzer.command.tree.source_label")).append(": ").withStyle(ChatFormatting.GRAY))
                                .append(toComponent(sourceTypeName).copy().withStyle(ChatFormatting.AQUA));

                        if (specifier != null && !specifier.isBlank()) hover
                                .append(Component.literal("\n   ").withStyle(ChatFormatting.DARK_GRAY))
                                .append(toComponent(specifier).copy().withStyle(ChatFormatting.YELLOW));
                    }
                } else {
                    hover.append(Component.literal("\n⚠ ").append(Component.translatable("complexityanalyzer.command.tree.avoid_cycle")).withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
                }
                break;

            case CRAFTING:
                hover.append(Component.translatable("complexityanalyzer.command.tree.crafting_recipe").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

                var machineType = node.getMachineType();
                if (machineType != null && !machineType.getString().isEmpty()) hover
                        .append(Component.literal("\n🏭 ").append(Component.translatable("complexityanalyzer.command.tree.machine_label")).append(": ").withStyle(ChatFormatting.GRAY))
                        .append(machineType.copy().withStyle(ChatFormatting.AQUA));
                break;
        }

        if (node.getItem() != null) {
            appendStackDetails(hover, node, engine);

            var complexity = engine.getComplexityResult(node.getItem());
            if (complexity != null) {
                hover.append(Component.literal("\n\n📊 ").append(Component.translatable("complexityanalyzer.command.tree.complexity_label")).append(": ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format("%.2f", complexity.getComplexity())).withStyle(getComplexityColor(complexity.getComplexity()), ChatFormatting.BOLD));

                if (complexity.getOptimalRecipe() != null) hover
                        .append(Component.literal("\n✅ ").append(Component.translatable("complexityanalyzer.command.tree.has_optimal")).withStyle(ChatFormatting.DARK_GREEN));
            }
        }

        return hover;
    }

    private static void appendStackDetails(MutableComponent hover, TreeNode node, AnalysisEngine engine) {
        var stack = node.getItemStack();
        if (stack.isEmpty() || !ItemStackIdentity.hasStackData(stack, engine.getRegistryAccess())) return;

        hover.append(Component.literal("\n\nItem(need translate): ").withStyle(ChatFormatting.GRAY))
                .append(stack.getHoverName().copy().withStyle(ChatFormatting.WHITE));

        try {
            var tooltipContext = Item.TooltipContext.of(engine.getRegistryAccess());
            var lines = stack.getTooltipLines(tooltipContext, null, TooltipFlag.NORMAL);
            int shown = 0;
            for (var line : lines) {
                if (shown == 0) {
                    shown++;
                    continue;
                }
                var text = line.getString();
                if (text.isBlank()) continue;
                hover.append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(line.copy().withStyle(ChatFormatting.GRAY));
                shown++;
                if (shown >= 6) break;
            }
        } catch (Throwable ignored) {
        }

        var dataKey = ItemStackIdentity.dataKey(stack, engine.getRegistryAccess());
        if (!dataKey.isBlank()) {
            hover.append(Component.literal("\nStack data(need translate): ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(truncate(dataKey)).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 180) return value;
        return value.substring(0, Math.max(0, 180 - 3)) + "...";
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
        output.sendStatusLine(source, "📊", "complexityanalyzer.command.tree.stats_section", ChatFormatting.AQUA);
        output.sendSubEntry(source, "complexityanalyzer.command.tree.total_nodes", String.valueOf(stats.getTotalNodes()), ChatFormatting.DARK_GRAY, ChatFormatting.WHITE);
        output.sendSubEntry(source, "complexityanalyzer.command.tree.unique_items", String.valueOf(stats.getUniqueItems()), ChatFormatting.DARK_GRAY, ChatFormatting.AQUA);
        output.sendSubEntry(source, "complexityanalyzer.command.tree.crafting_steps", String.valueOf(stats.getCraftingSteps()), ChatFormatting.DARK_GRAY, ChatFormatting.GOLD);
        output.sendSubEntry(source, "complexityanalyzer.command.tree.base_resources_count", String.valueOf(stats.getBaseResourcesCount()), ChatFormatting.DARK_GRAY, ChatFormatting.GREEN);

        if (stats.getCyclesDetected() > 0) output
                .sendSubEntry(source, Component.literal("⚠ ").append(Component.translatable("complexityanalyzer.command.tree.cycles_warning")), String.valueOf(stats.getCyclesDetected()), ChatFormatting.YELLOW, ChatFormatting.RED);

        output.sendEmptyLine(source);
    }

    private static void renderBaseResources(CommandSourceStack source, CraftingTreeData data, OutputManager output) {
        var baseResources = data.getBaseResources();

        if (baseResources.isEmpty()) {
            output.sendTip(source, "complexityanalyzer.command.tree.no_base_resources");
            return;
        }

        var mode = data.getDisplayMode();
        String titleKey = mode == DisplayMode.PLAYER_INSTRUCTION ? "complexityanalyzer.command.tree.shopping_list" : "complexityanalyzer.command.tree.precise_requirements";
        output.sendStatusLine(source, "🎒", titleKey, ChatFormatting.GREEN);

        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            output.sendTip(source, "complexityanalyzer.command.tree.rounded_up");
        } else {
            output.sendTip(source, "complexityanalyzer.command.tree.exact_fractional");
        }

        output.sendEmptyLine(source);

        for (var entry : Reference2DoubleMaps.fastIterable(baseResources)) {
            var item = entry.getKey();
            double amount = entry.getDoubleValue();
            var itemName = item.getDescription();

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
        output.sendStatusLine(source, "💡", "complexityanalyzer.command.tree.tips_section", ChatFormatting.YELLOW);

        var mode = data.getDisplayMode();
        var stats = data.getStatistics();

        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            String economicCommand = "/complexity tree " + itemId + " mode economic";
            output.sendClickableTip(source,
                    Component.translatable("complexityanalyzer.command.tree.try_mode_prefix"),
                    Component.translatable("complexityanalyzer.command.tree.try_mode_link", Component.translatable("complexityanalyzer.command.tree.mode.economic")),
                    Component.translatable("complexityanalyzer.command.tree.try_mode_suffix", Component.translatable("complexityanalyzer.command.tree.precise_calculations")),
                    economicCommand,
                    Component.translatable("complexityanalyzer.command.tree.try_mode_hover", Component.translatable("complexityanalyzer.command.tree.mode.economic")));
        } else {
            String playerCommand = "/complexity tree " + itemId + " mode player";
            output.sendClickableTip(source,
                    Component.translatable("complexityanalyzer.command.tree.try_mode_prefix"),
                    Component.translatable("complexityanalyzer.command.tree.try_mode_link", Component.translatable("complexityanalyzer.command.tree.mode.player")),
                    Component.translatable("complexityanalyzer.command.tree.try_mode_suffix", Component.translatable("complexityanalyzer.command.tree.gameplay_view")),
                    playerCommand,
                    Component.translatable("complexityanalyzer.command.tree.try_mode_hover", Component.translatable("complexityanalyzer.command.tree.mode.player")));
        }

        if (stats.getCyclesDetected() > 0) {
            output.sendStatusLine(source, "⚠", "complexityanalyzer.command.tree.cyclic_found", ChatFormatting.RED);
            output.sendTip(source, "complexityanalyzer.command.tree.mod_conflicts");
        }

        if (stats.getTotalNodes() > 50) {
            output.sendClickableTip(source,
                    Component.translatable("complexityanalyzer.command.tree.complex_tree_prefix"),
                    Component.translatable("complexityanalyzer.command.tree.complex_tree_link"),
                    Component.translatable("complexityanalyzer.command.tree.complex_tree_suffix"),
                    "/complexity tree " + itemId + " depth 5",
                    Component.translatable("complexityanalyzer.command.tree.complex_tree_hover"));
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
            return Component.translatable("complexityanalyzer.command.tree.percent_format", (int) ((amount / (double) maxStackSize) * 100)).getString();
        } else if (remainder == 0) {
            return Component.translatable("complexityanalyzer.command.tree.stacks_format", stacks).getString();
        } else {
            return Component.translatable("complexityanalyzer.command.tree.stacks_plus_format", stacks, remainder).getString();
        }
    }
}
