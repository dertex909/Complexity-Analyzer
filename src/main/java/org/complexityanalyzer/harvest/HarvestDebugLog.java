package org.complexityanalyzer.harvest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class HarvestDebugLog {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();
    private static final ObjectArrayList<Entry> BUFFER = new ObjectArrayList<>();
    private static final int MAX_ENTRIES = 200;

    public static void beginRecipe(String recipeId, String recipeClass) {
        var e = new Entry();
        e.recipeId = recipeId;
        e.recipeClass = recipeClass;
        e.timestamp = Instant.now().toString();
        CURRENT.set(e);
    }

    public static void logAccessor(String category, String type, String name, String declaredClass) {
        var e = CURRENT.get();
        if (e == null) return;
        e.accessors.computeIfAbsent(category, k -> new ArrayList<>())
                .add(new AccessorInfo(type, name, declaredClass));
    }

    public static void logExtractResult(String category, String accessorName, Object result) {
        var e = CURRENT.get();
        if (e == null) return;
        String resultClass = result == null ? "null" : result.getClass().getName();
        int size = -1;
        if (result instanceof Iterable<?> coll) {
            int c = 0;
            for (var __it = coll.iterator(); __it.hasNext(); ) { __it.next(); c++; if (c > 100) break; }
            size = c;
        } else if (result instanceof Object[] arr) {
            size = arr.length;
        }
        e.extractResults.computeIfAbsent(category, k -> new ArrayList<>())
                .add(new ExtractResult(accessorName, resultClass, size));
    }

    public static void logTiming(String phase, long nanos) {
        var e = CURRENT.get();
        if (e == null) return;
        e.timing.put(phase, nanos);
    }

    public static void endRecipe(int itemCount, int ingredientCount, int fluidCount, boolean rejected, String rejectedReason) {
        var e = CURRENT.get();
        if (e == null) return;
        e.result = new Result(itemCount, ingredientCount, fluidCount, rejected, rejectedReason);
        synchronized (BUFFER) {
            if (BUFFER.size() < MAX_ENTRIES) {
                BUFFER.add(e);
            }
        }
        CURRENT.remove();
    }

    public static void flush(Path worldDir) {
        if (worldDir == null) return;
        List<Entry> copy;
        synchronized (BUFFER) {
            if (BUFFER.isEmpty()) return;
            copy = new ArrayList<>(BUFFER);
            BUFFER.clear();
        }
        try {
            Files.createDirectories(worldDir);
            Path file = worldDir.resolve("harvest_debug.json");
            Files.writeString(file, GSON.toJson(copy));
            ComplexityAnalyzer.LOGGER.info("[HarvestDebug] Wrote {} entries to {}", copy.size(), file);
        } catch (IOException ex) {
            ComplexityAnalyzer.LOGGER.error("[HarvestDebug] Failed to write debug log", ex);
        }
    }

    static class Entry {
        String recipeId;
        String recipeClass;
        String timestamp;
        java.util.Map<String, List<AccessorInfo>> accessors = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<ExtractResult>> extractResults = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> timing = new java.util.LinkedHashMap<>();
        Result result;
    }

    static class AccessorInfo {
        String type;
        String name;
        String declaredClass;

        AccessorInfo(String type, String name, String declaredClass) {
            this.type = type;
            this.name = name;
            this.declaredClass = declaredClass;
        }
    }

    static class ExtractResult {
        String accessor;
        String resultClass;
        int size;

        ExtractResult(String accessor, String resultClass, int size) {
            this.accessor = accessor;
            this.resultClass = resultClass;
            this.size = size;
        }
    }

    static class Result {
        int itemCount;
        int ingredientCount;
        int fluidCount;
        boolean rejected;
        String rejectedReason;

        Result(int itemCount, int ingredientCount, int fluidCount, boolean rejected, String rejectedReason) {
            this.itemCount = itemCount;
            this.ingredientCount = ingredientCount;
            this.fluidCount = fluidCount;
            this.rejected = rejected;
            this.rejectedReason = rejectedReason;
        }
    }
}
