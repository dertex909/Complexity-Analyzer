package org.complexityanalyzer.analyzer.resource.data;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.IResourceSource;

import java.util.Collections;
import java.util.Map;

public class BaseResourceData {
    private final Item item;
    private final ResourceSourceType sourceType;
    private final double baseFactor;
    private final String details;
    private final String sourceName;
    private final Map<Item, Double> sourceItems;

    private BaseResourceData(Builder builder) {
        this.item = builder.item;
        this.sourceType = builder.sourceType;
        this.baseFactor = builder.baseFactor;
        this.details = builder.details;
        this.sourceName = builder.sourceName;
        this.sourceItems = builder.sourceItems;
    }

    public Item getItem() { return item; }
    public ResourceSourceType getSourceType() { return sourceType; }
    public double getBaseFactor() { return baseFactor; }
    public String getDetails() { return details; }
    public String getSourceName() { return sourceName; }
    public Map<Item, Double> getSourceItems() { return sourceItems; }

    @Override
    public String toString() {
        return String.format("BaseResource{item=%s, source=%s, factor=%.2f}", item, sourceType, baseFactor);
    }

    public enum ResourceSourceType {
        ORE("Ore/Mining", 1.0),
        EMPIRICAL_BLOCK("Empirical Block", 1.0),
        BLOCK("Block", 1.0),

        RENEWABLE("Renewable", 0.8),
        SHEARING("Shearing", 0.7),
        FISHING("Fishing", 1.2),

        BLOCK_DROP("Block Drop", 1.5),
        MOB_DROP("Mob Drop", 1.8),
        VILLAGER_TRADE("Villager Trade", 2.0),
        PIGLIN_BARTERING("Piglin Bartering", 2.2),
        CHEST_LOOT("Chest Loot", 3.0),
        ARCHAEOLOGY("Archaeology", 5.0),

        GENERIC_LOOT("Generic Loot", 3.0),
        UNKNOWN("Unknown", 10.0),
        UNOBTAINABLE("Unobtainable", Double.POSITIVE_INFINITY);

        private final String displayName;
        private final double baseMultiplier;
        ResourceSourceType(String displayName, double baseMultiplier) {
            this.displayName = displayName;
            this.baseMultiplier = baseMultiplier;
        }
        public String getDisplayName() { return displayName; }
        public double getBaseMultiplier() { return baseMultiplier; }
    }

    public static class Builder {
        private final Item item;
        private final String sourceName;
        private ResourceSourceType sourceType = ResourceSourceType.UNKNOWN;
        private double baseFactor = 1.0;
        private String details = "";
        private Map<Item, Double> sourceItems = Collections.emptyMap();

        public Builder(Item item, IResourceSource source) {
            this.item = item;
            this.sourceName = source.getName();
        }

        public Builder(Item item) {
            this.item = item;
            this.sourceName = "System";
        }

        public Builder sourceType(ResourceSourceType type) { this.sourceType = type; return this; }
        public Builder baseFactor(double factor) { this.baseFactor = factor; return this; }
        public Builder details(String details) { this.details = details; return this; }
        public Builder sourceItems(Map<Item, Double> items) { this.sourceItems = (items == null || items.isEmpty()) ? Collections.emptyMap() : Map.copyOf(items);return this; }
        public BaseResourceData build() { return new BaseResourceData(this); }
    }
}