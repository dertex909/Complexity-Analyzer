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

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Public entry point of the Complexity Analyzer mod for addons and integrations.
 *
 * <p><b>Reading data.</b> Obtain the singleton with {@link #get()} (or {@link #getOptional()} if you are not
 * sure the mod is present/loaded) and use the typed sub-views:
 * <pre>{@code
 * ComplexityAnalyzerAPI api = ComplexityAnalyzerAPI.get();
 * if (api.isReady()) {
 *     double c = api.items().getComplexity(Items.DIAMOND);
 *     api.recipes().getBestRecipe(Items.PISTON).ifPresent(r -> ...);
 *     api.mobs().getInfo(EntityType.GHAST).ifPresent(info -> ...);
 * }
 * }</pre>
 *
 * <p><b>Contributing data.</b> Subscribe to
 * {@link org.complexityanalyzer.api.event.ComplexityRegistrationEvent} (fired on the NeoForge game bus before
 * every analysis build) to register bosses, renewable mobs, hardcoded sources or fully custom resource
 * sources. Subscribe to {@link org.complexityanalyzer.api.event.ComplexityAnalysisCompleteEvent} to react when
 * results are ready.
 *
 * <p>All read methods are thread-safe and return safe defaults while {@link #isReady()} is {@code false}.
 * The instance is available from server start; it never changes for the lifetime of a server.
 */
public interface ComplexityAnalyzerAPI {

    /**
     * Semantic version of this API surface. Bumped on breaking changes.
     */
    String API_VERSION = "1.0.0";

    // ---- Accessors --------------------------------------------------------------------------------------

    /**
     * @return the live API instance.
     * @throws IllegalStateException if accessed before the mod has installed it (i.e. before server start).
     *                               Prefer {@link #getOptional()} from contexts where availability is unsure.
     */
    static ComplexityAnalyzerAPI get() {
        var api = Holder.instance;
        if (api == null) {
            throw new IllegalStateException("ComplexityAnalyzerAPI is not available yet (accessed before server start).");
        }
        return api;
    }

    /**
     * @return the API instance if installed, otherwise empty. Safe to call at any time.
     */
    static Optional<ComplexityAnalyzerAPI> getOptional() {
        return Optional.ofNullable(Holder.instance);
    }

    /**
     * @return {@code true} if the API instance has been installed (mod present and server started).
     */
    static boolean isAvailable() {
        return Holder.instance != null;
    }

    // ---- Lifecycle --------------------------------------------------------------------------------------

    /**
     * @return {@code true} once a full analysis pass has completed and all queries return real data.
     */
    boolean isReady();

    // ---- Read views -------------------------------------------------------------------------------------

    /**
     * Computed item complexities and difficulty categories.
     */
    ComplexityQuery items();

    /**
     * Parsed recipe graph and raw resource sources.
     */
    RecipeData recipes();

    /**
     * Mob spawn rarity and combat difficulty model.
     */
    MobData mobs();

    /**
     * World geo-scan block-composition data.
     */
    GeoData geo();

    /**
     * Machine-per-recipe-type registry.
     */
    MachineData machines();

    // ---- Live registries (also exposed via the registration event) --------------------------------------

    /**
     * Register/query bosses. Changes take effect on the next analysis build.
     */
    IBossRegistry bosses();

    /**
     * Register hardcoded sources / overrides. Changes take effect on the next analysis build.
     */
    IHardcodedSourceRegistry hardcodedSources();

    /**
     * Mark mobs as renewable. Changes take effect on the next analysis build.
     */
    IRenewableRegistry renewables();

    /**
     * Internal holder for the singleton. Set by the Complexity Analyzer core; addons must not call
     * {@link #install}/{@link #uninstall}.
     */
    @ApiStatus.Internal
    final class Holder {
        private static volatile ComplexityAnalyzerAPI instance;

        private Holder() {
        }

        @ApiStatus.Internal
        public static void install(ComplexityAnalyzerAPI api) {
            instance = api;
        }

        @ApiStatus.Internal
        public static void uninstall() {
            instance = null;
        }

        @ApiStatus.Internal
        @Nullable
        public static ComplexityAnalyzerAPI peek() {
            return instance;
        }
    }
}