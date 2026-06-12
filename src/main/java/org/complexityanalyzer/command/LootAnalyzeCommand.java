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
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.loot.not_ready"));
            return 0;
        }

        var uls = engine.getSourceByType(UniversalLootSource.class);
        if (uls == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.loot.not_initialized"));
            output.sendTip(source, "complexityanalyzer.command.loot.disabled_tip");
            return 0;
        }

        var itemsFromTable = new ObjectArrayList<BaseResourceData>();
        for (var typeEntry : Reference2ObjectMaps.fastIterable(uls.getAllLootData())) {
            for (var data : typeEntry.getValue().values()) {
                if (data.getDetails().contains(lootTableId.toString())) itemsFromTable.add(data);
            }
        }

        if (itemsFromTable.isEmpty()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.loot.no_items"));
            output.sendEntry(source, "📋", "complexityanalyzer.command.loot.table_label", lootTableId.toString(), ChatFormatting.GRAY, ChatFormatting.YELLOW);
            output.sendEmptyLine(source);
            output.sendTip(source, "complexityanalyzer.command.loot.no_items_tip");
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
        output.sendHeader(source, tableIcon, "complexityanalyzer.command.loot.header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        String tableType = getLootTableType(lootTableId.toString());

        output.sendEntry(source, "📋", "complexityanalyzer.command.loot.table_label", lootTableId.getPath(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🏷", "complexityanalyzer.command.loot.type_label", "complexityanalyzer.command.loot.type." + tableType, ChatFormatting.GRAY, getTableTypeColor(tableType));
        output.sendEntry(source, "📦", "complexityanalyzer.command.loot.items_found", String.valueOf(items.size()), ChatFormatting.GRAY, ChatFormatting.AQUA);

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

        output.sendStatusLine(source, "📊", "complexityanalyzer.command.loot.stats_section", ChatFormatting.YELLOW);
        output.sendSubEntry(source, "complexityanalyzer.command.loot.avg_chance", String.format("%.2f%%", avgChance), ChatFormatting.DARK_GRAY, ChatFormatting.AQUA);
        output.sendSubEntry(source, "complexityanalyzer.command.loot.highest", String.format("%.2f%%", highestChance), ChatFormatting.DARK_GRAY, ChatFormatting.GREEN);
        output.sendSubEntry(source, "complexityanalyzer.command.loot.lowest", String.format("%.2f%%", lowestChance), ChatFormatting.DARK_GRAY, ChatFormatting.RED);
        output.sendEmptyLine(source);
    }

    private static void displayItemsByRarity(CommandSourceStack source, ObjectList<BaseResourceData> items,
                                             OutputManager output) {
        ObjectList<BaseResourceData> common = new ObjectArrayList<>();
        ObjectList<BaseResourceData> uncommon = new ObjectArrayList<>();
        ObjectList<BaseResourceData> rare = new ObjectArrayList<>();
        ObjectList<BaseResourceData> veryRare = new ObjectArrayList<>();
        ObjectList<BaseResourceData> legendary = new ObjectArrayList<>();

        for (var data : items) {
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

        output.sendStatusLine(source, "💎", "complexityanalyzer.command.loot.rarity_section", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        if (!common.isEmpty()) {
            displayRarityCategory(source, "common", "🟢", ChatFormatting.GREEN, common, output);
        }
        if (!uncommon.isEmpty()) {
            displayRarityCategory(source, "uncommon", "🟡", ChatFormatting.YELLOW, uncommon, output);
        }
        if (!rare.isEmpty()) {
            displayRarityCategory(source, "rare", "🟠", ChatFormatting.GOLD, rare, output);
        }
        if (!veryRare.isEmpty()) {
            displayRarityCategory(source, "very_rare", "🔵", ChatFormatting.AQUA, veryRare, output);
        }
        if (!legendary.isEmpty()) {
            displayRarityCategory(source, "legendary", "🟣", ChatFormatting.LIGHT_PURPLE, legendary, output);
        }
    }

    private static void displayRarityCategory(CommandSourceStack source, String rarityKey, String icon,
                                              ChatFormatting color, ObjectList<BaseResourceData> items, OutputManager output) {
        output.sendStatusLine(source, icon, Component.translatable("complexityanalyzer.command.loot.rarity." + rarityKey).append(" (" + items.size() + ")"), color);
        for (var data : items) displayItem(source, data, output);
        output.sendEmptyLine(source);
    }

    private static void displayItem(CommandSourceStack source, BaseResourceData data, OutputManager output) {
        var itemComponent = data.getItem().getDescription();
        double chance = extractChance(data);
        output.sendSubEntry(source, itemComponent, String.format("%.2f%%", chance), ChatFormatting.WHITE, getChanceColor(chance));
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

        if (path.contains("chests/")) return "chest";
        if (path.contains("entities/")) return "entity";
        if (path.contains("gameplay/fishing")) return "fishing";
        if (path.contains("blocks/")) return "block";
        if (path.contains("archaeology/")) return "archaeology";

        return "generic";
    }

    private static ChatFormatting getTableTypeColor(String type) {
        return switch (type) {
            case "chest" -> ChatFormatting.GOLD;
            case "entity" -> ChatFormatting.RED;
            case "fishing" -> ChatFormatting.AQUA;
            case "block" -> ChatFormatting.GRAY;
            case "archaeology" -> ChatFormatting.YELLOW;
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
        String chanceMeta = data.getMetadata().get("chance");
        if (chanceMeta != null) try {
            return Double.parseDouble(chanceMeta);
        } catch (NumberFormatException ignored) {
        }

        try {
            String details = data.getDetails();
            int index = details.indexOf("Chance: ");
            if (index == -1) return 0.0;
            String chancePart = details.substring(index + 8);
            return Double.parseDouble(chancePart.replace("%", "").replace(",", ".").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
}