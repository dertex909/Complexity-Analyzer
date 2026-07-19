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

export class EdgeRouter {
    constructor(nodes) {
        this.nodes = nodes;
        this.categoryGroups = this.buildCategoryGroups();
    }

    buildCategoryGroups() {
        const groups = new Map();
        for (const node of this.nodes) {
            if (!groups.has(node.category)) groups.set(node.category, []);
            groups.get(node.category).push(node);
        }
        return groups;
    }

    getCategoryBounds(category) {
        const nodes = this.categoryGroups.get(category);
        if (!nodes || nodes.length === 0) return null;

        let minX = Infinity, maxX = -Infinity;
        let minY = Infinity, maxY = -Infinity;

        for (const node of nodes) {
            const padding = node.radius + 20;
            minX = Math.min(minX, node.x - padding);
            maxX = Math.max(maxX, node.x + padding);
            minY = Math.min(minY, node.y - padding);
            maxY = Math.max(maxY, node.y + padding);
        }

        return {minX, maxX, minY, maxY, category};
    }

    lineIntersectsRect(x1, y1, x2, y2, rect) {
        if (x1 >= rect.minX && x1 <= rect.maxX && y1 >= rect.minY && y1 <= rect.maxY) return true;
        if (x2 >= rect.minX && x2 <= rect.maxX && y2 >= rect.minY && y2 <= rect.maxY) return true;

        return this.lineIntersectsLine(x1, y1, x2, y2, rect.minX, rect.minY, rect.maxX, rect.minY) ||
            this.lineIntersectsLine(x1, y1, x2, y2, rect.maxX, rect.minY, rect.maxX, rect.maxY) ||
            this.lineIntersectsLine(x1, y1, x2, y2, rect.maxX, rect.maxY, rect.minX, rect.maxY) ||
            this.lineIntersectsLine(x1, y1, x2, y2, rect.minX, rect.maxY, rect.minX, rect.minY);
    }

    lineIntersectsLine(x1, y1, x2, y2, x3, y3, x4, y4) {
        const denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(denom) < 0.0001) return false;

        const t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
        const u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / denom;

        return t >= 0 && t <= 1 && u >= 0 && u <= 1;
    }

    findBypassPoints(x1, y1, x2, y2, rect, margin = 40) {
        const centerX = (rect.minX + rect.maxX) / 2;
        const centerY = (rect.minY + rect.maxY) / 2;

        const dx = x2 - x1;
        const dy = y2 - y1;

        const toRectX = centerX - x1;
        const toRectY = centerY - y1;

        const cross = dx * toRectY - dy * toRectX;

        let waypoints;

        if (Math.abs(dx) > Math.abs(dy)) {
            if (cross > 0) {
                waypoints = [
                    {x: rect.minX - margin, y: rect.minY - margin},
                    {x: rect.maxX + margin, y: rect.minY - margin}
                ];
            } else {
                waypoints = [
                    {x: rect.minX - margin, y: rect.maxY + margin},
                    {x: rect.maxX + margin, y: rect.maxY + margin}
                ];
            }
        } else {
            if (cross > 0) {
                waypoints = [
                    {x: rect.maxX + margin, y: rect.minY - margin},
                    {x: rect.maxX + margin, y: rect.maxY + margin}
                ];
            } else {
                waypoints = [
                    {x: rect.minX - margin, y: rect.minY - margin},
                    {x: rect.minX - margin, y: rect.maxY + margin}
                ];
            }
        }

        return waypoints;
    }

    routeEdge(edge) {
        const source = edge.source;
        const target = edge.target;

        if (!source || !target) return [];

        const path = [{x: source.x, y: source.y}];

        const obstacles = [];
        for (const [category] of this.categoryGroups.entries()) {
            if (category === source.category || category === target.category) continue;

            const bounds = this.getCategoryBounds(category);
            if (bounds && this.lineIntersectsRect(source.x, source.y, target.x, target.y, bounds)) obstacles.push(bounds);
        }

        if (obstacles.length > 0) {
            let currentX = source.x;
            let currentY = source.y;

            for (const obstacle of obstacles) {
                const waypoints = this.findBypassPoints(currentX, currentY, target.x, target.y, obstacle);
                for (const wp of waypoints) path.push(wp);
                if (waypoints.length > 0) {
                    const last = waypoints[waypoints.length - 1];
                    currentX = last.x;
                    currentY = last.y;
                }
            }
        }

        path.push({x: target.x, y: target.y});

        return path;
    }

    smoothPath(path, segments = 20) {
        if (path.length < 2) return path;
        if (path.length === 2) return path;

        const smoothPoints = [];

        smoothPoints.push(path[0]);

        for (let i = 0; i < path.length - 1; i++) {
            const p0 = path[Math.max(0, i - 1)];
            const p1 = path[i];
            const p2 = path[i + 1];
            const p3 = path[Math.min(path.length - 1, i + 2)];

            for (let t = 0; t <= 1; t += 1 / segments) {
                const t2 = t * t;
                const t3 = t2 * t;

                const x = 0.5 * (
                    (2 * p1.x) +
                    (-p0.x + p2.x) * t +
                    (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                    (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3
                );

                const y = 0.5 * (
                    (2 * p1.y) +
                    (-p0.y + p2.y) * t +
                    (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                    (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3
                );

                smoothPoints.push({x, y});
            }
        }

        smoothPoints.push(path[path.length - 1]);

        return smoothPoints;
    }

    bundleEdges(edges) {
        const bundles = new Map();

        for (const edge of edges) {
            const source = edge.source;
            const target = edge.target;

            const bundleKey = `${source.category}->${target.category}`;

            if (!bundles.has(bundleKey)) bundles.set(bundleKey, []);
            bundles.get(bundleKey).push(edge);
        }

        return bundles;
    }

    applyBundling(edges, strength = 0.8) {
        if (!Array.isArray(edges) || edges.length === 0) {
            return [];
        }
        if (edges.length < 2) return edges.map(e => this.routeEdge(e));

        const routes = edges.map(e => this.routeEdge(e));

        const midPoints = routes.map(route => {
            const mid = Math.floor(route.length / 2);
            return route[mid];
        });

        const centroidX = midPoints.reduce((sum, p) => sum + p.x, 0) / midPoints.length;
        const centroidY = midPoints.reduce((sum, p) => sum + p.y, 0) / midPoints.length;

        return routes.map(route => {
            const bundled = [...route];
            const mid = Math.floor(route.length / 2);

            if (bundled[mid]) bundled[mid] = {
                x: bundled[mid].x + (centroidX - bundled[mid].x) * strength,
                y: bundled[mid].y + (centroidY - bundled[mid].y) * strength
            };

            return bundled;
        });
    }

    routeAllEdges(edges, enableBundling = true, smoothness = 15) {
        const result = [];

        if (enableBundling) {
            const bundles = this.bundleEdges(edges);

            for (const [, bundleEdges] of bundles.entries()) {
                const bundledRoutes = this.applyBundling(bundleEdges, 0.6);

                for (let i = 0; i < bundleEdges.length; i++) {
                    const edge = bundleEdges[i];
                    const route = bundledRoutes[i];
                    const smoothRoute = this.smoothPath(route, smoothness);

                    result.push({edge, path: smoothRoute});
                }
            }
        } else {
            for (const edge of edges) {
                const route = this.routeEdge(edge);
                const smoothRoute = this.smoothPath(route, smoothness);

                result.push({edge, path: smoothRoute});
            }
        }

        return result;
    }
}
