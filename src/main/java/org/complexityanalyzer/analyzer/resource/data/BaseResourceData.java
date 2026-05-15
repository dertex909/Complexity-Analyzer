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

package org.complexityanalyzer.analyzer.resource.data;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.IResourceSource;

import java.util.Map;

public class BaseResourceData {
    private final Item item;
    private final ResourceSourceType sourceType;
    private final double baseFactor;
    private final String details;
    private final String sourceName;
    private final Reference2DoubleMap<Item> sourceItems;
    private final Object2ObjectMap<String, String> metadata;
    private final String sourceSpecifier;

    private BaseResourceData(Builder builder) {
        this.item = builder.item;
        this.sourceType = builder.sourceType;
        this.baseFactor = builder.baseFactor;
        this.details = builder.details;
        this.sourceName = builder.sourceName;
        this.sourceItems = Reference2DoubleMaps.unmodifiable(new Reference2DoubleOpenHashMap<>(builder.sourceItems));
        this.metadata = Object2ObjectMaps.unmodifiable(new Object2ObjectOpenHashMap<>(builder.metadata));
        this.sourceSpecifier = builder.sourceSpecifier;
    }

    public Item getItem() {
        return item;
    }

    public ResourceSourceType getSourceType() {
        return sourceType;
    }

    public double getBaseFactor() {
        return baseFactor;
    }

    public String getDetails() {
        return details;
    }

    public String getSourceName() {
        return sourceName;
    }

    public Reference2DoubleMap<Item> getSourceItems() {
        return sourceItems;
    }

    public Object2ObjectMap<String, String> getMetadata() {
        return metadata;
    }

    public String getSourceSpecifier() {
        return sourceSpecifier;
    }

    public boolean isOverride() {
        return "true".equals(metadata.get("override"));
    }

    public String getOverrideModId() {
        return metadata.getOrDefault("override_by", "unknown");
    }

    @Override
    public String toString() {
        String base = String.format("BaseResource{item=%s, source=%s, factor=%.2f}", item, sourceType, baseFactor);
        if (isOverride()) return base + " [OVERRIDE by " + getOverrideModId() + "]";
        return base;
    }

    public enum ResourceSourceType {
        OVERRIDE("complexityanalyzer.source_type.override", 0.0),
        ORE("complexityanalyzer.source_type.ore", 1.0),
        EMPIRICAL_BLOCK("complexityanalyzer.source_type.empirical_block", 1.0),
        BLOCK("complexityanalyzer.source_type.block", 1.0),
        BLOCK_TRANSFORMATION("complexityanalyzer.source_type.block_transformation", 1.0),
        FARMING("complexityanalyzer.source_type.farming", 0.8),
        CRAFTING("complexityanalyzer.source_type.crafting", 1.0),
        RENEWABLE("complexityanalyzer.source_type.renewable", 0.8),
        SHEARING("complexityanalyzer.source_type.shearing", 0.7),
        FISHING("complexityanalyzer.source_type.fishing", 1.2),
        MOB_DROP("complexityanalyzer.source_type.mob_drop", 1.8),
        VILLAGER_TRADE("complexityanalyzer.source_type.villager_trade", 2.0),
        PIGLIN_BARTERING("complexityanalyzer.source_type.piglin_bartering", 2.2),
        CHEST_LOOT("complexityanalyzer.source_type.chest_loot", 3.0),
        ARCHAEOLOGY("complexityanalyzer.source_type.archaeology", 5.0),
        SPECIAL_LOOT("complexityanalyzer.source_type.special_loot", 4.0),
        SPECIAL_ACTION("complexityanalyzer.source_type.special_action", 2.5),
        GENERIC_LOOT("complexityanalyzer.source_type.generic_loot", 3.0),
        UNKNOWN("complexityanalyzer.source_type.unknown", 10.0),
        UNOBTAINABLE("complexityanalyzer.source_type.unobtainable", Double.POSITIVE_INFINITY);

        private final String translationKey;
        private final double baseMultiplier;

        ResourceSourceType(String translationKey, double baseMultiplier) {
            this.translationKey = translationKey;
            this.baseMultiplier = baseMultiplier;
        }

        public String getDisplayName() {
            return translationKey;
        }

        public double getBaseMultiplier() {
            return baseMultiplier;
        }
    }

    public static class Builder {
        private final Item item;
        private final String sourceName;
        private ResourceSourceType sourceType = ResourceSourceType.UNKNOWN;
        private double baseFactor = 1.0;
        private String details = "";
        private String sourceSpecifier = "";
        private final Reference2DoubleMap<Item> sourceItems = new Reference2DoubleOpenHashMap<>();
        private final Object2ObjectMap<String, String> metadata = new Object2ObjectOpenHashMap<>();

        public Builder(Item item, IResourceSource source) {
            this.item = item;
            this.sourceName = source.getName();
        }

        public Builder(Item item) {
            this.item = item;
            this.sourceName = "System";
        }

        public Builder sourceType(ResourceSourceType type) {
            this.sourceType = type;
            return this;
        }

        public Builder baseFactor(double factor) {
            this.baseFactor = factor;
            return this;
        }

        public Builder details(String details) {
            this.details = details;
            return this;
        }

        public Builder sourceItems(Map<Item, Double> items) {
            if (items != null) this.sourceItems.putAll(items);
            return this;
        }

        public Builder addSourceItem(Item item, double amount) {
            this.sourceItems.put(item, amount);
            return this;
        }

        public Builder addMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, String> meta) {
            if (meta != null) this.metadata.putAll(meta);
            return this;
        }

        public BaseResourceData build() {
            return new BaseResourceData(this);
        }

        public Builder sourceSpecifier(String specifier) {
            this.sourceSpecifier = specifier;
            return this;
        }
    }
}