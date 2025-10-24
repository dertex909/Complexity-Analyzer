package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;

import java.util.*;

public class LootAnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation lootTableId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        // Проверка готовности движка
        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready!")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Проверка UniversalLootSource
        Optional<UniversalLootSource> ulsOpt = engine.getSourceByType(UniversalLootSource.class);
        if (ulsOpt.isEmpty()) {
            output.sendFailure(source,
                    Component.literal("⚠ UniversalLootSource is not initialized!")
                            .withStyle(ChatFormatting.RED));
            output.sendInfo(source,
                    Component.literal("This feature may be disabled in the config.")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return 0;
        }
        UniversalLootSource uls = ulsOpt.get();

        // Поиск предметов из таблицы
        List<BaseResourceData> itemsFromTable = new ArrayList<>();
        for (Map.Entry<BaseResourceData.ResourceSourceType, Map<Item, BaseResourceData>> typeEntry : uls.getAllLootData().entrySet()) {
            for (BaseResourceData data : typeEntry.getValue().values()) {
                if (data.getDetails().contains(lootTableId.toString())) {
                    itemsFromTable.add(data);
                }
            }
        }

        // Проверка результатов
        if (itemsFromTable.isEmpty()) {
            output.sendFailure(source,
                    Component.literal("❌ No items found for loot table")
                            .withStyle(ChatFormatting.RED));

            output.sendInfo(source,
                    Component.literal("Table: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(lootTableId.toString())
                                    .withStyle(ChatFormatting.YELLOW)));

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("Possible reasons:")
                            .withStyle(ChatFormatting.GRAY));
            output.sendInfo(source,
                    Component.literal("  • Table is empty")
                            .withStyle(ChatFormatting.DARK_GRAY));
            output.sendInfo(source,
                    Component.literal("  • Table doesn't exist")
                            .withStyle(ChatFormatting.DARK_GRAY));
            output.sendInfo(source,
                    Component.literal("  • Not cached yet")
                            .withStyle(ChatFormatting.DARK_GRAY));

            return 0;
        }

        // Сортировка по шансу (от высокого к низкому)
        itemsFromTable.sort(Comparator.comparingDouble(LootAnalyzeCommand::extractChance).reversed());

        displayLootAnalysis(source, lootTableId, itemsFromTable, output);
        return 1;
    }

    private static void displayLootAnalysis(
            CommandSourceStack source,
            ResourceLocation lootTableId,
            List<BaseResourceData> items,
            OutputManager output
    ) {
        // Заголовок
        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        String tableIcon = getLootTableIcon(lootTableId);
        output.sendInfo(source,
                Component.literal(tableIcon + " ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Loot Table Analysis")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        // Информация о таблице
        String tablePath = lootTableId.toString();
        String tableType = getLootTableType(tablePath);
        ChatFormatting typeColor = getTableTypeColor(tableType);

        output.sendInfo(source,
                Component.literal("  📋 Table: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(lootTableId.getPath())
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source,
                Component.literal("  🏷 Type: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(tableType)
                                .withStyle(typeColor, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("  📦 Items Found: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(items.size()))
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal(""));

        // Статистика
        displayStatistics(source, items, output);

        // Разделение по редкости
        displayItemsByRarity(source, items, output);

        // Нижний разделитель
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void displayStatistics(
            CommandSourceStack source,
            List<BaseResourceData> items,
            OutputManager output
    ) {
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

        output.sendInfo(source,
                Component.literal("  📊 Statistics")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Average Chance: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.format("%.2f%%", avgChance))
                                .withStyle(ChatFormatting.AQUA)));

        output.sendInfo(source,
                Component.literal("    Highest: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.format("%.2f%%", highestChance))
                                .withStyle(ChatFormatting.GREEN)));

        output.sendInfo(source,
                Component.literal("    Lowest: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.format("%.2f%%", lowestChance))
                                .withStyle(ChatFormatting.RED)));

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayItemsByRarity(
            CommandSourceStack source,
            List<BaseResourceData> items,
            OutputManager output
    ) {
        // Разделяем предметы по категориям редкости
        List<BaseResourceData> common = new ArrayList<>();      // > 20%
        List<BaseResourceData> uncommon = new ArrayList<>();    // 10-20%
        List<BaseResourceData> rare = new ArrayList<>();        // 5-10%
        List<BaseResourceData> veryRare = new ArrayList<>();    // 1-5%
        List<BaseResourceData> legendary = new ArrayList<>();   // < 1%

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

        output.sendInfo(source,
                Component.literal("  💎 Drops by Rarity")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        output.sendInfo(source, Component.literal(""));

        // Показываем категории (только если есть предметы)
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

    private static void displayRarityCategory(
            CommandSourceStack source,
            String categoryName,
            String icon,
            ChatFormatting color,
            List<BaseResourceData> items,
            OutputManager output
    ) {
        output.sendInfo(source,
                Component.literal("  " + icon + " ")
                        .withStyle(color)
                        .append(Component.literal(categoryName + " (" + items.size() + ")")
                                .withStyle(color, ChatFormatting.BOLD)));

        for (BaseResourceData data : items) {
            displayItem(source, data, output);
        }

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayItem(
            CommandSourceStack source,
            BaseResourceData data,
            OutputManager output
    ) {
        Component itemComponent = data.getItem().getDescription().copy();
        double chance = extractChance(data);

        // Определяем цвет шанса
        ChatFormatting chanceColor = getChanceColor(chance);

        // Прогресс-бар для визуализации шанса
        String chanceBar = getChanceBar(chance);

        // Строка с предметом
        MutableComponent itemLine = Component.literal("    • ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(itemComponent.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(": ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.format("%.2f%%", chance))
                        .withStyle(chanceColor, ChatFormatting.BOLD));

        output.sendInfo(source, itemLine);

        // Визуальный бар под предметом
        output.sendInfo(source,
                Component.literal("      " + chanceBar)
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    // === Вспомогательные методы ===

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

    private static String getChanceBar(double chance) {
        // Масштабируем до 100% = 10 блоков
        int filled = (int) Math.min(10, Math.ceil(chance / 10.0));
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i < filled) {
                bar.append("█");
            } else {
                bar.append("░");
            }
        }
        bar.append("]");
        return bar.toString();
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