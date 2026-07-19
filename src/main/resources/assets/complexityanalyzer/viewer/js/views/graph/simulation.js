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

import * as d3 from "../../core/libs/d3.js";
import {GRAPH_CONFIG} from "./constants.js";

export function createSimulation(nodes, edges) {
    return d3.forceSimulation(nodes)
        .force("link", d3.forceLink(edges)
            .distance(GRAPH_CONFIG.FORCE_LINK_DISTANCE)
            .strength(GRAPH_CONFIG.FORCE_LINK_STRENGTH))
        .force("charge", d3.forceManyBody()
            .strength(GRAPH_CONFIG.FORCE_CHARGE_STRENGTH)
            .distanceMax(GRAPH_CONFIG.FORCE_CHARGE_MAX_DISTANCE))
        .force("collide", d3.forceCollide(d => d.radius * GRAPH_CONFIG.NODE_COLLISION_MULTIPLIER + GRAPH_CONFIG.NODE_COLLISION_OFFSET)
            .iterations(GRAPH_CONFIG.FORCE_COLLIDE_ITERATIONS))
        .force("center", d3.forceCenter(0, 0)
            .strength(GRAPH_CONFIG.FORCE_CENTER_STRENGTH));
}

export async function runSimulation(simulation, nodes, updateProgress) {
    const tickCount = nodes.length > GRAPH_CONFIG.TICK_LARGE_THRESHOLD
        ? GRAPH_CONFIG.TICK_COUNT_LARGE
        : GRAPH_CONFIG.TICK_COUNT_SMALL;

    let ticksRun = 0;

    return new Promise((resolve) => {
        const doTicks = () => {
            for (let i = 0; i < GRAPH_CONFIG.TICKS_PER_FRAME && ticksRun < tickCount; i++) {
                simulation.tick();
                ticksRun++;
            }

            const progress = ticksRun / tickCount;
            if (updateProgress) updateProgress(progress);

            if (ticksRun < tickCount) {
                requestAnimationFrame(doTicks);
            } else {
                simulation.stop();
                resolve();
            }
        };

        doTicks();
    });
}
