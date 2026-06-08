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

package org.complexityanalyzer.api.event;

import net.neoforged.bus.api.Event;
import org.complexityanalyzer.api.ComplexityAnalyzerAPI;

/**
 * Fired on the NeoForge game bus ({@code NeoForge.EVENT_BUS}) right after an analysis pass reaches the READY
 * state and all data is queryable. Use it to read final complexities, build your own caches, or refresh
 * dependent systems.
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void onReady(ComplexityAnalysisCompleteEvent e) {
 *     var api = e.api();
 *     double diamond = api.items().getComplexity(Items.DIAMOND);
 * }
 * }</pre>
 *
 * <p>Use {@link #isReload()} to tell the initial server-start build ({@code false}) from later rebuilds
 * ({@code true}).
 */
public class ComplexityAnalysisCompleteEvent extends Event {

    private final ComplexityAnalyzerAPI api;
    private final boolean reload;

    public ComplexityAnalysisCompleteEvent(ComplexityAnalyzerAPI api, boolean reload) {
        this.api = api;
        this.reload = reload;
    }

    /**
     * @return the ready API handle (equivalent to {@link ComplexityAnalyzerAPI#get()}).
     */
    public ComplexityAnalyzerAPI api() {
        return api;
    }

    /**
     * @return {@code true} if this was a rebuild rather than the first build.
     */
    public boolean isReload() {
        return reload;
    }
}