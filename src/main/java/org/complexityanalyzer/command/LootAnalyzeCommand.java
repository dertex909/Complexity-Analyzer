package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.core.AnalysisEngine;

import java.util.*;

public class LootAnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation lootTableId) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cEngine is not ready."));
            return 0;
        }

        Optional<UniversalLootSource> ulsOpt = engine.getSourceByType(UniversalLootSource.class);
        if (ulsOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cUniversalLootSource is not initialized."));
            return 0;
        }
        UniversalLootSource uls = ulsOpt.get();

        List<BaseResourceData> itemsFromTable = new ArrayList<>();
        for (Map.Entry<BaseResourceData.ResourceSourceType, Map<Item, BaseResourceData>> typeEntry : uls.getAllLootData().entrySet()) {
            for (BaseResourceData data : typeEntry.getValue().values()) {
                if (data.getDetails().contains(lootTableId.toString())) {
                    itemsFromTable.add(data);
                }
            }
        }

        if (itemsFromTable.isEmpty()) {
            source.sendFailure(Component.literal("§cNo items found for loot table: " + lootTableId + ". It might be empty, skipped, or not a relevant type."));
            return 0;
        }

        itemsFromTable.sort(Comparator.comparingDouble(LootAnalyzeCommand::extractChance).reversed());

        displayLootAnalysis(source, lootTableId, itemsFromTable);
        return 1;
    }

    private static void displayLootAnalysis(CommandSourceStack source, ResourceLocation lootTableId, List<BaseResourceData> items) {
        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("§6§l=== Cached Loot Table Analysis ==="), false);
        source.sendSuccess(() -> Component.literal("§7Table: §f" + lootTableId), false);
        source.sendSuccess(() -> Component.literal(String.format("§7(Displaying §f%d§7 cached items from this source)", items.size())), false);
        source.sendSuccess(() -> Component.literal(""), false);

        source.sendSuccess(() -> Component.literal("§a--- Cached Drops ---"), false);

        for (BaseResourceData data : items) {
            Component itemComponent = data.getItem().getDescription().copy().withStyle(ChatFormatting.WHITE);

            String chanceText;
            try {
                String details = data.getDetails();
                chanceText = details.substring(details.indexOf("Chance: "));
            } catch (Exception e) {
                chanceText = data.getDetails();
            }

            final String finalChanceText = chanceText;
            source.sendSuccess(() -> Component.literal("§f- ").append(itemComponent)
                    .append(Component.literal(": " + finalChanceText).withStyle(ChatFormatting.AQUA)), false);
        }
    }

    private static double extractChance(BaseResourceData data) {
        try {
            String details = data.getDetails();
            String chancePart = details.substring(details.indexOf("Chance: ") + 8);
            String numberPart = chancePart.replace("%", "").trim();
            return Double.parseDouble(numberPart);
        } catch (Exception e) {
            return 0.0;
        }
    }
}