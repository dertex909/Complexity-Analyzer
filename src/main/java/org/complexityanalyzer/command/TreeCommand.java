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
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.tree.CraftingTreeBuilder;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.CraftingTreeData;
import org.complexityanalyzer.data.CraftingTreeData.*;
import org.complexityanalyzer.data.ItemComplexity;

import java.util.*;

/**
 * Команда для отображения дерева крафта в чате.
 * Использует CraftingTreeBuilder для построения и отображает результат пользователю.
 */
public class TreeCommand {

    public static final int DEFAULT_MAX_DEPTH = 100;

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId, String mode, int maxDepth) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        // Валидация
        if (!engine.isReady() || engine.getDepthAnalyzer().isEmpty()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready!")
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

        try {
            // Построение дерева
            CraftingTreeBuilder builder = new CraftingTreeBuilder(engine);
            CraftingTreeData treeData = builder.build(item, displayMode, maxDepth);

            // Отображение дерева
            renderTree(source, treeData, itemId, output, engine);

            return 1;
        } catch (Exception e) {
            output.sendFailure(source,
                    Component.literal("❌ Error building crafting tree: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error building tree for {}", itemId, e);
            return 0;
        }
    }

    //==================== РЕНДЕРИНГ ====================

    private static void renderTree(CommandSourceStack source, CraftingTreeData data,
                                   ResourceLocation itemId, OutputManager output, AnalysisEngine engine) {
        renderHeader(source, data, output, engine);
        renderTreeNode(source, data.getRoot(), "  ", true, data.getDisplayMode(), output, engine);
        renderFooter(source, data, itemId, output, engine);
    }

    private static void renderHeader(CommandSourceStack source, CraftingTreeData data,
                                     OutputManager output, AnalysisEngine engine) {
        String itemName = data.getRootItem().getDescription().getString();
        double complexity = engine.getComplexity(data.getRootItem());

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

        String modeIcon = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? "👤" : "💰";
        String modeName = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ? "Player View" : "Economic View";
        ChatFormatting modeColor = data.getDisplayMode() == DisplayMode.PLAYER_INSTRUCTION ?
                ChatFormatting.AQUA : ChatFormatting.GOLD;

        output.sendInfo(source,
                Component.literal("  " + modeIcon + " Mode: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(modeName)
                                .withStyle(modeColor)));

        output.sendInfo(source,
                Component.literal("  🔍 Max Depth: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(data.getMaxDepth()))
                                .withStyle(ChatFormatting.YELLOW)));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("  ─────────────────────────────")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));
    }

    private static void renderTreeNode(CommandSourceStack source, TreeNode node, String prefix,
                                       boolean isLast, DisplayMode mode, OutputManager output,
                                       AnalysisEngine engine) {
        String branchChar = isLast ? "└─ " : "├─ ";
        MutableComponent line = Component.literal(prefix)
                .append(Component.literal(branchChar).withStyle(ChatFormatting.DARK_GRAY));

        line.append(formatNode(node, mode, engine));

        output.sendInfo(source, line);

        // Рендер дочерних узлов
        if (node.getType() == NodeType.CRAFTING) {
            String childPrefix = prefix + (isLast ? "   " : "│  ");

            int totalChildren = node.getItemChildren().size() +
                    node.getFluidChildren().size() +
                    node.getChemicalChildren().size();
            int currentIndex = 0;

            // Item children
            for (TreeNode child : node.getItemChildren()) {
                currentIndex++;
                renderTreeNode(source, child, childPrefix, currentIndex == totalChildren,
                        mode, output, engine);
            }

            // Fluid children
            for (FluidNode fluid : node.getFluidChildren()) {
                currentIndex++;
                boolean isLastChild = (currentIndex == totalChildren);
                String fluidBranch = isLastChild ? "└─ " : "├─ ";

                String amount = String.format("%.2f", fluid.getAmount());
                MutableComponent fluidLine = Component.literal(childPrefix)
                        .append(Component.literal(fluidBranch).withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("💧 ").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(amount + "mB ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(fluid.getFluidName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" [FLUID]").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));

                output.sendInfo(source, fluidLine);
            }

            // Chemical children
            for (ChemicalNode chem : node.getChemicalChildren()) {
                currentIndex++;
                boolean isLastChild = (currentIndex == totalChildren);
                String chemBranch = isLastChild ? "└─ " : "├─ ";

                String amount = String.format("%.2f", chem.getAmount());
                MutableComponent chemLine = Component.literal(childPrefix)
                        .append(Component.literal(chemBranch).withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("⚗ ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(amount + " ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(chem.getChemicalName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" [CHEMICAL]").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));

                output.sendInfo(source, chemLine);

                if (chem.getSubTree() != null) {
                    String chemChildPrefix = childPrefix + (isLastChild ? "   " : "│  ");
                    renderTreeNode(source, chem.getSubTree(), chemChildPrefix, true, mode, output, engine);
                }
            }
        }
    }

    private static MutableComponent formatNode(TreeNode node, DisplayMode mode, AnalysisEngine engine) {
        String quantityString;
        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
            int displayAmount = (int) Math.ceil(node.getNeededAmount());
            quantityString = displayAmount >= 1 ? displayAmount + "x " : "";
        } else {
            quantityString = node.getNeededAmount() > 0.001 ?
                    String.format("%.2fx ", node.getNeededAmount()) : "";
        }

        ChatFormatting complexityColor = getComplexityColor(node.getComplexity());

        MutableComponent component = Component.empty();

        switch (node.getType()) {
            case NO_DATA:
                component.append(Component.literal(node.getItemName()).withStyle(ChatFormatting.RED))
                        .append(Component.literal(" [NO DATA]").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                break;

            case MAX_DEPTH_REACHED:
                component.append(Component.literal("... ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))
                        .append(Component.literal("[MAX DEPTH REACHED]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                break;

            case CYCLE:
                component.append(Component.literal("⛏ ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(node.getItemName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(") ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("[CYCLE]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                break;

            case BASE_RESOURCE:
                boolean wouldCreateCycle = (Boolean) node.getMetadata().getOrDefault("wouldCreateCycle", false);
                String sourceType = wouldCreateCycle ? "[BASE]" : "[SOURCE]";
                ChatFormatting sourceColor = wouldCreateCycle ? ChatFormatting.GREEN : ChatFormatting.AQUA;

                String sourceDetail = "";
                if (!wouldCreateCycle) {
                    String specifier = (String) node.getMetadata().get("sourceSpecifier");
                    if (specifier != null && !specifier.isBlank()) {
                        sourceDetail = " - " + specifier;
                    } else {
                        String sourceTypeName = (String) node.getMetadata().get("sourceTypeName");
                        if (sourceTypeName != null) {
                            sourceDetail = " - " + sourceTypeName;
                        }
                    }
                }

                component.append(Component.literal("⛏ ").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(node.getItemName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(") ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(sourceType).withStyle(sourceColor, ChatFormatting.BOLD))
                        .append(Component.literal(sourceDetail).withStyle(ChatFormatting.GRAY));
                break;

            case CRAFTING:
                component.append(Component.literal("🔨 ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(quantityString).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(node.getItemName()).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("%.2f", node.getComplexity())).withStyle(complexityColor))
                        .append(Component.literal(") - ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(node.getMachineType()).withStyle(ChatFormatting.AQUA));
                break;
        }

        // Add hover event
        if (node.getItem() != null) {
            Optional<ItemComplexity> complexityOpt = engine.getComplexityResult(node.getItem());
            complexityOpt.ifPresent(itemComplexity -> {
                HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        createHoverText(itemComplexity, engine));
                component.withStyle(style -> style.withHoverEvent(hoverEvent));
            });
        }

        return component;
    }

    private static void renderFooter(CommandSourceStack source, CraftingTreeData data,
                                     ResourceLocation itemId, OutputManager output, AnalysisEngine engine) {
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("  ─────────────────────────────")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        renderStatistics(source, data.getStatistics(), output);
        renderBaseResources(source, data, output);
        renderTips(source, data, itemId, output);

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void renderStatistics(CommandSourceStack source, TreeStatistics stats, OutputManager output) {
        output.sendInfo(source,
                Component.literal("  📊 ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Tree Statistics")
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("    Total Nodes: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(stats.getTotalNodes()))
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source,
                Component.literal("    Unique Items: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(stats.getUniqueItems()))
                                .withStyle(ChatFormatting.AQUA)));

        output.sendInfo(source,
                Component.literal("    Crafting Steps: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(stats.getCraftingSteps()))
                                .withStyle(ChatFormatting.GOLD)));

        output.sendInfo(source,
                Component.literal("    Base Resources: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.valueOf(stats.getBaseResourcesCount()))
                                .withStyle(ChatFormatting.GREEN)));

        if (stats.getCyclesDetected() > 0) {
            output.sendInfo(source,
                    Component.literal("    ⚠ Cycles Detected: ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(String.valueOf(stats.getCyclesDetected()))
                                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static void renderBaseResources(CommandSourceStack source, CraftingTreeData data, OutputManager output) {
        Map<Item, Double> baseResources = data.getBaseResources();

        if (baseResources.isEmpty()) {
            output.sendInfo(source,
                    Component.literal("  ⚠ No base resources needed (item might be unobtainable)")
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
            return;
        }

        DisplayMode mode = data.getDisplayMode();
        String title = mode == DisplayMode.PLAYER_INSTRUCTION ?
                "Shopping List (What to Gather)" : "Precise Resource Requirements";

        output.sendInfo(source,
                Component.literal("  🎒 ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(title)
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
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

                    if (mode == DisplayMode.PLAYER_INSTRUCTION) {
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

    private static void renderTips(CommandSourceStack source, CraftingTreeData data,
                                   ResourceLocation itemId, OutputManager output) {
        output.sendInfo(source,
                Component.literal("  💡 ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("Tips & Options")
                                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        DisplayMode mode = data.getDisplayMode();
        TreeStatistics stats = data.getStatistics();

        if (mode == DisplayMode.PLAYER_INSTRUCTION) {
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

        if (stats.getTotalNodes() > 50) {
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

        if (stats.getCyclesDetected() > 0) {
            output.sendInfo(source,
                    Component.literal("    ⚠ Cyclic dependencies detected!")
                            .withStyle(ChatFormatting.RED));
            output.sendInfo(source,
                    Component.literal("      This may indicate mod conflicts or loop recipes")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static Component createHoverText(ItemComplexity complexityData, AnalysisEngine engine) {
        if (complexityData.getOptimalRecipe().isPresent()) {
            var recipe = complexityData.getOptimalRecipe().get();
            String machineName = engine.getMachineRegistry()
                    .flatMap(registry -> registry.getMachineForRecipe(recipe.getRecipeType()))
                    .map(m -> m.getDescription().getString())
                    .orElse("Crafting Table");
            return Component.literal("Source: Crafting (" + machineName + ")");
        }

        if (complexityData.getBaseData().isPresent()) {
            BaseResourceData data = complexityData.getBaseData().get();
            String specifier = data.getSourceSpecifier();
            if (specifier == null || specifier.isBlank()) {
                specifier = "Details unavailable";
            }

            // Используем getDisplayName() напрямую
            String sourceName = data.getSourceType().getDisplayName();

            // Простая проверка по имени
            String sourcePrefix = "Source: ";
            if (sourceName.contains("Mob") || sourceName.contains("Drop")) {
                return Component.literal(sourcePrefix + "Mob Drop (" + specifier + ")");
            } else if (sourceName.contains("Mining") || sourceName.contains("Ore") || sourceName.contains("Block")) {
                return Component.literal(sourcePrefix + "Mining (" + specifier + ")");
            } else if (sourceName.contains("Villager") || sourceName.contains("Trade")) {
                return Component.literal(sourcePrefix + "Villager Trade (" + specifier + ")");
            } else if (sourceName.contains("Fishing")) {
                return Component.literal(sourcePrefix + "Fishing");
            } else if (sourceName.contains("Chest") || sourceName.contains("Loot")) {
                return Component.literal(sourcePrefix + "Chest Loot");
            } else {
                return Component.literal(sourcePrefix + sourceName);
            }
        }

        return Component.literal("Source: Unknown");
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