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

package org.complexityanalyzer.export.format;

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
import org.complexityanalyzer.export.format.ExportData.ItemData;
import org.complexityanalyzer.export.format.ExportData.MobData;
import org.complexityanalyzer.export.format.ExportData.SourceData;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.sources.HardcodedSource;
import org.complexityanalyzer.util.ModFileManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;

import static java.util.Locale.ROOT;

public class ComplexityExporter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final ResourceLocation AIR_LOCATION = ResourceLocation.parse("minecraft:air");
    private static final Comparator<ItemData> ITEM_DATA_COMPARATOR = (a, b) -> Double.compare(b.complexity(), a.complexity());
    private static final Comparator<SourceData> SOURCE_DATA_COMPARATOR = Comparator.comparingDouble(SourceData::estimatedCost);
    private static final Comparator<MobData> MOB_DATA_COMPARATOR = (a, b) -> Double.compare(b.combatPower(), a.combatPower());

    public static Path exportAllItems(MinecraftServer server, AnalysisEngine engine, IExportFormat format) throws IOException {
        return exportItemsAs(server, engine, format, null, -1, "all");
    }

    public static Path exportItemsByCategory(MinecraftServer server, AnalysisEngine engine, IExportFormat format, String categoryName) throws IOException {
        return exportItemsAs(server, engine, format, categoryName, -1, "category_" + categoryName.toLowerCase(ROOT));
    }

    public static Path exportTopItems(MinecraftServer server, AnalysisEngine engine, IExportFormat format, int count) throws IOException {
        return exportItemsAs(server, engine, format, null, count, "top" + count);
    }

    public static Path exportSingleItem(MinecraftServer server, AnalysisEngine engine, IExportFormat format, String itemIdString) throws IOException {
        var itemId = ResourceLocation.parse(itemIdString);
        var item = GameRegistryManager.getItem(itemId);
        if (item == Items.AIR && !itemId.equals(AIR_LOCATION)) {
            throw new IllegalArgumentException("Item not found: " + itemIdString);
        }
        var complexity = Objects.requireNonNull(engine.getComplexityResult(item), () -> "Failed to analyze item: " + itemIdString);
        var itemData = buildItemData(item, itemId, complexity, engine);
        var exportFile = ModFileManager.resolve(server, "export", "items", itemId.getNamespace() + "_" + itemId.getPath() + "." + format.getFileExtension());
        ModFileManager.writeStringAtomic(exportFile, format.formatSingleItem(itemData));
        return exportFile;
    }

    public static Path exportAllMobs(MinecraftServer server, AnalysisEngine engine, IExportFormat format) throws IOException {
        return exportMobsAs(server, engine, format, null, -1, "all");
    }

    public static Path exportMobsByCategory(MinecraftServer server, AnalysisEngine engine, IExportFormat format, String categoryName) throws IOException {
        return exportMobsAs(server, engine, format, categoryName, -1, "category_" + categoryName.toLowerCase(ROOT));
    }

    public static Path exportTopMobs(MinecraftServer server, AnalysisEngine engine, IExportFormat format, int count) throws IOException {
        return exportMobsAs(server, engine, format, null, count, "top" + count);
    }

    public static Path exportSingleMob(MinecraftServer server, AnalysisEngine engine, IExportFormat format, String mobIdString) throws IOException {
        var mobId = ResourceLocation.parse(mobIdString);
        var mobType = GameRegistryManager.getEntityType(mobId);
        var mobData = Objects.requireNonNull(buildMobData(mobType, engine), () -> "Failed to analyze mob: " + mobIdString);
        var exportFile = ModFileManager.resolve(server, "export", "mobs", mobId.getNamespace() + "_" + mobId.getPath() + "." + format.getFileExtension());
        ModFileManager.writeStringAtomic(exportFile, format.formatSingleMob(mobData));
        return exportFile;
    }

    private static Path exportItemsAs(MinecraftServer server, AnalysisEngine engine, IExportFormat format,
                                      String categoryFilter, int topN, String fileSuffix) throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        var items = new ObjectArrayList<ItemData>();
        for (var item : GameRegistryManager.getAllItems()) {
            var c = engine.getComplexityResult(item);
            if (c == null) continue;
            if (topN > 0 && Double.isInfinite(c.getComplexity())) continue;
            if (categoryFilter != null && !c.getCategory().getDisplayName().equalsIgnoreCase(categoryFilter)) continue;
            items.add(buildItemData(item, GameRegistryManager.getItemId(item), c, engine));
        }
        items.sort(ITEM_DATA_COMPARATOR);

        if (topN > 0 && items.size() > topN) {
            var trimmed = new ObjectArrayList<ItemData>(topN);
            for (int i = 0; i < topN; i++) trimmed.add(items.get(i));
            items = trimmed;
        }

        var exportFile = ModFileManager.resolve(server, "export", "items_" + fileSuffix + "_" + timestamp + "." + format.getFileExtension());
        String content = format.formatItems(new ExportData(timestamp, items.size(), items));
        ModFileManager.writeStringAtomic(exportFile, content);
        return exportFile;
    }

    private static Path exportMobsAs(MinecraftServer server, AnalysisEngine engine, IExportFormat format,
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
        var exportPath = ModFileManager.resolve(server, "export", "mobs_" + fileSuffix + "_" + timestamp + "." + format.getFileExtension());
        ModFileManager.writeStringAtomic(exportPath, format.formatMobs(mobDataList));
        return exportPath;
    }

    private static boolean checkIfHardcoded(Item item) {
        return HardcodedSource.isInitialized() && HardcodedSource.getRegistry().isRegistered(item);
    }

    private static ItemData buildItemData(Item item, ResourceLocation itemId, ItemComplexity complexity,
                                          AnalysisEngine engine) {
        var sourcesRaw = engine.findAllSourcesForItem(item);
        var sources = new ObjectArrayList<SourceData>();
        for (var data : sourcesRaw) sources.add(buildSourceData(data, engine));
        sources.sort(SOURCE_DATA_COMPARATOR);

        boolean isHardcoded = checkIfHardcoded(item);

        return new ItemData(
                itemId.toString(),
                item.getDescription().getString(),
                complexity.getComplexity(),
                complexity.getCategory().getDisplayName(),
                complexity.hasRecipe(),
                complexity.getDepth(),
                engine.getUsageCount(item),
                complexity.isValid(),
                isHardcoded,
                sources
        );
    }

    private static SourceData buildSourceData(BaseResourceData data, AnalysisEngine engine) {
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
        return new SourceData(data.getSourceType().getDisplayName(), data.getBaseFactor(), fullCost,
                data.getDetails(), ingredients);
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
}