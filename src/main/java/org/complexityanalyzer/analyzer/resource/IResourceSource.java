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

package org.complexityanalyzer.analyzer.resource;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import org.jetbrains.annotations.Nullable;

/**
 * A provider of raw (non-crafted) acquisition paths for items — e.g. mining a block, killing a mob, fishing,
 * a villager trade, or a custom gathering mechanic. The analyzer queries every source for an item and keeps the
 * cheapest path; sources thus define the "leaves" of the complexity graph that crafting recipes build upon.
 *
 * <p>Lifecycle: {@link #initialize(Level)} runs once per analysis build (heavy scanning belongs here, with
 * results cached in the instance); afterwards {@link #canProvide(Item)} and {@link #analyze(Item)} are called
 * to resolve costs. All built-in sources follow this contract, and addon sources registered via
 * {@link org.complexityanalyzer.api.IResourceSourceRegistry} are treated identically.
 */
public interface IResourceSource {

    /**
     * Performs the source's one-time scan/setup for this analysis build. Called on a background thread unless
     * {@link #requiresServerThread()} is {@code true}. Store results in the instance for later queries.
     *
     * @param level the server level being analyzed
     */
    void initialize(Level level);

    /**
     * @return {@code true} if this source can supply the given item (cheap check used before {@link #analyze}).
     */
    boolean canProvide(Item item);

    /**
     * Computes the cheapest acquisition data this source offers for the item.
     *
     * @param item the item to price
     * @return the resource data, or {@code null} if this source does not provide the item
     */
    @Nullable
    BaseResourceData analyze(Item item);

    /**
     * @return the category this source classifies its results as (used for display and base multipliers).
     */
    BaseResourceData.ResourceSourceType getSourceType();

    /**
     * @return tie-break priority when multiple sources yield the same cost; higher wins. Defaults to 0.
     */
    default int getPriority() {
        return 0;
    }

    /**
     * @return {@code true} if {@link #initialize} must run on the main server thread (e.g. it reads world state
     * or runs loot tables that touch chunks). Defaults to {@code false} so it can run concurrently.
     */
    default boolean requiresServerThread() {
        return false;
    }

    /**
     * @return a short human-readable name for logging and source attribution.
     */
    String getName();
}
