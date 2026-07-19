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

import {GRAPH_CONFIG} from "./constants.js";

const categoryColors = new Map();

export function getCategoryColor(category) {
    if (!category) return GRAPH_CONFIG.DEFAULT_COLOR;
    if (categoryColors.has(category)) return categoryColors.get(category);

    const cssVarName = `--cat-${category.replace(/\s+/g, "_")}`;
    let color = getComputedStyle(document.documentElement).getPropertyValue(cssVarName).trim();
    if (!color) color = getComputedStyle(document.documentElement).getPropertyValue(`--cat-${category}`).trim();
    if (!color) color = GRAPH_CONFIG.DEFAULT_COLOR;

    categoryColors.set(category, color);
    return color;
}

export function transformCoords(clientX, clientY, canvas, pan, zoom) {
    const bounds = canvas.getBoundingClientRect();
    const mX = clientX - bounds.left;
    const mY = clientY - bounds.top;
    return {x: (mX - pan.x) / zoom, y: (mY - pan.y) / zoom};
}

export function isNeighbor(n1, n2, neighborMap) {
    if (!n1 || !n2) return false;
    const neighbors = neighborMap.get(n1.id);
    return neighbors && neighbors.has(n2.id);
}

export function calculateGraphBounds(nodes) {
    if (nodes.length === 0) return {minX: 0, maxX: 0, minY: 0, maxY: 0, width: 0, height: 0};

    let minX = Infinity, maxX = -Infinity;
    let minY = Infinity, maxY = -Infinity;

    for (const n of nodes) {
        if (n.x < minX) minX = n.x;
        if (n.x > maxX) maxX = n.x;
        if (n.y < minY) minY = n.y;
        if (n.y > maxY) maxY = n.y;
    }

    return {minX, maxX, minY, maxY, width: maxX - minX, height: maxY - minY};
}

export function calculateFitView(nodes, canvasWidth, canvasHeight) {
    const bounds = calculateGraphBounds(nodes);
    const pad = GRAPH_CONFIG.VIEWPORT_PAD;
    const targetW = canvasWidth - pad * 2;
    const targetH = canvasHeight - pad * 2;

    const zoom = Math.max(
        GRAPH_CONFIG.INITIAL_ZOOM_MIN,
        Math.min(
            GRAPH_CONFIG.INITIAL_ZOOM_MAX,
            Math.min(targetW / (bounds.width || 1), targetH / (bounds.height || 1))
        )
    );

    const pan = {
        x: (canvasWidth - bounds.width * zoom) / 2 - bounds.minX * zoom,
        y: (canvasHeight - bounds.height * zoom) / 2 - bounds.minY * zoom
    };

    return {zoom, pan};
}

export function findNodeAtPosition(nodes, graphX, graphY, zoom, maxRadius = null) {
    let found = null;
    let bestDist = maxRadius !== null ? maxRadius : (GRAPH_CONFIG.HOVER_RADIUS / zoom);

    for (const n of nodes) {
        const dst = Math.hypot(n.x - graphX, n.y - graphY);
        const limit = Math.max(n.radius, bestDist);

        if (dst < limit && dst < bestDist) {
            found = n;
            bestDist = dst;
        }
    }

    return found;
}
