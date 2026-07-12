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

package org.complexityanalyzer.geoscan.storage;

import com.google.gson.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.BiomeDataMapper;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.data.ScanMetadata;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class GeoDataStorage {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ResourceLocation.class, new ResourceLocationAdapter())
            .create();
    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(ResourceLocation.class, new ResourceLocationAdapter())
            .create();
    private final Path dataDir;
    private final Path reconDir;
    private final Path finalDir;
    private final Path metadataFile;
    private final ConcurrentHashMap<Path, Object> fileLockMarkers = new ConcurrentHashMap<>();

    public GeoDataStorage(MinecraftServer server) {
        this.dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(ComplexityAnalyzer.MODID);
        this.reconDir = dataDir.resolve("recon");
        this.finalDir = dataDir.resolve("final");
        this.metadataFile = dataDir.resolve("metadata.json");
    }

    public void ensureDirectoriesExist() {
        try {
            Files.createDirectories(this.reconDir);
            Files.createDirectories(this.finalDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create world-specific geo-data directories!", e);
        }
    }

    public ScanMetadata loadMetadata() {
        if (!Files.exists(metadataFile)) return new ScanMetadata(ScanMetadata.ScanPhase.IDLE);
        try (var reader = new FileReader(metadataFile.toFile())) {
            var meta = PRETTY_GSON.fromJson(reader, ScanMetadata.class);
            return meta != null ? meta : new ScanMetadata(ScanMetadata.ScanPhase.IDLE);
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to read metadata file! Assuming IDLE state.", e);
            return new ScanMetadata(ScanMetadata.ScanPhase.IDLE);
        }
    }

    public void saveMetadata(ScanMetadata metadata) {
        try (var writer = new FileWriter(metadataFile.toFile())) {
            PRETTY_GSON.toJson(metadata, writer);
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to write metadata file!", e);
        }
    }

    public void appendReconData(ResourceLocation dimension, ResourceLocation biome, ObjectArrayList<ChunkSnapshot> newSnapshots) {
        if (newSnapshots.isEmpty()) return;
        var file = getReconFilePath(dimension, biome);

        fileLockMarkers.compute(file, (k, v) -> {
            try {
                Files.createDirectories(file.getParent());
                try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (int i = 0, n = newSnapshots.size(); i < n; i++) {
                        writer.write(GSON.toJson(newSnapshots.get(i)));
                        writer.newLine();
                    }
                }
            } catch (IOException e) {
                ComplexityAnalyzer.LOGGER.error("Failed to append recon data for biome {}", biome, e);
            }
            return Boolean.TRUE;
        });
    }

    public Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>> getAllReconFilePaths() {
        var allPaths = new Object2ObjectOpenHashMap<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>>();
        if (!Files.exists(reconDir)) return allPaths;

        try (var dimNamespaces = Files.list(reconDir)) {
            var dimNsIt = dimNamespaces.filter(Files::isDirectory).iterator();
            while (dimNsIt.hasNext()) {
                var dimNamespaceDir = dimNsIt.next();
                try (var dimPaths = Files.list(dimNamespaceDir)) {
                    var dimPathIt = dimPaths.filter(Files::isDirectory).iterator();
                    while (dimPathIt.hasNext()) {
                        var dimPathDir = dimPathIt.next();
                        var dimensionId = ResourceLocation.fromNamespaceAndPath(
                                dimNamespaceDir.getFileName().toString(),
                                dimPathDir.getFileName().toString()
                        );
                        var biomeFiles = new Object2ObjectOpenHashMap<ResourceLocation, Path>();
                        try (var files = Files.list(dimPathDir)) {
                            var fileIt = files.filter(f -> f.toString().endsWith(".jsonl")).iterator();
                            while (fileIt.hasNext()) {
                                var filePath = fileIt.next();
                                String fileName = filePath.getFileName().toString();
                                String encodedName = fileName.substring(0, fileName.length() - 6);
                                var biomeId = decodeLocation(encodedName);
                                biomeFiles.put(biomeId, filePath);
                            }
                        } catch (IOException e) {
                            ComplexityAnalyzer.LOGGER.error("Failed to list biome files in {}", dimPathDir, e);
                        }

                        if (!biomeFiles.isEmpty()) allPaths.put(dimensionId, biomeFiles);
                    }
                } catch (IOException e) {
                    ComplexityAnalyzer.LOGGER.error("Failed to list dimension paths in {}", dimNamespaceDir, e);
                }
            }
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to list dimension namespaces in recon directory", e);
        }
        return allPaths;
    }

    public Stream<ChunkSnapshot> streamReconFile(Path path) {
        if (!Files.exists(path)) return Stream.empty();
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            var snapshots = new ObjectArrayList<ChunkSnapshot>();
            var it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                try {
                    var snapshot = GSON.fromJson(line, ChunkSnapshot.class);
                    if (snapshot != null) snapshots.add(snapshot);
                } catch (JsonSyntaxException e) {
                    ComplexityAnalyzer.LOGGER.error("Failed to parse line in recon file {}: {}", path, line, e);
                }
            }
            return snapshots.stream();
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to stream recon file {}", path, e);
            return Stream.empty();
        }
    }

    public Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, BiomeScanData>> loadAllFinalData(BiomeDataMapper mapper) {
        var loadedData = loadDataFromDirectory(finalDir, (reader) -> {
            var data = PRETTY_GSON.fromJson(reader, BiomeScanData.class);
            if (data != null) mapper.afterLoad(data);
            return data;
        });

        var result = new Object2ObjectOpenHashMap<ResourceLocation, Object2ObjectMap<ResourceLocation, BiomeScanData>>();
        for (var entry : loadedData.object2ObjectEntrySet()) {
            result.put(entry.getKey(), new Object2ObjectOpenHashMap<>(entry.getValue()));
        }
        return result;
    }

    public void saveFinalBiomeData(ResourceLocation dimension, ResourceLocation biome,
                                   BiomeScanData data, BiomeDataMapper mapper) {
        mapper.prepareForSave(data);
        var file = getFinalFilePath(dimension, biome);
        saveJson(file, data);
    }

    public void deleteAllData() {
        try {
            if (Files.exists(dataDir)) try (var walk = Files.walk(dataDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
            }
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to clear geo-data directory.", e);
        } finally {
            ensureDirectoriesExist();
        }
    }

    public void deleteFinalData() throws IOException {
        deleteDirectory(finalDir);
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
        }
        Files.createDirectories(dir);
    }

    private void deletePath(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to delete path: {}", path, e);
        }
    }

    private void saveJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            try (var writer = new FileWriter(file.toFile())) {
                GeoDataStorage.PRETTY_GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to save JSON to file {}", file, e);
        }
    }

    private <T> Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, T>> loadDataFromDirectory(Path rootDir, ThrowingFunction<FileReader, T> fromJson) {
        var allData = new Object2ObjectOpenHashMap<ResourceLocation, Object2ObjectMap<ResourceLocation, T>>();
        if (!Files.exists(rootDir)) return allData;

        try (var dimNamespaces = Files.list(rootDir)) {
            var dimNsIt = dimNamespaces.filter(Files::isDirectory).iterator();
            while (dimNsIt.hasNext()) {
                var dimNamespaceDir = dimNsIt.next();
                try (var dimPaths = Files.list(dimNamespaceDir)) {
                    var dimPathIt = dimPaths.filter(Files::isDirectory).iterator();
                    while (dimPathIt.hasNext()) {
                        var dimPathDir = dimPathIt.next();
                        var dimensionId = ResourceLocation.fromNamespaceAndPath(
                                dimNamespaceDir.getFileName().toString(),
                                dimPathDir.getFileName().toString()
                        );

                        var biomeData = new Object2ObjectOpenHashMap<ResourceLocation, T>();
                        try (var biomeFiles = Files.list(dimPathDir)) {
                            var fileIt = biomeFiles.filter(f -> f.toString().endsWith(".json")).iterator();
                            while (fileIt.hasNext()) {
                                var biomeFile = fileIt.next();
                                try (var reader = new FileReader(biomeFile.toFile())) {
                                    var data = fromJson.apply(reader);
                                    if (data != null) {
                                        String fileName = biomeFile.getFileName().toString();
                                        String encodedName = fileName.substring(0, fileName.length() - 5);
                                        var biomeId = decodeLocation(encodedName);
                                        biomeData.put(biomeId, data);
                                    }
                                } catch (Exception e) {
                                    ComplexityAnalyzer.LOGGER.error("Failed to load/parse data from file {}", biomeFile, e);
                                }
                            }
                        } catch (IOException e) {
                            ComplexityAnalyzer.LOGGER.error("Failed to list biome files in {}", dimPathDir, e);
                        }

                        if (!biomeData.isEmpty()) allData.put(dimensionId, biomeData);
                    }
                } catch (IOException e) {
                    ComplexityAnalyzer.LOGGER.error("Failed to list dimension paths in {}", dimNamespaceDir, e);
                }
            }
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to list dimension namespaces in directory {}", rootDir, e);
        }
        return allData;
    }

    private String encodeLocation(ResourceLocation location) {
        return location.getNamespace() + "~" + location.getPath().replace('/', '_');
    }

    private ResourceLocation decodeLocation(String encoded) {
        String[] parts = encoded.split("~", 2);
        if (parts.length != 2) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", encoded.replace('_', '/'));
        }
        return ResourceLocation.fromNamespaceAndPath(parts[0], parts[1].replace('_', '/'));
    }

    private Path getReconFilePath(ResourceLocation dimension, ResourceLocation biome) {
        String biomeFileName = encodeLocation(biome) + ".jsonl";
        return reconDir.resolve(dimension.getNamespace()).resolve(dimension.getPath()).resolve(biomeFileName);
    }

    public int countReconChunks(ResourceLocation dimension, ResourceLocation biome) {
        var path = getReconFilePath(dimension, biome);
        if (!Files.exists(path)) return 0;
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return (int) lines.count();
        } catch (IOException e) {
            return 0;
        }
    }

    public Object2ObjectMap<ResourceLocation, LongOpenHashSet> loadAllReconChunkCoordinates() {
        var allCoordinates = new Object2ObjectOpenHashMap<ResourceLocation, LongOpenHashSet>();
        var allPaths = getAllReconFilePaths();

        for (var dimEntry : allPaths.object2ObjectEntrySet()) {
            var dim = dimEntry.getKey();
            var coordinatesForDimension = allCoordinates.computeIfAbsent(dim, ignored -> new LongOpenHashSet());
            for (var path : dimEntry.getValue().values()) {
                try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
                    var it = lines.iterator();
                    while (it.hasNext()) {
                        try {
                            var snapshot = GSON.fromJson(it.next(), ChunkSnapshot.class);
                            if (snapshot != null) {
                                coordinatesForDimension.add(ChunkPos.asLong(snapshot.chunkX(), snapshot.chunkZ()));
                            }
                        } catch (JsonSyntaxException ignored) {
                        }
                    }
                } catch (IOException e) {
                    ComplexityAnalyzer.LOGGER.debug("Failed to read recon file: {}", path);
                }
            }
        }

        return allCoordinates;
    }

    private Path getFinalFilePath(ResourceLocation dimension, ResourceLocation biome) {
        String biomeFileName = encodeLocation(biome) + ".json";
        return finalDir.resolve(dimension.getNamespace()).resolve(dimension.getPath()).resolve(biomeFileName);
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private static class ResourceLocationAdapter implements JsonSerializer<ResourceLocation>, JsonDeserializer<ResourceLocation> {
        @Override
        public JsonElement serialize(ResourceLocation src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public ResourceLocation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json.isJsonNull()) return null;
            return ResourceLocation.parse(json.getAsString());
        }
    }
}