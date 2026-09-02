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

package org.complexityanalyzer.geoscan.storage;

import com.google.gson.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.BiomeDataMapper;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.util.ModFileManager;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public class GeoDataStorage {
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(ResourceLocation.class, new ResourceLocationAdapter()).create();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(ResourceLocation.class, new ResourceLocationAdapter()).create();
    private final Path dataDir;
    private final Path reconDir;
    private final Path finalDir;
    private final Path metadataFile;
    private final ConcurrentHashMap<Path, Object> fileLockMarkers = new ConcurrentHashMap<>();

    public GeoDataStorage(MinecraftServer server) {
        this.dataDir = ModFileManager.resolve(server);
        this.reconDir = ModFileManager.resolve(server, "recon");
        this.finalDir = ModFileManager.resolve(server, "final");
        this.metadataFile = ModFileManager.resolve(server, "metadata.json");
    }

    public ScanMetadata loadMetadata() {
        if (!ModFileManager.exists(metadataFile)) return new ScanMetadata(ScanMetadata.ScanPhase.IDLE);
        try {
            String json = ModFileManager.readString(metadataFile);
            var meta = PRETTY_GSON.fromJson(json, ScanMetadata.class);
            return meta != null ? meta : new ScanMetadata(ScanMetadata.ScanPhase.IDLE);
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to read metadata file! Assuming IDLE state.", e);
            return new ScanMetadata(ScanMetadata.ScanPhase.IDLE);
        }
    }

    public void saveMetadata(ScanMetadata metadata) {
        try {
            ModFileManager.writeStringAtomic(metadataFile, PRETTY_GSON.toJson(metadata));
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to write metadata file!", e);
        }
    }

    public void appendReconData(ResourceLocation dimension, ResourceLocation biome, ObjectArrayList<ChunkSnapshot> newSnapshots) {
        if (newSnapshots.isEmpty()) return;
        var file = getReconFilePath(dimension, biome);

        fileLockMarkers.compute(file, (k, v) -> {
            try {
                var lines = new ObjectArrayList<String>(newSnapshots.size());
                for (var newSnapshot : newSnapshots) lines.add(GSON.toJson(newSnapshot));
                ModFileManager.appendLines(file, lines);
            } catch (IOException e) {
                ComplexityAnalyzer.LOGGER.error("Failed to append recon data for biome {}", biome, e);
            }
            return Boolean.TRUE;
        });
    }

    public Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>> getAllReconFilePaths() {
        var allPaths = new Object2ObjectOpenHashMap<ResourceLocation, Object2ObjectMap<ResourceLocation, Path>>();
        if (!ModFileManager.exists(reconDir)) return allPaths;

        try {
            var dimNamespaces = ModFileManager.list(reconDir);
            for (var dimNamespaceDir : dimNamespaces) {
                if (!ModFileManager.exists(dimNamespaceDir)) continue;

                var dimPaths = ModFileManager.list(dimNamespaceDir);
                for (var dimPathDir : dimPaths) {
                    if (!ModFileManager.exists(dimPathDir)) continue;

                    var dimensionId = ResourceLocation.fromNamespaceAndPath(
                            dimNamespaceDir.getFileName().toString(),
                            dimPathDir.getFileName().toString()
                    );

                    var biomeFiles = new Object2ObjectOpenHashMap<ResourceLocation, Path>();
                    var files = ModFileManager.list(dimPathDir);
                    for (var filePath : files) {
                        String fileName = filePath.getFileName().toString();
                        if (!fileName.endsWith(".jsonl")) continue;

                        String encodedName = fileName.substring(0, fileName.length() - 6);
                        var biomeId = decodeLocation(encodedName);
                        biomeFiles.put(biomeId, filePath);
                    }

                    if (!biomeFiles.isEmpty()) allPaths.put(dimensionId, biomeFiles);
                }
            }
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to list dimension namespaces in recon directory", e);
        }
        return allPaths;
    }

    public ObjectArrayList<ChunkSnapshot> readReconFile(Path path) {
        var snapshots = new ObjectArrayList<ChunkSnapshot>();
        if (!ModFileManager.exists(path)) return snapshots;
        try {
            var lines = ModFileManager.readLines(path);
            for (var line : lines) {
                try {
                    var snapshot = GSON.fromJson(line, ChunkSnapshot.class);
                    if (snapshot != null) snapshots.add(snapshot);
                } catch (JsonSyntaxException e) {
                    ComplexityAnalyzer.LOGGER.error("Failed to parse line in recon file {}: {}", path, line, e);
                }
            }
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to read recon file {}", path, e);
        }
        return snapshots;
    }

    public Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, BiomeScanData>> loadAllFinalData(BiomeDataMapper mapper) {
        var loadedData = loadDataFromDirectory(finalDir, (jsonString) -> {
            var data = PRETTY_GSON.fromJson(jsonString, BiomeScanData.class);
            if (data != null) mapper.afterLoad(data);
            return data;
        });

        var result = new Object2ObjectOpenHashMap<ResourceLocation, Object2ObjectMap<ResourceLocation, BiomeScanData>>();
        for (var entry : Object2ObjectMaps.fastIterable(loadedData)) {
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
        ModFileManager.delete(dataDir);
    }

    public void deleteFinalData() {
        ModFileManager.delete(finalDir);
    }

    private void saveJson(Path file, Object data) {
        try {
            ModFileManager.writeStringAtomic(file, GeoDataStorage.PRETTY_GSON.toJson(data));
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to save JSON to file {}", file, e);
        }
    }

    private <T> Object2ObjectMap<ResourceLocation, Object2ObjectMap<ResourceLocation, T>> loadDataFromDirectory(Path rootDir, ThrowingFunction<String, T> fromJson) {
        var allData = new Object2ObjectOpenHashMap<ResourceLocation, Object2ObjectMap<ResourceLocation, T>>();
        if (!ModFileManager.exists(rootDir)) return allData;

        try {
            var dimNamespaces = ModFileManager.list(rootDir);
            for (var dimNamespaceDir : dimNamespaces) {
                if (!ModFileManager.exists(dimNamespaceDir)) continue;

                var dimPaths = ModFileManager.list(dimNamespaceDir);
                for (var dimPathDir : dimPaths) {
                    if (!ModFileManager.exists(dimPathDir)) continue;

                    var dimensionId = ResourceLocation.fromNamespaceAndPath(
                            dimNamespaceDir.getFileName().toString(),
                            dimPathDir.getFileName().toString()
                    );

                    var biomeData = new Object2ObjectOpenHashMap<ResourceLocation, T>();
                    var biomeFiles = ModFileManager.list(dimPathDir);
                    for (var biomeFile : biomeFiles) {
                        String fileName = biomeFile.getFileName().toString();
                        if (!fileName.endsWith(".json")) continue;

                        try {
                            String json = ModFileManager.readString(biomeFile);
                            var data = fromJson.apply(json);
                            if (data != null) {
                                String encodedName = fileName.substring(0, fileName.length() - 5);
                                var biomeId = decodeLocation(encodedName);
                                biomeData.put(biomeId, data);
                            }
                        } catch (Exception e) {
                            ComplexityAnalyzer.LOGGER.error("Failed to load/parse data from file {}", biomeFile, e);
                        }
                    }

                    if (!biomeData.isEmpty()) allData.put(dimensionId, biomeData);
                }
            }
        } catch (IOException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to list dimension namespaces in directory {}", rootDir, e);
        }
        return allData;
    }

    private String encodeLocation(ResourceLocation location) {
        return location.getNamespace() + "~" + location.getPath().replace('/', '@');
    }

    private ResourceLocation decodeLocation(String encoded) {
        int tildeIndex = encoded.indexOf('~');
        if (tildeIndex == -1) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", encoded.replace('@', '/'));
        }
        String namespace = encoded.substring(0, tildeIndex);
        String path = encoded.substring(tildeIndex + 1).replace('@', '/');
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private Path getReconFilePath(ResourceLocation dimension, ResourceLocation biome) {
        String biomeFileName = encodeLocation(biome) + ".jsonl";
        return reconDir.resolve(dimension.getNamespace()).resolve(dimension.getPath()).resolve(biomeFileName);
    }

    public int countReconChunks(ResourceLocation dimension, ResourceLocation biome) {
        var path = getReconFilePath(dimension, biome);
        if (!ModFileManager.exists(path)) return 0;
        try {
            return ModFileManager.readLines(path).size();
        } catch (IOException e) {
            return 0;
        }
    }

    public Object2ObjectMap<ResourceLocation, LongSet> loadAllReconChunkCoordinates() {
        var allCoordinates = new Object2ObjectOpenHashMap<ResourceLocation, LongSet>();
        var allPaths = getAllReconFilePaths();

        for (var dimEntry : Object2ObjectMaps.fastIterable(allPaths)) {
            var dim = dimEntry.getKey();
            var coordinatesForDimension = allCoordinates.computeIfAbsent(dim, k -> new LongOpenHashSet());
            for (var path : dimEntry.getValue().values()) {
                var snapshots = readReconFile(path);
                for (var snapshot : snapshots) {
                    coordinatesForDimension.add(ChunkPos.asLong(snapshot.chunkX(), snapshot.chunkZ()));
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