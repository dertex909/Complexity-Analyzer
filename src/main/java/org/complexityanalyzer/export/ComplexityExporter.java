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

package org.complexityanalyzer.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.export.ExportData.MobData;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.sources.HardcodedSource;
import org.complexityanalyzer.util.ModFileManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

import static java.util.Locale.ROOT;

public class ComplexityExporter {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(Double.class, new DoubleSerializer())
            .registerTypeAdapter(double.class, new DoubleSerializer())
            .create();

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final ResourceLocation AIR_LOCATION = ResourceLocation.parse("minecraft:air");

    private static final Comparator<ExportData.ItemData> ITEM_DATA_COMPARATOR =
            (a, b) -> Double.compare(b.complexity(), a.complexity());

    private static final Comparator<CsvRow> CSV_ROW_COMPARATOR =
            (a, b) -> Double.compare(b.complexity(), a.complexity());

    private static final Comparator<ExportData.SourceData> SOURCE_DATA_COMPARATOR =
            Comparator.comparingDouble(ExportData.SourceData::estimatedCost);

    private static final Comparator<MobData> MOB_DATA_COMPARATOR =
            (a, b) -> Double.compare(b.combatPower(), a.combatPower());

    public static Path exportAllItems(MinecraftServer server, AnalysisEngine engine) throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        var exportFile = ModFileManager.resolve(server, "export", "items_all_" + timestamp + ".json");
        var allItems = new ObjectArrayList<ExportData.ItemData>();
        for (var item : GameRegistryManager.getAllItems()) {
            var c = engine.getComplexityResult(item);
            if (c != null) allItems.add(buildItemData(item, GameRegistryManager.getItemId(item), c, engine));
        }
        allItems.sort(ITEM_DATA_COMPARATOR);
        String json = GSON.toJson(new ExportData(timestamp, allItems.size(), allItems));
        ModFileManager.writeStringAtomic(exportFile, json);
        return exportFile;
    }

    public static Path exportItemsByCategory(MinecraftServer server, AnalysisEngine engine, String categoryName)
            throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        var exportFile = ModFileManager.resolve(server, "export", "items_category_" + categoryName.toLowerCase(ROOT) + "_" + timestamp + ".json");
        var filteredItems = new ObjectArrayList<ExportData.ItemData>();
        for (var item : GameRegistryManager.getAllItems()) {
            var c = engine.getComplexityResult(item);
            if (c != null && c.getCategory().getDisplayName().equalsIgnoreCase(categoryName)) {
                filteredItems.add(buildItemData(item, GameRegistryManager.getItemId(item), c, engine));
            }
        }
        filteredItems.sort(ITEM_DATA_COMPARATOR);
        String json = GSON.toJson(new ExportData(timestamp, filteredItems.size(), filteredItems));
        ModFileManager.writeStringAtomic(exportFile, json);
        return exportFile;
    }

    public static Path exportTopItems(MinecraftServer server, AnalysisEngine engine, int count) throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        var exportFile = ModFileManager.resolve(server, "export", "items_top" + count + "_" + timestamp + ".json");
        var allItems = new ObjectArrayList<ExportData.ItemData>();
        for (var item : GameRegistryManager.getAllItems()) {
            var c = engine.getComplexityResult(item);
            if (c != null && !Double.isInfinite(c.getComplexity())) {
                allItems.add(buildItemData(item, GameRegistryManager.getItemId(item), c, engine));
            }
        }
        allItems.sort(ITEM_DATA_COMPARATOR);

        int topCount = Math.min(count, allItems.size());
        var topItems = new ObjectArrayList<ExportData.ItemData>(topCount);
        for (int i = 0; i < topCount; i++) topItems.add(allItems.get(i));

        String json = GSON.toJson(new ExportData(timestamp, topItems.size(), topItems));
        ModFileManager.writeStringAtomic(exportFile, json);
        return exportFile;
    }

    public static Path exportItemsCSV(MinecraftServer server, AnalysisEngine engine) throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        var exportFile = ModFileManager.resolve(server, "export", "items_all_" + timestamp + ".csv");

        var rows = new ObjectArrayList<CsvRow>();
        for (var item : GameRegistryManager.getAllItems()) {
            var c = engine.getComplexityResult(item);
            if (c != null) rows.add(new CsvRow(
                    GameRegistryManager.getItemId(item).toString(),
                    item.getDescription().getString(),
                    c.getComplexity(),
                    c.getCategory().getDisplayName(),
                    c.hasRecipe(),
                    c.getDepth(),
                    engine.getUsageCount(item),
                    c.isValid(),
                    c.hasCycle(),
                    checkIfHardcoded(item)
            ));
        }

        rows.sort(CSV_ROW_COMPARATOR);
        ModFileManager.writeStringAtomic(exportFile, buildItemsCsv(rows));
        return exportFile;
    }

    private static String buildItemsCsv(ObjectArrayList<CsvRow> rows) {
        var sb = new StringBuilder(rows.size() * 128);
        sb.append("Item ID,Display Name,Complexity,Category,Has Recipe,Crafting Depth,Used In Recipes,Is Valid,Has Cycle,Is Hardcoded\n");
        for (var row : rows) {
            sb.append(row.itemId).append(",\"")
                    .append(row.displayName.replace("\"", "\"\"")).append("\",")
                    .append(String.format(ROOT, "%.2f", row.complexity)).append(",")
                    .append(row.category).append(",")
                    .append(row.hasRecipe).append(",")
                    .append(row.craftingDepth).append(",")
                    .append(row.usedInRecipes).append(",")
                    .append(row.isValid).append(",")
                    .append(row.hasCycle).append(",")
                    .append(row.isHardcoded).append('\n');
        }
        return sb.toString();
    }

    public static Path exportSingleItem(MinecraftServer server, AnalysisEngine engine, String itemIdString)
            throws IOException {
        var itemId = ResourceLocation.parse(itemIdString);
        var item = GameRegistryManager.getItem(itemId);
        if (item == Items.AIR && !itemId.equals(AIR_LOCATION)) {
            throw new IllegalArgumentException("Item not found: " + itemIdString);
        }
        var complexity = engine.getComplexityResult(item);
        if (complexity == null) throw new IllegalStateException("Failed to analyze item: " + itemIdString);
        var itemData = buildItemData(item, itemId, complexity, engine);
        var exportFile = ModFileManager.resolve(server, "export", "items", itemId.getNamespace() + "_" + itemId.getPath() + ".json");
        ModFileManager.writeStringAtomic(exportFile, GSON.toJson(itemData));
        return exportFile;
    }

    public static Path exportAllMobs(MinecraftServer server, AnalysisEngine engine, String format) throws IOException {
        return exportMobsAs(server, engine, format, null, -1, "all");
    }

    public static Path exportMobsByCategory(MinecraftServer server, AnalysisEngine engine, String categoryName)
            throws IOException {
        return exportMobsAs(server, engine, "json", categoryName, -1, "category_" + categoryName);
    }

    public static Path exportTopMobs(MinecraftServer server, AnalysisEngine engine, int count) throws IOException {
        return exportMobsAs(server, engine, "json", null, count, "top" + count);
    }

    public static Path exportSingleMob(MinecraftServer server, AnalysisEngine engine, String mobIdString)
            throws IOException {
        var mobId = ResourceLocation.parse(mobIdString);
        var mobType = GameRegistryManager.getEntityType(mobId);
        var mobData = buildMobData(mobType, engine);
        if (mobData == null) throw new IllegalStateException("Failed to analyze mob: " + mobIdString);
        var exportFile = ModFileManager.resolve(server, "export", "mobs", mobId.getNamespace() + "_" + mobId.getPath() + ".json");
        ModFileManager.writeStringAtomic(exportFile, GSON.toJson(mobData));
        return exportFile;
    }

    private static boolean checkIfHardcoded(Item item) {
        try {
            var registry = HardcodedSource.getRegistry();
            return registry.isRegistered(item);
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static ExportData.ItemData buildItemData(Item item, ResourceLocation itemId, ItemComplexity complexity,
                                                     AnalysisEngine engine) {
        var sourcesRaw = engine.findAllSourcesForItem(item);
        var sources = new ObjectArrayList<ExportData.SourceData>();
        for (var data : sourcesRaw) sources.add(buildSourceData(data, engine));
        sources.sort(SOURCE_DATA_COMPARATOR);

        boolean isHardcoded = checkIfHardcoded(item);

        return new ExportData.ItemData(
                itemId.toString(),
                item.getDescription().getString(),
                complexity.getComplexity(),
                complexity.getCategory().getDisplayName(),
                complexity.hasRecipe(),
                complexity.getDepth(),
                engine.getUsageCount(item),
                complexity.isValid(),
                complexity.hasCycle(),
                isHardcoded,
                sources
        );
    }

    private static ExportData.SourceData buildSourceData(BaseResourceData data, AnalysisEngine engine) {
        double fullCost = data.getBaseFactor();
        var ingredients = new Object2DoubleOpenHashMap<String>();
        if (!data.getSourceItems().isEmpty()) {
            for (var entry : Reference2DoubleMaps.fastIterable(data.getSourceItems())) {
                var sourceItem = entry.getKey();
                double amount = entry.getDoubleValue();
                ingredients.put(GameRegistryManager.getItemId(sourceItem).toString(), amount);
                var sourceComplexity = engine.getComplexityResult(sourceItem);
                if (sourceComplexity != null && sourceComplexity.isValid()) {
                    fullCost += sourceComplexity.getComplexity() * amount;
                } else {
                    fullCost = Double.POSITIVE_INFINITY;
                    break;
                }
            }
        }
        return new ExportData.SourceData(data.getSourceType().getDisplayName(), data.getBaseFactor(), fullCost,
                data.getDetails(), ingredients);
    }

    private static Path exportMobsAs(MinecraftServer server, AnalysisEngine engine, String format,
                                     String categoryFilter, int topN, String fileSuffix) throws IOException {
        var mobDataList = new ObjectArrayList<MobData>();
        for (var type : GameRegistryManager.getAllEntityTypes()) {
            if (type.getCategory() == MobCategory.MISC) continue;
            if (categoryFilter != null && !type.getCategory().getName().equalsIgnoreCase(categoryFilter)) continue;
            var data = buildMobData(type, engine);
            if (data != null && !Double.isInfinite(data.combatPower())) mobDataList.add(data);
        }
        mobDataList.sort(MOB_DATA_COMPARATOR);

        if (topN > 0 && mobDataList.size() > topN) {
            var trimmed = new ObjectArrayList<MobData>(topN);
            for (int i = 0; i < topN; i++) trimmed.add(mobDataList.get(i));
            mobDataList = trimmed;
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        var exportPath = ModFileManager.resolve(server, "export", "mobs_" + fileSuffix + "_" + timestamp + "." + format);

        if ("csv".equalsIgnoreCase(format)) {
            ModFileManager.writeStringAtomic(exportPath, buildMobsCsv(mobDataList));
        } else {
            ModFileManager.writeStringAtomic(exportPath, GSON.toJson(mobDataList));
        }
        return exportPath;
    }

    private static String buildMobsCsv(ObjectArrayList<MobData> mobDataList) {
        var sb = new StringBuilder(mobDataList.size() * 256);
        sb.append("Name,ID,Category,Health,Damage,Armor,Survivability,Threat,Combat Power,Rarity,Is Boss,Is MiniBoss,Notable Drops\n");
        for (var data : mobDataList) {
            var drops = data.drops();

            sb.append('"').append(data.name()).append("\",")
                    .append('"').append(data.id()).append("\",")
                    .append('"').append(data.category()).append("\",")
                    .append(String.format(ROOT, "%.2f", data.health())).append(",")
                    .append(String.format(ROOT, "%.2f", data.damage())).append(",")
                    .append(String.format(ROOT, "%.2f", data.armor())).append(",")
                    .append(String.format(ROOT, "%.2f", data.survivability())).append(",")
                    .append(String.format(ROOT, "%.2f", data.threat())).append(",")
                    .append(String.format(ROOT, "%.2f", data.combatPower())).append(",")
                    .append(String.format(ROOT, "%.2f", data.rarity())).append(",")
                    .append(data.isBoss()).append(",")
                    .append(data.isMiniBoss()).append(",")
                    .append('"');

            if (drops.isEmpty()) {
                sb.append("None");
            } else {
                for (int j = 0; j < drops.size(); j++) {
                    var d = drops.get(j);
                    if (j > 0) sb.append("; ");
                    sb.append(d.itemName()).append(" (").append(String.format(ROOT, "%.2f", d.yieldPerKill())).append(')');
                }
            }
            sb.append("\"\n");
        }
        return sb.toString();
    }

    private static MobData buildMobData(EntityType<?> type, AnalysisEngine engine) {
        var mobProvider = engine.getMobPropertyProvider();
        if (mobProvider == null) return null;
        var props = mobProvider.getProperties(type);
        if (props == null) return null;

        var drops = new ObjectArrayList<ExportData.MobDropData>();
        var source = engine.getMobDropSource();
        if (source != null) for (var d : source.getDropsForEntity(type)) {
            drops.add(new ExportData.MobDropData(
                    GameRegistryManager.getItemId(d.item()).toString(),
                    d.item().getDescription().getString(),
                    d.averageYield()
            ));
        }

        return new MobData(type.getDescription().getString(), GameRegistryManager.getEntityTypeId(type).toString(),
                type.getCategory().getName(), props.maxHealth(), props.attackDamage(), props.armor(),
                props.calculateSurvivability(), props.calculateThreat(), props.calculateCombatPower(),
                mobProvider.getRarity(type), mobProvider.isBoss(type), mobProvider.isMiniBoss(type), drops);
    }

    private static class DoubleSerializer extends TypeAdapter<Double> {
        @Override
        public void write(JsonWriter out, Double value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else if (Double.isInfinite(value)) {
                out.value(value > 0 ? "Infinity" : "-Infinity");
            } else if (Double.isNaN(value)) {
                out.value("NaN");
            } else {
                out.value(value);
            }
        }

        @Override
        public Double read(JsonReader in) throws IOException {
            return switch (in.peek()) {
                case STRING -> {
                    String str = in.nextString();
                    yield switch (str) {
                        case "Infinity" -> Double.POSITIVE_INFINITY;
                        case "-Infinity" -> Double.NEGATIVE_INFINITY;
                        case "NaN" -> Double.NaN;
                        default -> Double.parseDouble(str);
                    };
                }
                case NUMBER -> in.nextDouble();
                case NULL -> {
                    in.nextNull();
                    yield null;
                }
                default -> throw new JsonSyntaxException("Expected number or string");
            };
        }
    }

    private record CsvRow(
            String itemId,
            String displayName,
            double complexity,
            String category,
            boolean hasRecipe,
            int craftingDepth,
            int usedInRecipes,
            boolean isValid,
            boolean hasCycle,
            boolean isHardcoded
    ) {
    }
}