package org.complexityanalyzer.bytecode.cache;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class BytecodeCacheManager {

    private static final Path CACHE_FILE = Path.of("config", "complexityanalyzer", "bytecode_cache.json");
    private static final String HASH_ALGORITHM = "SHA-256";

    private BytecodeCacheManager() {
    }

    public static BytecodeCache loadCache() {
        if (!Files.exists(CACHE_FILE)) {
            ComplexityAnalyzer.LOGGER.info("[Cache] No cache file found, full scan needed");
            return new BytecodeCache();
        }

        try {
            String json = Files.readString(CACHE_FILE, StandardCharsets.UTF_8);
            var hashes = parseJson(json);
            ComplexityAnalyzer.LOGGER.info("[Cache] Loaded {} cached class hashes", hashes.size());
            return new BytecodeCache(hashes);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("[Cache] Failed to load cache: {}", e.getMessage());
            return new BytecodeCache();
        }
    }

    public static void saveCache(BytecodeCache cache) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            String json = toJson(cache.getAll());
            Files.writeString(CACHE_FILE, json, StandardCharsets.UTF_8);
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

    private static Map<String, String> parseJson(String json) {
        var map = new Object2ObjectOpenHashMap<String, String>();
        if (json == null || json.isBlank()) return map;

        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return map;

        String inner = json.substring(1, json.length() - 1).trim();
        if (inner.isEmpty()) return map;

        for (String pair : inner.split(",")) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;

            int colon = pair.indexOf(':');
            if (colon < 0) continue;

            String key = pair.substring(0, colon).trim();
            String value = pair.substring(colon + 1).trim();

            if (key.startsWith("\"") && key.endsWith("\"")) key = key.substring(1, key.length() - 1);
            if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);

            if (!key.isEmpty() && !value.isEmpty()) map.put(key, value);
        }
        return map;
    }

    private static String toJson(Map<String, String> map) {
        var sb = new StringBuilder();
        sb.append("{\n");
        var entries = new ObjectArrayList<>(map.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            sb.append("  \"")
                    .append(escapeJson(e.getKey()))
                    .append("\": \"")
                    .append(escapeJson(e.getValue()))
                    .append("\"");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}