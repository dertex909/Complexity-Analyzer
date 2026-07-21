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

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.resource.data.BaseResourceData;

/**
 * Registry for manually defined acquisition sources and value overrides. Use it when the automatic analysis
 * cannot see how an item is obtained, or when you want to force a specific cost.
 *
 * <p>There are two tiers:
 * <ul>
 *   <li><b>Normal sources</b> ({@link #registerTransformation}, {@link #registerComplexSource}) participate
 *       alongside discovered sources — the cheapest path still wins.</li>
 *   <li><b>Overrides</b> ({@link #registerOverride}, {@link #registerUnobtainable}) take precedence over
 *       everything else for that item, regardless of cheaper discovered paths.</li>
 * </ul>
 *
 * <p>The calling mod id is captured automatically for attribution/logging. Register during
 * {@link org.complexityanalyzer.api.event.ComplexityRegistrationEvent}.
 */
public interface IHardcodedSourceRegistry {

    /**
     * Registers a simple one-input transformation (e.g. a custom processing step producing {@code result}
     * from {@code input}).
     *
     * @param result      the produced item
     * @param input       the single consumed item (counted as 1 per output)
     * @param toolWear    optional per-output tool/catalyst wear amounts ({@code null} for none)
     * @param baseCost    flat cost added on top of the ingredient costs
     * @param description human-readable explanation shown in tooltips/details
     */
    void registerTransformation(Item result, Item input, Reference2DoubleMap<Item> toolWear, double baseCost,
                                String description);

    /**
     * Registers a multi-ingredient source with an explicit source type.
     *
     * @param result      the produced item
     * @param ingredients consumed items mapped to their per-output amounts
     * @param baseCost    flat cost added on top of the ingredient costs
     * @param type        the {@link BaseResourceData.ResourceSourceType} to classify this source as
     * @param description human-readable explanation
     */
    void registerComplexSource(Item result, Reference2DoubleMap<Item> ingredients, double baseCost,
                               BaseResourceData.ResourceSourceType type, String description);

    /**
     * Registers a forced override that wins over all discovered sources for {@code result}.
     *
     * @param result      the item whose cost is being overridden
     * @param ingredients consumed items mapped to their per-output amounts
     * @param baseCost    flat cost added on top of the ingredient costs
     * @param description human-readable explanation
     */
    void registerOverride(Item result, Reference2DoubleMap<Item> ingredients, double baseCost, String description);

    /**
     * Marks an item as unobtainable (infinite complexity), overriding any discovered source — e.g. creative-only
     * or debug items that should never be priced as craftable.
     *
     * @param item   the unobtainable item
     * @param reason human-readable reason shown in details
     */
    void registerUnobtainable(Item item, String reason);

    /**
     * @return {@code true} if a normal source or override has been registered for the item.
     */
    boolean isRegistered(Item item);
}