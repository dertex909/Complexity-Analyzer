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

package org.complexityanalyzer.api;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;

import java.util.Collection;
import java.util.Optional;

/**
 * Read-only access to computed item complexities. All methods are safe to call from any thread once the engine
 * is {@link ComplexityAnalyzerAPI#isReady() ready}; before that they return empty/zero defaults.
 */
public interface ComplexityQuery {

    /**
     * @return the complexity score of the item, or {@code -1} if it is not analyzed / uncalculable.
     * Higher means harder to obtain. {@code Infinity} means unobtainable.
     */
    double getComplexity(Item item);

    /**
     * Convenience overload operating on a stack's item.
     */
    double getComplexity(ItemStack stack);

    /**
     * @return the bucketed difficulty tier for the item (never {@code null}; {@code UNCALCULABLE} if unknown).
     */
    ComplexityCategory getCategory(Item item);

    /**
     * @return the full computed record for the item (score, depth, optimal recipe, flags), if analyzed.
     */
    Optional<ItemComplexity> getDetailed(Item item);

    /**
     * @return {@code true} if the item has a computed, valid complexity.
     */
    boolean isAnalyzed(Item item);

    /**
     * @return an immutable view of every item that currently has a computed complexity.
     */
    Collection<Item> getAnalyzedItems();

    /**
     * @return number of analyzed items.
     */
    int count();
}