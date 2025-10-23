package org.complexityanalyzer.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class ComplexityExporter {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(Double.class, new DoubleSerializer())
            .registerTypeAdapter(double.class, new DoubleSerializer())
            .create();

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static class DoubleSerializer extends TypeAdapter<Double> {
        @Override
        public void write(JsonWriter out, Double value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else if (Double.isInfinite(value)) {
                if (value > 0) {
                    out.value("Infinity");
                } else {
                    out.value("-Infinity");
                }
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

    public static Path exportAll(MinecraftServer server, AnalysisEngine engine) throws IOException {
        Path exportDir = getExportDirectory(server);
        Files.createDirectories(exportDir);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("complexity_export_" + timestamp + ".json");

        ComplexityAnalyzer.LOGGER.info("Exporting all items to: {}", exportFile);

        List<ExportData.ItemData> allItems = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);

            try {
                Optional<ItemComplexity> complexityOpt = engine.getComplexityResult(item);
                if (complexityOpt.isEmpty()) {
                    continue;
                }

                ExportData.ItemData itemData = buildItemData(item, itemId, complexityOpt.get(), engine);
                allItems.add(itemData);

            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to export item: {}", itemId, e);
            }
        }

        allItems.sort(Comparator.comparingDouble(ExportData.ItemData::complexity).reversed());

        ExportData exportData = new ExportData(
                timestamp,
                allItems.size(),
                allItems
        );

        String json = GSON.toJson(exportData);
        Files.writeString(exportFile, json);

        ComplexityAnalyzer.LOGGER.info("Successfully exported {} items", allItems.size());

        return exportFile;
    }

    public static Path exportCategory(MinecraftServer server, AnalysisEngine engine, String categoryName) throws IOException {
        Path exportDir = getExportDirectory(server);
        Files.createDirectories(exportDir);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("complexity_" + categoryName.toLowerCase() + "_" + timestamp + ".json");

        List<ExportData.ItemData> filteredItems = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);

            try {
                Optional<ItemComplexity> complexityOpt = engine.getComplexityResult(item);
                if (complexityOpt.isEmpty()) {
                    continue;
                }

                ItemComplexity complexity = complexityOpt.get();

                if (!complexity.getCategory().getDisplayName().equalsIgnoreCase(categoryName)) {
                    continue;
                }

                ExportData.ItemData itemData = buildItemData(item, itemId, complexity, engine);
                filteredItems.add(itemData);

            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to export item: {}", itemId, e);
            }
        }

        filteredItems.sort(Comparator.comparingDouble(ExportData.ItemData::complexity).reversed());

        ExportData exportData = new ExportData(
                timestamp,
                filteredItems.size(),
                filteredItems
        );

        String json = GSON.toJson(exportData);
        Files.writeString(exportFile, json);

        ComplexityAnalyzer.LOGGER.info("Exported {} items of category {}", filteredItems.size(), categoryName);

        return exportFile;
    }

    public static Path exportTop(MinecraftServer server, AnalysisEngine engine, int count) throws IOException {
        Path exportDir = getExportDirectory(server);
        Files.createDirectories(exportDir);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("complexity_top" + count + "_" + timestamp + ".json");

        List<ExportData.ItemData> allItems = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);

            try {
                Optional<ItemComplexity> complexityOpt = engine.getComplexityResult(item);
                if (complexityOpt.isEmpty()) {
                    continue;
                }

                ExportData.ItemData itemData = buildItemData(item, itemId, complexityOpt.get(), engine);
                allItems.add(itemData);

            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to export item: {}", itemId, e);
            }
        }

        allItems.sort(Comparator.comparingDouble(ExportData.ItemData::complexity).reversed());
        List<ExportData.ItemData> topItems = allItems.stream()
                .limit(count)
                .collect(Collectors.toList());

        ExportData exportData = new ExportData(
                timestamp,
                topItems.size(),
                topItems
        );

        String json = GSON.toJson(exportData);
        Files.writeString(exportFile, json);

        ComplexityAnalyzer.LOGGER.info("Exported top {} items", count);

        return exportFile;
    }

    public static Path exportCSV(MinecraftServer server, AnalysisEngine engine) throws IOException {
        Path exportDir = getExportDirectory(server);
        Files.createDirectories(exportDir);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("complexity_export_" + timestamp + ".csv");

        StringBuilder csv = new StringBuilder();
        csv.append("Item ID,Display Name,Complexity,Category,Has Recipe,Crafting Depth,Used In Recipes,Is Valid,Has Cycle\n");

        List<CsvRow> rows = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);

            try {
                Optional<ItemComplexity> opt = engine.getComplexityResult(item);
                if (opt.isEmpty()) {
                    continue;
                }

                ItemComplexity c = opt.get();
                rows.add(new CsvRow(
                        itemId.toString(),
                        item.getDescription().getString(),
                        c.getComplexity(),
                        c.getCategory().getDisplayName(),
                        c.hasRecipe(),
                        c.getDepth(),
                        engine.getUsageCount(item),
                        c.isValid(),
                        c.hasCycle()
                ));

            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to process item for CSV: {}", itemId, e);
            }
        }

        rows.sort(Comparator.comparingDouble(CsvRow::complexity).reversed());

        for (CsvRow row : rows) {
            csv.append(String.format("%s,\"%s\",%.2f,%s,%s,%d,%d,%s,%s\n",
                    row.itemId,
                    row.displayName.replace("\"", "\"\""),
                    row.complexity,
                    row.category,
                    row.hasRecipe,
                    row.craftingDepth,
                    row.usedInRecipes,
                    row.isValid,
                    row.hasCycle
            ));
        }

        Files.writeString(exportFile, csv.toString());

        ComplexityAnalyzer.LOGGER.info("Exported {} items to CSV", rows.size());

        return exportFile;
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
            boolean hasCycle
    ) {}

    public static Path exportSingle(MinecraftServer server, AnalysisEngine engine, String itemIdString) throws IOException {
        Path exportDir = getExportDirectory(server).resolve("items");
        Files.createDirectories(exportDir);

        ResourceLocation itemId = ResourceLocation.parse(itemIdString);
        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);

        if (itemOpt.isEmpty()) {
            throw new IllegalArgumentException("Item not found: " + itemIdString);
        }

        Item item = itemOpt.get();
        Optional<ItemComplexity> complexityOpt = engine.getComplexityResult(item);

        if (complexityOpt.isEmpty()) {
            throw new IllegalStateException("Failed to analyze item: " + itemIdString);
        }

        ExportData.ItemData itemData = buildItemData(item, itemId, complexityOpt.get(), engine);

        String fileName = itemId.getNamespace() + "_" + itemId.getPath() + ".json";
        Path exportFile = exportDir.resolve(fileName);

        String json = GSON.toJson(itemData);
        Files.writeString(exportFile, json);

        ComplexityAnalyzer.LOGGER.info("Exported item {} to {}", itemId, exportFile);

        return exportFile;
    }

    private static ExportData.ItemData buildItemData(
            Item item,
            ResourceLocation itemId,
            ItemComplexity complexity,
            AnalysisEngine engine
    ) {
        String itemName = item.getDescription().getString();

        List<ExportData.SourceData> sources = engine.findAllSourcesForItem(item).stream()
                .map(data -> buildSourceData(data, engine))
                .sorted(Comparator.comparingDouble(ExportData.SourceData::estimatedCost))
                .collect(Collectors.toList());

        return new ExportData.ItemData(
                itemId.toString(),
                itemName,
                complexity.getComplexity(),
                complexity.getCategory().getDisplayName(),
                complexity.hasRecipe(),
                complexity.getDepth(),
                engine.getUsageCount(item),
                complexity.isValid(),
                complexity.hasCycle(),
                sources
        );
    }

    private static ExportData.SourceData buildSourceData(
            BaseResourceData data,
            AnalysisEngine engine
    ) {
        double fullCost = data.getBaseFactor();
        Map<String, Double> ingredients = new HashMap<>();

        if (!data.getSourceItems().isEmpty()) {
            for (var entry : data.getSourceItems().entrySet()) {
                Item sourceItem = entry.getKey();
                double amount = entry.getValue();

                ResourceLocation sourceId = BuiltInRegistries.ITEM.getKey(sourceItem);
                ingredients.put(sourceId.toString(), amount);

                Optional<ItemComplexity> sourceComplexity = engine.getComplexityResult(sourceItem);
                if (sourceComplexity.isPresent() && sourceComplexity.get().isValid()) {
                    fullCost += sourceComplexity.get().getComplexity() * amount;
                } else {
                    fullCost = Double.POSITIVE_INFINITY;
                    break;
                }
            }
        }

        return new ExportData.SourceData(
                data.getSourceType().getDisplayName(),
                data.getBaseFactor(),
                fullCost,
                data.getDetails(),
                ingredients
        );
    }

    private static Path getExportDirectory(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data")
                .resolve("complexityanalyzer")
                .resolve("export");
    }
}