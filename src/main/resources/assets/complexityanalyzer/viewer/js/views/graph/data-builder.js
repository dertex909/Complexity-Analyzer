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

import {readRecipesAt} from "../../core/cabin.js";
import {GRAPH_CONFIG} from "./constants.js";

export function buildGraphData(db) {
    const itemsCount = db.items.count;
    const nodes = [];
    const edges = [];
    const nodeMap = new Map();
    const neighborMap = new Map();
    const edgeKeySet = new Set();

    for (let i = 0; i < itemsCount; i++) {
        const item = db.items.get(i);
        if (!item) continue;

        const node = {
            id: i,
            name: item.name,
            itemId: item.id,
            category: item.categoryName || "Unknown",
            complexity: item.complexity,
            depth: item.depth,
            totalIngredients: item.totalIngredients,
            usageCount: item.usageCount,
            flags: item.flags,
            degree: 0,
            radius: 6
        };

        nodes.push(node);
        nodeMap.set(i, node);
        neighborMap.set(i, new Set());
    }

    const addEdge = (ingId, outId) => {
        if (ingId < 0 || ingId >= itemsCount || outId < 0 || outId >= itemsCount) return;

        const minVal = Math.min(ingId, outId);
        const maxVal = Math.max(ingId, outId);
        const edgeKey = `${minVal}-${maxVal}`;

        if (!edgeKeySet.has(edgeKey)) {
            edgeKeySet.add(edgeKey);
            edges.push({source: ingId, target: outId});

            const nA = nodeMap.get(ingId);
            const nB = nodeMap.get(outId);
            if (nA) nA.degree++;
            if (nB) nB.degree++;

            neighborMap.get(ingId).add(outId);
            neighborMap.get(outId).add(ingId);
        }
    };

    if (db._rB) for (let i = 0; i < itemsCount; i++) {
        const ref = db.recipeIndex.get(i);
        if (ref && ref.offset !== 0xFFFFFFFF && ref.count > 0) try {
            const recipes = readRecipesAt(db._rB, db.strings, ref.offset, ref.count);
            for (const recipe of recipes) {
                const outInd = recipe.outputItemIndex;
                if (outInd >= 0 && outInd < itemsCount) {
                    for (const ing of recipe.ingredients) for (const vi of ing.variants) addEdge(vi, outInd);
                    for (const otherOut of recipe.itemOutputs) addEdge(outInd, otherOut.itemIndex);
                }
            }
        } catch (e) {
        }
    }

    const activeNodes = nodes;
    const activeNodeMap = new Map();

    activeNodes.forEach(n => {
        n.radius = Math.max(
            GRAPH_CONFIG.MIN_NODE_RADIUS,
            GRAPH_CONFIG.NODE_BASE_RADIUS + Math.log(Math.max(1, n.complexity || 1)) * GRAPH_CONFIG.NODE_RADIUS_SCALE
        );
        activeNodeMap.set(n.id, n);
    });

    const resolvedEdges = edges
        .map(e => {
            const sNode = activeNodeMap.get(e.source);
            const tNode = activeNodeMap.get(e.target);
            if (sNode && tNode) return {source: sNode, target: tNode};
            return null;
        })
        .filter(Boolean);

    return {
        nodes: activeNodes,
        edges: resolvedEdges,
        neighborMap
    };
}

export function applyInitialLayoutAsync(nodes, edges, onProgress) {
    return new Promise((resolve) => {
        const workerNodes = nodes.map(n => ({
            id: n.id,
            radius: n.radius,
            complexity: (n.complexity > 0 && isFinite(n.complexity)) ? n.complexity : 0,
            degree: n.degree
        }));

        const workerEdges = edges.map(e => ({
            sourceId: e.source.id,
            targetId: e.target.id
        }));

        const workerCode = `
        self.onmessage = function(e) {
            const { nodes, edges } = e.data;
            const nodeMap = new Map();
            nodes.forEach(n => nodeMap.set(n.id, n));

            const PHI = Math.PI * (3 - Math.sqrt(5));
            for (let i = 0; i < nodes.length; i++) {
                const n = nodes[i];
                const angle = i * PHI;
                const dist = 32.0 * Math.sqrt(i) + (Math.random() - 0.5) * 20.0;
                n.x = dist * Math.cos(angle);
                n.y = dist * Math.sin(angle);
                n.vx = 0;
                n.vy = 0;
            }

            const ITERS = 180;
            const COLL_ITERS = 80;
            const TOTAL_STEPS = ITERS + COLL_ITERS;

            const SPRING_K = 0.008;
            const REPULSION_K = 2400.0;
            const CELL_SIZE = 120;
            const GAP = 9.0;
            const GRID_OFFSET = 50000;

            const getGridKey = (x, y) => {
                const cx = (Math.floor(x / CELL_SIZE) + GRID_OFFSET) & 0xFFFF;
                const cy = (Math.floor(y / CELL_SIZE) + GRID_OFFSET) & 0xFFFF;
                return (cx << 16) | cy;
            };

            for (let iter = 0; iter < ITERS; iter++) {
                const temp = 1.0 - (iter / ITERS) * 0.65;

                for (let i = 0; i < edges.length; i++) {
                    const edge = edges[i];
                    const sNode = nodeMap.get(edge.sourceId);
                    const tNode = nodeMap.get(edge.targetId);
                    if (!sNode || !tNode) continue;

                    let dx = tNode.x - sNode.x;
                    let dy = tNode.y - sNode.y;
                    const dist = Math.hypot(dx, dy) || 0.1;

                    const desiredDist = sNode.radius + tNode.radius + 38.0;
                    const delta = dist - desiredDist;
                    const force = delta * SPRING_K * temp;

                    const w1 = 1 / Math.max(1, Math.sqrt(sNode.degree || 1));
                    const w2 = 1 / Math.max(1, Math.sqrt(tNode.degree || 1));

                    const fx = (dx / dist) * force;
                    const fy = (dy / dist) * force;

                    sNode.vx += fx * w1;
                    sNode.vy += fy * w1;
                    tNode.vx -= fx * w2;
                    tNode.vy -= fy * w2;
                }

                const grid = new Map();
                for (let i = 0; i < nodes.length; i++) {
                    const n = nodes[i];
                    const key = getGridKey(n.x, n.y);
                    let cell = grid.get(key);
                    if (!cell) {
                        cell = [];
                        grid.set(key, cell);
                    }
                    cell.push(n);
                }

                for (let i = 0; i < nodes.length; i++) {
                    const n1 = nodes[i];
                    const cx = Math.floor(n1.x / CELL_SIZE);
                    const cy = Math.floor(n1.y / CELL_SIZE);

                    for (let dx = -1; dx <= 1; dx++) {
                        for (let dy = -1; dy <= 1; dy++) {
                            const key = (((cx + dx + GRID_OFFSET) & 0xFFFF) << 16) | ((cy + dy + GRID_OFFSET) & 0xFFFF);
                            const cell = grid.get(key);
                            if (!cell) continue;

                            for (let j = 0; j < cell.length; j++) {
                                const n2 = cell[j];
                                if (n1.id >= n2.id) continue;

                                let dX = n2.x - n1.x;
                                let dY = n2.y - n1.y;
                                let distSq = dX * dX + dY * dY;

                                if (distSq < 0.01) {
                                    const rnd = Math.random() * Math.PI * 2;
                                    dX = Math.cos(rnd);
                                    dY = Math.sin(rnd);
                                    distSq = 1.0;
                                }

                                const minDist = n1.radius + n2.radius + GAP;

                                if (distSq < minDist * minDist) {
                                    const dist = Math.sqrt(distSq);
                                    const overlap = minDist - dist;
                                    const pushX = (dX / dist) * overlap * 0.6;
                                    const pushY = (dY / dist) * overlap * 0.6;

                                    n1.vx -= pushX;
                                    n1.vy -= pushY;
                                    n2.vx += pushX;
                                    n2.vy += pushY;
                                } else if (distSq < CELL_SIZE * CELL_SIZE) {
                                    const rep = (REPULSION_K * temp) / Math.max(distSq, 36.0);
                                    n1.vx -= dX * rep;
                                    n1.vy -= dY * rep;
                                    n2.vx += dX * rep;
                                    n2.vy += dY * rep;
                                }
                            }
                        }
                    }

                    n1.vx -= n1.x * 0.00004;
                    n1.vy -= n1.y * 0.00004;
                }

                for (let i = 0; i < nodes.length; i++) {
                    const n = nodes[i];
                    const speed = Math.hypot(n.vx, n.vy);
                    if (speed > 60.0) {
                        n.vx = (n.vx / speed) * 60.0;
                        n.vy = (n.vy / speed) * 60.0;
                    }
                    n.x += n.vx;
                    n.y += n.vy;
                    n.vx *= 0.68;
                    n.vy *= 0.68;
                }

                if (iter % 5 === 0) {
                    self.postMessage({ type: "progress", percent: Math.round((iter / TOTAL_STEPS) * 100) });
                }
            }

            for (let iter = 0; iter < COLL_ITERS; iter++) {
                const grid = new Map();
                for (let i = 0; i < nodes.length; i++) {
                    const n = nodes[i];
                    const key = getGridKey(n.x, n.y);
                    let cell = grid.get(key);
                    if (!cell) {
                        cell = [];
                        grid.set(key, cell);
                    }
                    cell.push(n);
                }

                let maxOverlap = 0;

                for (let i = 0; i < nodes.length; i++) {
                    const n1 = nodes[i];
                    const cx = Math.floor(n1.x / CELL_SIZE);
                    const cy = Math.floor(n1.y / CELL_SIZE);

                    for (let dx = -1; dx <= 1; dx++) {
                        for (let dy = -1; dy <= 1; dy++) {
                            const key = (((cx + dx + GRID_OFFSET) & 0xFFFF) << 16) | ((cy + dy + GRID_OFFSET) & 0xFFFF);
                            const cell = grid.get(key);
                            if (!cell) continue;

                            for (let j = 0; j < cell.length; j++) {
                                const n2 = cell[j];
                                if (n1.id >= n2.id) continue;

                                let dX = n2.x - n1.x;
                                let dY = n2.y - n1.y;
                                let distSq = dX * dX + dY * dY;
                                const minDist = n1.radius + n2.radius + GAP;

                                if (distSq < minDist * minDist) {
                                    let dist = Math.sqrt(distSq);
                                    if (dist < 0.001) {
                                        const rnd = Math.random() * Math.PI * 2;
                                        dX = Math.cos(rnd);
                                        dY = Math.sin(rnd);
                                        dist = 0.001;
                                    }

                                    const overlap = minDist - dist;
                                    if (overlap > maxOverlap) maxOverlap = overlap;

                                    const pushX = (dX / dist) * overlap * 0.5;
                                    const pushY = (dY / dist) * overlap * 0.5;

                                    n1.x -= pushX;
                                    n1.y -= pushY;
                                    n2.x += pushX;
                                    n2.y += pushY;
                                }
                            }
                        }
                    }
                }

                if (iter % 5 === 0) {
                    self.postMessage({ type: "progress", percent: Math.round(((ITERS + iter) / TOTAL_STEPS) * 100) });
                }

                if (maxOverlap < 0.05) break;
            }

            const coords = nodes.map(n => ({ id: n.id, x: n.x, y: n.y }));
            self.postMessage({ type: "complete", coords });
        };
        `;

        const blob = new Blob([workerCode], {type: "application/javascript"});
        const workerUrl = URL.createObjectURL(blob);
        const worker = new Worker(workerUrl);

        worker.postMessage({nodes: workerNodes, edges: workerEdges});

        worker.onmessage = (e) => {
            const msg = e.data;

            if (msg.type === "progress") {
                if (typeof onProgress === "function") onProgress(msg.percent);
            } else if (msg.type === "complete") {
                const {coords} = msg;

                const nodeMap = new Map();
                nodes.forEach(n => nodeMap.set(n.id, n));

                coords.forEach(c => {
                    const n = nodeMap.get(c.id);
                    if (n) {
                        n.x = c.x;
                        n.y = c.y;
                    }
                });

                worker.terminate();
                URL.revokeObjectURL(workerUrl);
                resolve();
            }
        };
    });
}