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
import net.minecraft.world.level.Level;
import org.complexityanalyzer.resource.data.BaseResourceData;

import java.util.function.BiConsumer;

/**
 * Universal registry for custom acquisition sources, dynamic item pricing logic, and manual overrides.
 *
 * <p>Unlike rigid recipe registrations, this interface allows arbitrary acquisition logic to participate
 * directly in the analysis pipeline without subclassing or bytecode manipulation.
 *
 * <p><b>Registration styles supported:</b>
 * <ul>
 *   <li><b>Direct bindings:</b> {@link #register(Item, BaseResourceData)} pairs a specific {@link Item}
 *       with precomputed resource metadata.</li>
 *   <li><b>World scanners:</b> {@link #register(BiConsumer)} schedules a scanning routine executed during
 *       pipeline initialization with full access to the server's {@link Level} and registries (e.g. block
 *       transformations, coral drying, fluid interactions).</li>
 * </ul>
 */
public interface IHardcodedSourceRegistry {

    /**
     * Directly associates an item with a predefined {@link BaseResourceData} instance.
     *
     * @param item the target item
     * @param data the acquisition metadata for this item
     */
    void register(Item item, BaseResourceData data);

    /**
     * Registers a deferred world-aware initializer called once per engine analysis pass (at server start
     * and on every reload).
     *
     * @param initializer consumer receiving the active server {@link Level} and this {@link IHardcodedSourceRegistry}
     */
    void register(BiConsumer<Level, IHardcodedSourceRegistry> initializer);

    /**
     * Checks whether an acquisition source is available for the given item.
     *
     * @param item the item to test
     * @return {@code true} if this registry can supply data for {@code item}
     */
    boolean isRegistered(Item item);
}