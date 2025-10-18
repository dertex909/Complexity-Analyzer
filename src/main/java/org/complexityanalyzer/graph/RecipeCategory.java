package org.complexityanalyzer.graph;

public enum RecipeCategory {
    PRIMARY(1000, "Primary"),
    STORAGE_COMPRESSION(100, "Compression"),
    RECYCLING(20, "Recycling"),
    STORAGE_DECOMPRESSION(10, "Decompression"),
    RECOLORING(5, "Recoloring"),
    UNPROCESSABLE(0, "Unprocessable");

    private final int priority;
    private final String displayName;

    RecipeCategory(int priority, String displayName) {
        this.priority = priority;
        this.displayName = displayName;
    }

    public int getPriority() { return priority; }
    public String getDisplayName() { return displayName; }
}