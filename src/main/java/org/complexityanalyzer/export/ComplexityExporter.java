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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.export.ExportData.MobData;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
                case NULL -> { in.nextNull(); yield null; }
                default -> throw new JsonSyntaxException("Expected number or string");
            };
        }
    }

    public static Path exportAllItems(MinecraftServer server, AnalysisEngine engine) throws IOException {
        Path exportDir = getExportDirectory(server);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("items_all_" + timestamp + ".json");
        List<ExportData.ItemData> allItems = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            engine.getComplexityResult(item).ifPresent(c ->
                    allItems.add(buildItemData(item, BuiltInRegistries.ITEM.getKey(item), c, engine))
            );
        }
        allItems.sort(Comparator.comparingDouble(ExportData.ItemData::complexity).reversed());
        String json = GSON.toJson(new ExportData(timestamp, allItems.size(), allItems));
        Files.writeString(exportFile, json);
        return exportFile;
    }

    public static Path exportItemsByCategory(MinecraftServer server, AnalysisEngine engine, String categoryName) throws IOException {
        Path exportDir = getExportDirectory(server);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("items_category_" + categoryName.toLowerCase() + "_" + timestamp + ".json");
        List<ExportData.ItemData> filteredItems = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            engine.getComplexityResult(item).ifPresent(c -> {
                if (c.getCategory().getDisplayName().equalsIgnoreCase(categoryName)) {
                    filteredItems.add(buildItemData(item, BuiltInRegistries.ITEM.getKey(item), c, engine));
                }
            });
        }
        filteredItems.sort(Comparator.comparingDouble(ExportData.ItemData::complexity).reversed());
        String json = GSON.toJson(new ExportData(timestamp, filteredItems.size(), filteredItems));
        Files.writeString(exportFile, json);
        return exportFile;
    }

    public static Path exportTopItems(MinecraftServer server, AnalysisEngine engine, int count) throws IOException {
        Path exportDir = getExportDirectory(server);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("items_top" + count + "_" + timestamp + ".json");
        List<ExportData.ItemData> allItems = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            engine.getComplexityResult(item).ifPresent(c ->
                    allItems.add(buildItemData(item, BuiltInRegistries.ITEM.getKey(item), c, engine))
            );
        }
        allItems.sort(Comparator.comparingDouble(ExportData.ItemData::complexity).reversed());
        List<ExportData.ItemData> topItems = allItems.stream().limit(count).collect(Collectors.toList());
        String json = GSON.toJson(new ExportData(timestamp, topItems.size(), topItems));
        Files.writeString(exportFile, json);
        return exportFile;
    }

    public static Path exportItemsCSV(MinecraftServer server, AnalysisEngine engine) throws IOException {
        Path exportDir = getExportDirectory(server);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportFile = exportDir.resolve("items_all_" + timestamp + ".csv");
        List<CsvRow> rows = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            engine.getComplexityResult(item).ifPresent(c -> rows.add(new CsvRow(
                    BuiltInRegistries.ITEM.getKey(item).toString(), item.getDescription().getString(),
                    c.getComplexity(), c.getCategory().getDisplayName(), c.hasRecipe(), c.getDepth(),
                    engine.getUsageCount(item), c.isValid(), c.hasCycle()
            )));
        }
        rows.sort(Comparator.comparingDouble(CsvRow::complexity).reversed());
        try (PrintWriter writer = new PrintWriter(exportFile.toFile(), StandardCharsets.UTF_8)) {
            writer.println("Item ID,Display Name,Complexity,Category,Has Recipe,Crafting Depth,Used In Recipes,Is Valid,Has Cycle");
            for (CsvRow row : rows) {
                writer.println(String.format(Locale.US, "%s,\"%s\",%.2f,%s,%s,%d,%d,%s,%s",
                        row.itemId, row.displayName.replace("\"", "\"\""), row.complexity, row.category,
                        row.hasRecipe, row.craftingDepth, row.usedInRecipes, row.isValid, row.hasCycle
                ));
            }
        }
        return exportFile;
    }

    public static Path exportSingleItem(MinecraftServer server, AnalysisEngine engine, String itemIdString) throws IOException {
        Path exportDir = getExportDirectory(server).resolve("items");
        Files.createDirectories(exportDir);
        ResourceLocation itemId = ResourceLocation.parse(itemIdString);
        Item item = BuiltInRegistries.ITEM.getOptional(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemIdString));
        ItemComplexity complexity = engine.getComplexityResult(item)
                .orElseThrow(() -> new IllegalStateException("Failed to analyze item: " + itemIdString));
        ExportData.ItemData itemData = buildItemData(item, itemId, complexity, engine);
        Path exportFile = exportDir.resolve(itemId.getNamespace() + "_" + itemId.getPath() + ".json");
        Files.writeString(exportFile, GSON.toJson(itemData));
        return exportFile;
    }

    public static Path exportAllMobs(MinecraftServer server, AnalysisEngine engine, String format) throws IOException {
        return exportMobsAs(server, engine, format, null, -1, "all");
    }

    public static Path exportMobsByCategory(MinecraftServer server, AnalysisEngine engine, String categoryName) throws IOException {
        return exportMobsAs(server, engine, "json", categoryName, -1, "category_" + categoryName);
    }

    public static Path exportTopMobs(MinecraftServer server, AnalysisEngine engine, int count) throws IOException {
        return exportMobsAs(server, engine, "json", null, count, "top" + count);
    }

    public static Path exportSingleMob(MinecraftServer server, AnalysisEngine engine, String mobIdString) throws IOException {
        Path exportDir = getExportDirectory(server).resolve("mobs");
        Files.createDirectories(exportDir);
        ResourceLocation mobId = ResourceLocation.parse(mobIdString);
        EntityType<?> mobType = Optional.of(BuiltInRegistries.ENTITY_TYPE.get(mobId))
                .orElseThrow(() -> new IllegalArgumentException("Mob not found: " + mobIdString));
        MobData mobData = buildMobData(mobType, engine);
        if (mobData == null) {
            throw new IllegalStateException("Failed to analyze mob: " + mobIdString);
        }
        Path exportFile = exportDir.resolve(mobId.getNamespace() + "_" + mobId.getPath() + ".json");
        Files.writeString(exportFile, GSON.toJson(mobData));
        return exportFile;
    }

    // --- ПРИВАТНЫЕ ХЕЛПЕРЫ ---

    private record CsvRow(String itemId, String displayName, double complexity, String category, boolean hasRecipe, int craftingDepth, int usedInRecipes, boolean isValid, boolean hasCycle) {}

    private static Path getExportDirectory(MinecraftServer server) throws IOException {
        // Используем ваш правильный и элегантный подход.
        // server.getWorldPath() возвращает Path, и мы просто продолжаем с ним работать.
        Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data")
                .resolve("complexityanalyzer")
                .resolve("export");

        // Нормализуем путь, чтобы он был "чистым"
        dir = dir.toAbsolutePath().normalize();

        // Создаем директорию, если ее нет
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        // Логирование для проверки
        ComplexityAnalyzer.LOGGER.info("[Exporter] Resolved export directory to absolute path: {}", dir);

        return dir;
    }

    private static ExportData.ItemData buildItemData(Item item, ResourceLocation itemId, ItemComplexity complexity, AnalysisEngine engine) {
        List<ExportData.SourceData> sources = engine.findAllSourcesForItem(item).stream()
                .map(data -> buildSourceData(data, engine))
                .sorted(Comparator.comparingDouble(ExportData.SourceData::estimatedCost))
                .collect(Collectors.toList());
        return new ExportData.ItemData(itemId.toString(), item.getDescription().getString(), complexity.getComplexity(),
                complexity.getCategory().getDisplayName(), complexity.hasRecipe(), complexity.getDepth(),
                engine.getUsageCount(item), complexity.isValid(), complexity.hasCycle(), sources);
    }

    private static ExportData.SourceData buildSourceData(BaseResourceData data, AnalysisEngine engine) {
        double fullCost = data.getBaseFactor();
        Map<String, Double> ingredients = new HashMap<>();
        if (!data.getSourceItems().isEmpty()) {
            for (var entry : data.getSourceItems().entrySet()) {
                Item sourceItem = entry.getKey();
                double amount = entry.getValue();
                ingredients.put(BuiltInRegistries.ITEM.getKey(sourceItem).toString(), amount);
                Optional<ItemComplexity> sourceComplexity = engine.getComplexityResult(sourceItem);
                if (sourceComplexity.isPresent() && sourceComplexity.get().isValid()) {
                    fullCost += sourceComplexity.get().getComplexity() * amount;
                } else {
                    fullCost = Double.POSITIVE_INFINITY;
                    break;
                }
            }
        }
        return new ExportData.SourceData(data.getSourceType().getDisplayName(), data.getBaseFactor(), fullCost, data.getDetails(), ingredients);
    }

    private static Path exportMobsAs(MinecraftServer server, AnalysisEngine engine, String format, String categoryFilter, int topN, String fileSuffix) throws IOException {
        List<MobData> mobDataList = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            // ❌ БЫЛО: if (!type.getCategory().isPersistent()) continue;
            // ✅ СТАЛО:
            if (type.getCategory() == MobCategory.MISC) continue;

            if (categoryFilter != null && !type.getCategory().getName().equalsIgnoreCase(categoryFilter)) continue;
            MobData data = buildMobData(type, engine);
            if (data != null) mobDataList.add(data);
        }
        mobDataList.sort(Comparator.comparingDouble(MobData::combatPower).reversed());
        if (topN > 0) {
            mobDataList = mobDataList.stream().limit(topN).collect(Collectors.toList());
        }

        Path exportDir = getExportDirectory(server);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path exportPath = exportDir.resolve("mobs_" + fileSuffix + "_" + timestamp + "." + format);

        if ("csv".equalsIgnoreCase(format)) {
            try (PrintWriter writer = new PrintWriter(exportPath.toFile(), StandardCharsets.UTF_8)) {
                writer.println("Name,ID,Category,Health,Damage,Armor,Survivability,Threat,Combat Power,Rarity,Is Boss,Is MiniBoss,Notable Drops");
                for (MobData data : mobDataList) {
                    String drops = data.drops().stream().map(d -> String.format("%s (%.2f)", d.itemName(), d.yieldPerKill())).collect(Collectors.joining("; "));
                    writer.println(String.format(Locale.US, "\"%s\",\"%s\",\"%s\",%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%b,%b,\"%s\"",
                            data.name(), data.id(), data.category(), data.health(), data.damage(), data.armor(),
                            data.survivability(), data.threat(), data.combatPower(), data.rarity(),
                            data.isBoss(), data.isMiniBoss(), drops.isEmpty() ? "None" : drops));
                }
            }
        } else { // JSON
            Files.writeString(exportPath, GSON.toJson(mobDataList));
        }
        return exportPath;
    }

    private static MobData buildMobData(EntityType<?> type, AnalysisEngine engine) {
        MobPropertyProvider mobProvider = engine.getMobPropertyProvider().orElse(null);
        if (mobProvider == null) return null;
        MobPropertyProvider.MobProperties props = mobProvider.getProperties(type).orElse(null);
        if (props == null) return null;

        List<ExportData.MobDropData> drops = new ArrayList<>();
        engine.getMobDropSource().ifPresent(source -> drops.addAll(source.getDropsForEntity(type).stream()
                .map(d -> new ExportData.MobDropData(BuiltInRegistries.ITEM.getKey(d.item()).toString(), d.item().getDescription().getString(), d.averageYield()))
                .toList()));

        return new MobData(type.getDescription().getString(), BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
                type.getCategory().getName(), props.maxHealth(), props.attackDamage(), props.armor(),
                props.calculateSurvivability(), props.calculateThreat(), props.calculateCombatPower(),
                mobProvider.getRarity(type), mobProvider.isBoss(type), mobProvider.isMiniBoss(type), drops);
    }
}