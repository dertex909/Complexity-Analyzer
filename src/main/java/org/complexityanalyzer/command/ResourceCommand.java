package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.event.DatapackSyncHandler;

import java.util.Optional;

public class ResourceCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation itemId) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = DatapackSyncHandler.getEngine();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet!"));
            return 0;
        }

        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cItem not found: " + itemId));
            return 0;
        }

        Item item = itemOpt.get();

        try {
            Optional<BaseResourceData> resourceDataOpt = engine.getBaseResourceData(item);

            if (resourceDataOpt.isEmpty()) {
                source.sendFailure(Component.literal("§cNo base resource data for: " + itemId));
                return 0;
            }

            BaseResourceData data = resourceDataOpt.get();
            displayResourceInfo(source, item, data, engine, itemId);

            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError analyzing resource: " + e.getMessage()));
            ComplexityAnalyzer.LOGGER.error("Error analyzing resource {}", itemId, e);
            return 0;
        }
    }

    private static void displayResourceInfo(
            CommandSourceStack source,
            Item item,
            BaseResourceData data,
            org.complexityanalyzer.core.AnalysisEngine engine,
            ResourceLocation itemId
    ) {
        String itemName = item.getDescription().getString();

        source.sendSuccess(() -> Component.literal("§6§l=== Base Resource Analysis ==="), false);
        source.sendSuccess(() -> Component.literal("§7Item: §f" + itemName), false);
        source.sendSuccess(() -> Component.literal(""), false);

        source.sendSuccess(() -> Component.literal("§e[Source]"), false);
        source.sendSuccess(() -> Component.literal("§7  Type: §f" + data.getSourceType().getDisplayName()), false);
        source.sendSuccess(() -> Component.literal("§7  Base Factor: §a" + String.format("%.2f", data.getBaseFactor())), false);

        if (!data.getDetails().isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7  Details: §f" + data.getDetails()), false);
        }

        source.sendSuccess(() -> Component.literal(""), false);

        boolean hasRecipe = engine.hasRecipe(item);

        final String itemIdString = itemId.toString();

        if (hasRecipe) {
            source.sendSuccess(() -> Component.literal("§7Note: §eThis item also has crafting recipes"), false);
            source.sendSuccess(() -> Component.literal("§7Use §f/complexity analyze item " + itemIdString + " §7for full analysis"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§7This is a §abase resource §7(no recipes)"), false);
        }
    }
}