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

export class GraphCache {
    constructor() {
        this.cachedHash = null;
        this.cachedNodes = null;
        this.cachedEdges = null;
        this.cachedNeighborMap = null;
    }

    get(cacheKey) {
        if (this.cachedHash === cacheKey && this.cachedNodes && this.cachedEdges) return {
            nodes: this.cachedNodes,
            edges: this.cachedEdges,
            neighborMap: this.cachedNeighborMap,
            isCached: true
        };
        return null;
    }

    set(cacheKey, nodes, edges, neighborMap) {
        this.cachedHash = cacheKey;
        this.cachedNodes = nodes;
        this.cachedEdges = edges;
        this.cachedNeighborMap = neighborMap;
    }

    clear() {
        this.cachedHash = null;
        this.cachedNodes = null;
        this.cachedEdges = null;
        this.cachedNeighborMap = null;
    }
}
