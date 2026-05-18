package org.complexityanalyzer.bytecode.cache;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class BytecodeCacheManager {

    private static final String CACHE_FILENAME = "bytecode_cache.json";
    private static final String HASH_ALGORITHM = "SHA-256";

    private BytecodeCacheManager() {
    }

    private static Path cacheFile(Path worldDir) {
        return worldDir.resolve("complexityanalyzer").resolve(CACHE_FILENAME);
    }

    public static BytecodeCache loadCache(Path worldDir) {
        Path file = cacheFile(worldDir);
        if (!Files.exists(file)) {
            ComplexityAnalyzer.LOGGER.info("[Cache] No cache file found for world {}, full scan needed", worldDir.getFileName());
            return new BytecodeCache();
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            var hashes = parseJson(json);
            ComplexityAnalyzer.LOGGER.info("[Cache] Loaded {} cached class hashes", hashes.size());
            return new BytecodeCache(hashes);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[Cache] Failed to load cache: {}", e.getMessage());
            return new BytecodeCache();
        }
    }

    public static void saveCache(BytecodeCache cache, Path worldDir) {
        Path file = cacheFile(worldDir);
        try {
            Files.createDirectories(file.getParent());
            String json = toJson(cache.getAll());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            ComplexityAnalyzer.LOGGER.info("[Cache] Saved {} class hashes", cache.size());
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Cache] Failed to save: {}", e.getMessage());
        }
    }

    public static Map<String, byte[]> filterChanged(Map<String, byte[]> allClasses, BytecodeCache cache) {
        var changed = new ConcurrentHashMap<String, byte[]>();
        var total = new AtomicInteger(0);
        var skipped = new AtomicInteger(0);

        allClasses.entrySet().parallelStream().forEach(entry -> {
            total.incrementAndGet();
            String hash = computeHash(entry.getValue());
            if (cache.isChanged(entry.getKey(), hash)) {
                changed.put(entry.getKey(), entry.getValue());
                cache.put(entry.getKey(), hash);
            } else {
                skipped.incrementAndGet();
            }
        });

        ComplexityAnalyzer.LOGGER.info("[Cache] {} classes total, {} changed, {} skipped",
                total.get(), changed.size(), skipped.get());
        return changed;
    }

    public static String computeHash(byte[] data) {
        try {
            var md = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (Exception e) {
            return "";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    private static Map<String, String> parseJson(String json) {
        if (json == null || json.isBlank()) return new Object2ObjectOpenHashMap<>();
        try {
            var type = new com.google.gson.reflect.TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> parsed = GSON.fromJson(json, type);
            if (parsed == null) return new Object2ObjectOpenHashMap<>();
            return new Object2ObjectOpenHashMap<>(parsed);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Cache] Failed to parse JSON cache", e);
            return new Object2ObjectOpenHashMap<>();
        }
    }

    private static String toJson(Map<String, String> map) {
        try {
            var sorted = new TreeMap<>(map);
            return GSON.toJson(sorted);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Cache] Failed to serialize JSON cache", e);
            return "{}";
        }
    }
}