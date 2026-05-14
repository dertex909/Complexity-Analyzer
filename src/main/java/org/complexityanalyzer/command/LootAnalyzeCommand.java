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

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;

public class LootAnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation lootTableId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready!"));
            return 0;
        }

        UniversalLootSource uls = engine.getSourceByType(UniversalLootSource.class);
        if (uls == null) {
            output.sendFailure(source, Component.literal("⚠ UniversalLootSource is not initialized!"));
            output.sendTip(source, "This feature may be disabled in the config.");
            return 0;
        }

        ObjectList<BaseResourceData> itemsFromTable = new ObjectArrayList<>();
        for (var typeEntry : Reference2ObjectMaps.fastIterable(uls.getAllLootData())) {
            for (BaseResourceData data : typeEntry.getValue().values()) {
                if (data.getDetails().contains(lootTableId.toString())) itemsFromTable.add(data);
            }
        }

        if (itemsFromTable.isEmpty()) {
            output.sendFailure(source, Component.literal("❌ No items found for loot table"));
            output.sendEntry(source, "📋", "Table", lootTableId.toString(), ChatFormatting.GRAY, ChatFormatting.YELLOW);
            output.sendEmptyLine(source);
            output.sendTip(source, "Possible reasons: table is empty, doesn't exist, or not cached yet");
            return 0;
        }

        itemsFromTable.sort((a, b) -> Double.compare(extractChance(b), extractChance(a)));

        displayLootAnalysis(source, lootTableId, itemsFromTable, output);
        return 1;
    }

    private static void displayLootAnalysis(CommandSourceStack source, ResourceLocation lootTableId,
                                            ObjectList<BaseResourceData> items, OutputManager output) {
        String tableIcon = getLootTableIcon(lootTableId);
        output.sendEmptyLine(source);
        output.sendHeader(source, tableIcon, "Loot Table Analysis", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        String tableType = getLootTableType(lootTableId.toString());

        output.sendEntry(source, "📋", "Table", lootTableId.getPath(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🏷", "Type", tableType, ChatFormatting.GRAY, getTableTypeColor(tableType));
        output.sendEntry(source, "📦", "Items Found", String.valueOf(items.size()), ChatFormatting.GRAY, ChatFormatting.AQUA);

        output.sendEmptyLine(source);
        displayStatistics(source, items, output);
        displayItemsByRarity(source, items, output);
        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displayStatistics(CommandSourceStack source, ObjectList<BaseResourceData> items,
                                          OutputManager output) {
        double totalChance = 0.0;
        double highestChance = 0.0;
        double lowestChance = 100.0;

        for (BaseResourceData data : items) {
            double chance = extractChance(data);
            totalChance += chance;
            highestChance = Math.max(highestChance, chance);
            lowestChance = Math.min(lowestChance, chance);
        }

        double avgChance = totalChance / items.size();

        output.sendStatusLine(source, "📊", "Statistics", ChatFormatting.YELLOW);
        output.sendSubEntry(source, "Average Chance", String.format("%.2f%%", avgChance), ChatFormatting.DARK_GRAY, ChatFormatting.AQUA);
        output.sendSubEntry(source, "Highest", String.format("%.2f%%", highestChance), ChatFormatting.DARK_GRAY, ChatFormatting.GREEN);
        output.sendSubEntry(source, "Lowest", String.format("%.2f%%", lowestChance), ChatFormatting.DARK_GRAY, ChatFormatting.RED);
        output.sendEmptyLine(source);
    }

    private static void displayItemsByRarity(CommandSourceStack source, ObjectList<BaseResourceData> items,
                                             OutputManager output) {
        ObjectList<BaseResourceData> common = new ObjectArrayList<>();
        ObjectList<BaseResourceData> uncommon = new ObjectArrayList<>();
        ObjectList<BaseResourceData> rare = new ObjectArrayList<>();
        ObjectList<BaseResourceData> veryRare = new ObjectArrayList<>();
        ObjectList<BaseResourceData> legendary = new ObjectArrayList<>();

        for (BaseResourceData data : items) {
            double chance = extractChance(data);
            if (chance > 20.0) {
                common.add(data);
            } else if (chance > 10.0) {
                uncommon.add(data);
            } else if (chance > 5.0) {
                rare.add(data);
            } else if (chance > 1.0) {
                veryRare.add(data);
            } else {
                legendary.add(data);
            }
        }

        output.sendStatusLine(source, "💎", "Drops by Rarity", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        if (!common.isEmpty()) {
            displayRarityCategory(source, "Common", "🟢", ChatFormatting.GREEN, common, output);
        }
        if (!uncommon.isEmpty()) {
            displayRarityCategory(source, "Uncommon", "🟡", ChatFormatting.YELLOW, uncommon, output);
        }
        if (!rare.isEmpty()) {
            displayRarityCategory(source, "Rare", "🟠", ChatFormatting.GOLD, rare, output);
        }
        if (!veryRare.isEmpty()) {
            displayRarityCategory(source, "Very Rare", "🔵", ChatFormatting.AQUA, veryRare, output);
        }
        if (!legendary.isEmpty()) {
            displayRarityCategory(source, "Legendary", "🟣", ChatFormatting.LIGHT_PURPLE, legendary, output);
        }
    }

    private static void displayRarityCategory(CommandSourceStack source, String categoryName, String icon,
                                              ChatFormatting color, ObjectList<BaseResourceData> items, OutputManager output) {
        output.sendStatusLine(source, icon, categoryName + " (" + items.size() + ")", color);
        for (BaseResourceData data : items) displayItem(source, data, output);
        output.sendEmptyLine(source);
    }

    private static void displayItem(CommandSourceStack source, BaseResourceData data, OutputManager output) {
        Component itemComponent = data.getItem().getDescription().copy();
        double chance = extractChance(data);
        output.sendSubEntry(source, itemComponent.getString(), String.format("%.2f%%", chance), ChatFormatting.WHITE, getChanceColor(chance));
        output.sendValueBar(source, (int) Math.min(100, chance), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
    }

    private static String getLootTableIcon(ResourceLocation lootTableId) {
        String path = lootTableId.getPath().toLowerCase();

        if (path.contains("chest")) return "📦";
        if (path.contains("entities") || path.contains("mobs")) return "⚔";
        if (path.contains("fishing")) return "🎣";
        if (path.contains("gameplay")) return "🎲";
        if (path.contains("blocks")) return "⛏";

        return "🎁";
    }

    private static String getLootTableType(String path) {
        path = path.toLowerCase();

        if (path.contains("chests/")) return "Chest Loot";
        if (path.contains("entities/")) return "Entity Drop";
        if (path.contains("gameplay/fishing")) return "Fishing Loot";
        if (path.contains("blocks/")) return "Block Drop";
        if (path.contains("archaeology/")) return "Archaeology";

        return "Generic Loot";
    }

    private static ChatFormatting getTableTypeColor(String type) {
        return switch (type) {
            case "Chest Loot" -> ChatFormatting.GOLD;
            case "Entity Drop" -> ChatFormatting.RED;
            case "Fishing Loot" -> ChatFormatting.AQUA;
            case "Block Drop" -> ChatFormatting.GRAY;
            case "Archaeology" -> ChatFormatting.YELLOW;
            default -> ChatFormatting.WHITE;
        };
    }

    private static ChatFormatting getChanceColor(double chance) {
        if (chance > 20.0) return ChatFormatting.GREEN;
        if (chance > 10.0) return ChatFormatting.YELLOW;
        if (chance > 5.0) return ChatFormatting.GOLD;
        if (chance > 1.0) return ChatFormatting.AQUA;
        return ChatFormatting.LIGHT_PURPLE;
    }

    private static double extractChance(BaseResourceData data) {
        try {
            String details = data.getDetails();
            String chancePart = details.substring(details.indexOf("Chance: ") + 8);
            return Double.parseDouble(chancePart.replace("%", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
}