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
import java.util.function.Function;

/**
 * Universal registry for custom acquisition sources, dynamic item pricing logic, and manual overrides.
 *
 * <p>Unlike rigid recipe registrations, this interface allows arbitrary acquisition logic to participate
 * directly in the analysis pipeline without subclassing or bytecode manipulation.
 *
 * <p><b>Registration styles supported:</b>
 * <ul>
 *   <li><b>Dynamic resolvers:</b> {@link #register(Function)} lets you evaluate items on the fly using
 *       arbitrary predicates, dynamic math, tags, or mod integrations.</li>
 *   <li><b>Direct bindings:</b> {@link #register(Item, BaseResourceData)} pairs a specific {@link Item}
 *       with precomputed resource metadata.</li>
 *   <li><b>World scanners:</b> {@link #register(BiConsumer)} schedules a scanning routine executed during
 *       pipeline initialization with full access to the server's {@link Level} and registries (e.g. block
 *       transformations, coral drying, fluid interactions).</li>
 * </ul>
 *
 * <p><b>Addon usage:</b> Subscribe to
 * {@link org.complexityanalyzer.api.event.ComplexityRegistrationEvent} (fired on {@code NeoForge.EVENT_BUS}
 * before each build pass) and contribute via {@link org.complexityanalyzer.api.event.ComplexityRegistrationEvent#hardcodedSources()}:
 * <pre>{@code
 * @SubscribeEvent
 * public static void onRegister(ComplexityRegistrationEvent event) {
 *     // Register an on-the-fly resolver
 *     event.hardcodedSources().register(item -> {
 *         if (item.toString().contains("magic_essence")) {
 *             return new BaseResourceData.Builder(item)
 *                     .baseFactor(25.0)
 *                     .details("Harvested via mana siphon")
 *                     .build();
 *         }
 *         return null;
 *     });
 *
 *     // Register a batch scanner that reads the world
 *     event.hardcodedSources().register((level, registry) -> {
 *         // Custom world scanning logic here
 *     });
 * }
 * }</pre>
 */
public interface IHardcodedSourceRegistry {

    /**
     * Registers a dynamic resolution function invoked on-demand when the engine queries an item's base cost.
     *
     * <p>The function should inspect the provided item and return a populated {@link BaseResourceData},
     * or {@code null} if the item is not handled by this resolver.
     *
     * @param resolver functional resolver mapping an {@link Item} to its {@link BaseResourceData} (or {@code null})
     */
    void register(Function<Item, BaseResourceData> resolver);

    /**
     * Directly associates an item with a predefined {@link BaseResourceData} instance.
     *
     * <p>Useful for static item prices, custom drops, or overrides where no complex calculation is required.
     *
     * @param item the target item
     * @param data the acquisition metadata for this item
     */
    void register(Item item, BaseResourceData data);

    /**
     * Registers a deferred world-aware initializer called once per engine analysis pass (at server start
     * and on every reload).
     *
     * <p>Use this for heavy operations such as scanning {@link net.minecraft.core.registries.BuiltInRegistries#BLOCK},
     * inspecting biome structures, or registering transformations derived from game world state.
     *
     * @param initializer consumer receiving the active server {@link Level} and this {@link IHardcodedSourceRegistry}
     */
    void register(BiConsumer<Level, IHardcodedSourceRegistry> initializer);

    /**
     * Checks whether an acquisition source or dynamic provider is available for the given item.
     *
     * @param item the item to test
     * @return {@code true} if this registry can supply data for {@code item}
     */
    boolean isRegistered(Item item);
}