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

import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.sources.UniversalLootSource;

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

        String tableIdStr = lootTableId.toString();
        var itemsFromTable = new ObjectArrayList<BaseResourceData>();
        for (var typeEntry : Reference2ObjectMaps.fastIterable(uls.getAllLootData())) {
            for (var data : typeEntry.getValue().values()) {
                if (tableIdStr.equals(data.getSourceSpecifier())) itemsFromTable.add(data);
            }
        }

        if (itemsFromTable.isEmpty()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.loot.no_items"));
            output.sendEntry(source, "📋", "complexityanalyzer.command.loot.table_label", tableIdStr, ChatFormatting.GRAY, ChatFormatting.YELLOW);
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
        var sourceType = items.getFirst().getSourceType();

        output.sendEmptyLine(source);
        output.sendHeader(source, sourceType.getIcon(), "complexityanalyzer.command.loot.header", ChatFormatting.GOLD);
        output.sendEmptyLine(source);

        output.sendEntry(source, "📋", "complexityanalyzer.command.loot.table_label", lootTableId.getPath(), ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🏷", "complexityanalyzer.command.loot.type_label", sourceType.getDisplayName(), ChatFormatting.GRAY, sourceType.getColor());
        output.sendEntry(source, "📦", "complexityanalyzer.command.loot.items_found", String.valueOf(items.size()), ChatFormatting.GRAY, ChatFormatting.AQUA);

        output.sendEmptyLine(source);
        displayStatistics(source, items, output);
        displayItemsByRarity(source, items, output);
        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displayStatistics(CommandSourceStack source, ObjectList<BaseResourceData> items, OutputManager output) {
        double totalChance = 0.0;
        double highestChance = 0.0;
        double lowestChance = 100.0;

        for (var data : items) {
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

    private static void displayItemsByRarity(CommandSourceStack source, ObjectList<BaseResourceData> items, OutputManager output) {
        @SuppressWarnings("unchecked")
        ObjectArrayList<BaseResourceData>[] groups = new ObjectArrayList[Rarity.VALUES.length];
        for (int i = 0; i < groups.length; i++) groups[i] = new ObjectArrayList<>();
        for (var data : items) groups[Rarity.fromChance(extractChance(data)).ordinal()].add(data);

        output.sendStatusLine(source, "💎", "complexityanalyzer.command.loot.rarity_section", ChatFormatting.YELLOW);
        output.sendEmptyLine(source);

        for (var rarity : Rarity.VALUES) {
            var groupItems = groups[rarity.ordinal()];
            if (!groupItems.isEmpty()) displayRarityCategory(source, rarity, groupItems, output);
        }
    }

    private static void displayRarityCategory(CommandSourceStack source, Rarity rarity, ObjectList<BaseResourceData> items, OutputManager output) {
        output.sendStatusLine(source, rarity.icon, Component.translatable("complexityanalyzer.command.loot.rarity." + rarity.key).append(" (" + items.size() + ")"), rarity.color);
        for (var data : items) displayItem(source, data, output);
        output.sendEmptyLine(source);
    }

    private static void displayItem(CommandSourceStack source, BaseResourceData data, OutputManager output) {
        var itemComponent = data.getItem().getDescription();
        double chance = extractChance(data);
        output.sendSubEntry(source, itemComponent, String.format("%.2f%%", chance), ChatFormatting.WHITE, Rarity.fromChance(chance).color);
        output.sendValueBar(source, (int) Math.min(100, chance), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
    }

    private static double extractChance(BaseResourceData data) {
        String chanceMeta = data.getMetadata().get("chance");
        if (chanceMeta == null) return 0.0;
        try {
            return Double.parseDouble(chanceMeta);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private enum Rarity {
        COMMON("common", "🟢", ChatFormatting.GREEN, 20.0),
        UNCOMMON("uncommon", "🟡", ChatFormatting.YELLOW, 10.0),
        RARE("rare", "🟠", ChatFormatting.GOLD, 5.0),
        VERY_RARE("very_rare", "🔵", ChatFormatting.AQUA, 1.0),
        LEGENDARY("legendary", "🟣", ChatFormatting.LIGHT_PURPLE, 0.0);

        private static final Rarity[] VALUES = values();

        final String key;
        final String icon;
        final ChatFormatting color;
        final double minChance;

        Rarity(String key, String icon, ChatFormatting color, double minChance) {
            this.key = key;
            this.icon = icon;
            this.color = color;
            this.minChance = minChance;
        }

        static Rarity fromChance(double chance) {
            for (var rarity : VALUES) if (chance > rarity.minChance) return rarity;
            return LEGENDARY;
        }
    }
}