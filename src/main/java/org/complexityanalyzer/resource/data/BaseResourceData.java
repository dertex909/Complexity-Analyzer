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

package org.complexityanalyzer.resource.data;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.resource.IResourceSource;

import java.util.Map;

/**
 * Immutable description of one raw (non-crafted) way to obtain an item, produced by an
 * {@link IResourceSource}. It pairs a base cost ({@link #getBaseFactor()}) with the items consumed to realize
 * that path ({@link #getSourceItems()}, e.g. tool wear or trade inputs) plus human-readable details and
 * free-form metadata.
 *
 * <p>Instances are built via {@link Builder} and are safe to share across threads. The collections returned by
 * {@link #getSourceItems()} and {@link #getMetadata()} are unmodifiable.
 */
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

    /**
     * @return the item this acquisition path produces.
     */
    public Item getItem() {
        return item;
    }

    /**
     * @return how this path is classified (mining, loot, mob drop, …).
     */
    public ResourceSourceType getSourceType() {
        return sourceType;
    }

    /**
     * @return the base cost of obtaining one unit via this path; lower is cheaper, {@code Infinity} = unobtainable.
     */
    public double getBaseFactor() {
        return baseFactor;
    }

    /**
     * @return human-readable explanation of the path (shown in tooltips/details).
     */
    public String getDetails() {
        return details;
    }

    /**
     * @return the name of the {@link IResourceSource} that produced this data.
     */
    public String getSourceName() {
        return sourceName;
    }

    /**
     * @return items consumed per produced unit (e.g. tool durability, trade inputs); unmodifiable, may be empty.
     */
    public Reference2DoubleMap<Item> getSourceItems() {
        return sourceItems;
    }

    /**
     * @return free-form key/value metadata attached by the source; unmodifiable.
     */
    public Object2ObjectMap<String, String> getMetadata() {
        return metadata;
    }

    /**
     * @return a source-specific qualifier (e.g. the block mined, the loot table id) for display.
     */
    public String getSourceSpecifier() {
        return sourceSpecifier;
    }

    /**
     * @return {@code true} if this data was registered as a forced override by a mod.
     */
    public boolean isOverride() {
        return "true".equals(metadata.get("override"));
    }

    /**
     * @return the mod id that registered the override, or {@code "unknown"}.
     */
    public String getOverrideModId() {
        return metadata.getOrDefault("override_by", "unknown");
    }

    @Override
    public String toString() {
        String base = String.format("BaseResource{item=%s, source=%s, factor=%.2f}", item, sourceType, baseFactor);
        if (isOverride()) return base + " [OVERRIDE by " + getOverrideModId() + "]";
        return base;
    }

    /**
     * Classification of a raw acquisition path. Each constant carries a translation key for display and a
     * {@code baseMultiplier} that scales the path's cost, reflecting how "expensive" that method is conceptually
     * (e.g. a villager trade weighs more than mining the same item).
     */
    public enum ResourceSourceType {
        OVERRIDE("complexityanalyzer.source_type.override", 0.0, "⚡", ChatFormatting.RED),
        ORE("complexityanalyzer.source_type.ore", 1.0, "⛏", ChatFormatting.DARK_GRAY),
        EMPIRICAL_BLOCK("complexityanalyzer.source_type.empirical_block", 1.0, "🧱", ChatFormatting.GRAY),
        BLOCK("complexityanalyzer.source_type.block", 1.0, "🧱", ChatFormatting.GRAY),
        BLOCK_TRANSFORMATION("complexityanalyzer.source_type.block_transformation", 1.0, "🔄", ChatFormatting.BLUE),
        FARMING("complexityanalyzer.source_type.farming", 0.8, "🌾", ChatFormatting.GREEN),
        CRAFTING("complexityanalyzer.source_type.crafting", 1.0, "⚒", ChatFormatting.YELLOW),
        RENEWABLE("complexityanalyzer.source_type.renewable", 0.8, "🌱", ChatFormatting.DARK_GREEN),
        SHEARING("complexityanalyzer.source_type.shearing", 0.7, "✂", ChatFormatting.GREEN),
        FISHING("complexityanalyzer.source_type.fishing", 1.2, "🎣", ChatFormatting.AQUA),
        MOB_DROP("complexityanalyzer.source_type.mob_drop", 1.8, "⚔", ChatFormatting.RED),
        VILLAGER_TRADE("complexityanalyzer.source_type.villager_trade", 2.0, "📜", ChatFormatting.GREEN),
        PIGLIN_BARTERING("complexityanalyzer.source_type.piglin_bartering", 2.2, "🐷", ChatFormatting.YELLOW),
        CHEST_LOOT("complexityanalyzer.source_type.chest_loot", 3.0, "📦", ChatFormatting.GOLD),
        ARCHAEOLOGY("complexityanalyzer.source_type.archaeology", 5.0, "🏺", ChatFormatting.LIGHT_PURPLE),
        SPECIAL_LOOT("complexityanalyzer.source_type.special_loot", 4.0, "🎁", ChatFormatting.GOLD),
        SPECIAL_ACTION("complexityanalyzer.source_type.special_action", 2.5, "✨", ChatFormatting.LIGHT_PURPLE),
        GENERIC_LOOT("complexityanalyzer.source_type.generic_loot", 3.0, "🎁", ChatFormatting.WHITE),
        UNKNOWN("complexityanalyzer.source_type.unknown", 10.0, "❓", ChatFormatting.GRAY),
        UNOBTAINABLE("complexityanalyzer.source_type.unobtainable", Double.POSITIVE_INFINITY, "🚫", ChatFormatting.DARK_RED);

        private final String translationKey;
        private final double baseMultiplier;
        private final String icon;
        private final ChatFormatting color;

        ResourceSourceType(String translationKey, double baseMultiplier, String icon, ChatFormatting color) {
            this.translationKey = translationKey;
            this.baseMultiplier = baseMultiplier;
            this.icon = icon;
            this.color = color;
        }

        /**
         * @return the translation key for this type's display name.
         */
        public String getDisplayName() {
            return translationKey;
        }

        /**
         * @return the cost multiplier applied to paths of this type.
         */
        public double getBaseMultiplier() {
            return baseMultiplier;
        }

        /**
         * @return the icon emoji associated with this source type.
         */
        public String getIcon() {
            return icon;
        }

        /**
         * @return the chat formatting color for this source type.
         */
        public ChatFormatting getColor() {
            return color;
        }
    }

    public enum ResourceDifficultyTier {
        EXTREME_HARD(20.0, "complexityanalyzer.command.resource.difficulty.extreme_hard", ChatFormatting.DARK_RED),
        VERY_HARD(15.0, "complexityanalyzer.command.resource.difficulty.very_hard", ChatFormatting.RED),
        HARD(10.0, "complexityanalyzer.command.resource.difficulty.hard", ChatFormatting.GOLD),
        MODERATE(5.0, "complexityanalyzer.command.resource.difficulty.moderate", ChatFormatting.YELLOW),
        EASY(2.0, "complexityanalyzer.command.resource.difficulty.easy", ChatFormatting.GREEN),
        VERY_EASY(0.0, "complexityanalyzer.command.resource.difficulty.very_easy", ChatFormatting.GREEN);

        public final double minFactor;
        public final String translationKey;
        public final ChatFormatting color;

        ResourceDifficultyTier(double minFactor, String translationKey, ChatFormatting color) {
            this.minFactor = minFactor;
            this.translationKey = translationKey;
            this.color = color;
        }

        public static ResourceDifficultyTier fromFactor(double factor) {
            for (var tier : values()) if (factor >= tier.minFactor) return tier;
            return VERY_EASY;
        }
    }

    /**
     * Fluent builder for {@link BaseResourceData}. Custom {@link IResourceSource}s use it to emit their results.
     */
    public static class Builder {
        private final Item item;
        private final String sourceName;
        private final Reference2DoubleMap<Item> sourceItems = new Reference2DoubleOpenHashMap<>();
        private final Object2ObjectMap<String, String> metadata = new Object2ObjectOpenHashMap<>();
        private ResourceSourceType sourceType = ResourceSourceType.UNKNOWN;
        private double baseFactor = 1.0;
        private String details = "";
        private String sourceSpecifier = "";

        /**
         * @param item   the produced item
         * @param source the source emitting this data (its name is recorded for attribution)
         */
        public Builder(Item item, IResourceSource source) {
            this.item = item;
            this.sourceName = source.getName();
        }

        /**
         * Creates a builder attributed to the system rather than a specific source.
         *
         * @param item the produced item
         */
        public Builder(Item item) {
            this.item = item;
            this.sourceName = "System";
        }

        /**
         * Sets the classification of this path.
         */
        public Builder sourceType(ResourceSourceType type) {
            this.sourceType = type;
            return this;
        }

        /**
         * Sets the base cost per produced unit.
         */
        public Builder baseFactor(double factor) {
            this.baseFactor = factor;
            return this;
        }

        /**
         * Sets the human-readable details string.
         */
        public Builder details(String details) {
            this.details = details;
            return this;
        }

        /**
         * Replaces the consumed-items map (per produced unit). {@code null} is ignored.
         */
        public Builder sourceItems(Map<Item, Double> items) {
            if (items != null) this.sourceItems.putAll(items);
            return this;
        }

        /**
         * Adds a single consumed item with the given per-unit amount.
         */
        public Builder addSourceItem(Item item, double amount) {
            this.sourceItems.put(item, amount);
            return this;
        }

        /**
         * Adds one metadata key/value pair.
         */
        public Builder addMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Merges the given metadata map. {@code null} is ignored.
         */
        public Builder metadata(Map<String, String> meta) {
            if (meta != null) this.metadata.putAll(meta);
            return this;
        }

        /**
         * @return the finished, immutable resource data.
         */
        public BaseResourceData build() {
            return new BaseResourceData(this);
        }

        /**
         * Sets the source-specific qualifier (e.g. block or loot-table id).
         */
        public Builder sourceSpecifier(String specifier) {
            this.sourceSpecifier = specifier;
            return this;
        }
    }
}