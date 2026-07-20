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
            degree: n.degree
        }));

        const workerEdges = edges.map(e => ({
            sourceId: e.source.id,
            targetId: e.target.id
        }));

        const workerCode = `
        self.onmessage = function(e) {
            const { nodes, edges, config } = e.data;
            const PHI = Math.PI * (3 - Math.sqrt(5));
            
            nodes.forEach((n, idx) => {
                const angle = idx * PHI;
                const baseRadius = config.INITIAL_LAYOUT_RADIUS * Math.sqrt(idx);

                const jitterX = (Math.random() - 0.5) * 5;
                const jitterY = (Math.random() - 0.5) * 5;

                n.x = baseRadius * Math.cos(angle) + jitterX;
                n.y = baseRadius * Math.sin(angle) + jitterY;
                n.vx = 0;
                n.vy = 0;
            });

            const nodeMap = new Map();
            nodes.forEach(n => nodeMap.set(n.id, n));

            const ITERS = 120;
            const COLL_LIMIT = 100;
            const TOTAL_STEPS = ITERS + COLL_LIMIT;

            const SPRING_K = 0.02;
            const REPULSION_K = 600.0;
            const CELL_SIZE = 160; 
            const DAMPING = 0.7;

            const getGridKey = (x, y) => {
                const cx = Math.floor(x / CELL_SIZE) + 50000;
                const cy = Math.floor(y / CELL_SIZE) + 50000;
                return (cx << 16) | cy;
            };

            for (let iter = 0; iter < ITERS; iter++) {
                for (let i = 0; i < edges.length; i++) {
                    const edge = edges[i];
                    const sNode = nodeMap.get(edge.sourceId);
                    const tNode = nodeMap.get(edge.targetId);
                    if (!sNode || !tNode) continue;

                    let dx = tNode.x - sNode.x;
                    let dy = tNode.y - sNode.y;

                    const dist = Math.hypot(dx, dy) || 0.1;
                    if (dist > 300.0) {
                        dx = (dx / dist) * 300.0;
                        dy = (dy / dist) * 300.0;
                    }

                    const w1 = 1 / Math.max(1, Math.sqrt(sNode.degree || 1));
                    const w2 = 1 / Math.max(1, Math.sqrt(tNode.degree || 1));

                    sNode.vx += dx * SPRING_K * w1;
                    sNode.vy += dy * SPRING_K * w1;
                    tNode.vx -= dx * SPRING_K * w2;
                    tNode.vy -= dy * SPRING_K * w2;
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
                            const key = ((cx + dx + 50000) << 16) | (cy + dy + 50000);
                            const cell = grid.get(key);
                            if (cell) {
                                for (let j = 0; j < cell.length; j++) {
                                    const n2 = cell[j];
                                    if (n1.id >= n2.id) continue;

                                    let dX = n2.x - n1.x;
                                    let dY = n2.y - n1.y;
                                    let distSq = dX * dX + dY * dY;

                                    if (distSq < 0.01) {
                                        const randAngle = Math.random() * Math.PI * 2;
                                        dX = Math.cos(randAngle);
                                        dY = Math.sin(randAngle);
                                        distSq = 1.0;
                                    }

                                    const minGap = n1.radius + n2.radius + 8.0;

                                    if (Math.abs(dX) > CELL_SIZE || Math.abs(dY) > CELL_SIZE) continue;

                                    if (distSq < minGap * minGap) {
                                        const dist = Math.sqrt(distSq);
                                        const overlap = minGap - dist;

                                        const pushX = (dX / dist) * overlap * 0.5;
                                        const pushY = (dY / dist) * overlap * 0.5;

                                        n1.vx -= pushX;
                                        n1.vy -= pushY;
                                        n2.vx += pushX;
                                        n2.vy += pushY;
                                    } else if (distSq < CELL_SIZE * CELL_SIZE) {
                                        const safeDistSq = Math.max(distSq, 4.0);
                                        const repForce = REPULSION_K / safeDistSq;
                                        n1.vx -= dX * repForce;
                                        n1.vy -= dY * repForce;
                                        n2.vx += dX * repForce;
                                        n2.vy += dY * repForce;
                                    }
                                }
                            }
                        }
                    }

                    n1.vx -= n1.x * 0.0001;
                    n1.vy -= n1.y * 0.0001;
                }

                for (let i = 0; i < nodes.length; i++) {
                    const n = nodes[i];
                    
                    const speed = Math.hypot(n.vx, n.vy);
                    if (speed > 100.0) {
                        n.vx = (n.vx / speed) * 100.0;
                        n.vy = (n.vy / speed) * 100.0;
                    }

                    n.x += n.vx;
                    n.y += n.vy;
                    n.vx *= DAMPING;
                    n.vy *= DAMPING;

                    if (!isFinite(n.x) || !isFinite(n.y)) {
                        n.x = (Math.random() - 0.5) * 50;
                        n.y = (Math.random() - 0.5) * 50;
                        n.vx = 0;
                        n.vy = 0;
                    }
                }

                if (iter % 5 === 0) {
                    self.postMessage({ type: "progress", percent: Math.round((iter / TOTAL_STEPS) * 100) });
                }
            }

            let overlaps = 1;
            let collIters = 0;
            while (overlaps > 0 && collIters < COLL_LIMIT) {
                overlaps = 0;

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
                            const key = ((cx + dx + 50000) << 16) | (cy + dy + 50000);
                            const cell = grid.get(key);
                            if (cell) {
                                for (let j = 0; j < cell.length; j++) {
                                    const n2 = cell[j];
                                    if (n1.id >= n2.id) continue;

                                    let dX = n2.x - n1.x;
                                    let dY = n2.y - n1.y;
                                    let distSq = dX * dX + dY * dY;

                                    if (distSq < 0.01) {
                                        const randAngle = Math.random() * Math.PI * 2;
                                        dX = Math.cos(randAngle);
                                        dY = Math.sin(randAngle);
                                        distSq = 1.0;
                                    }

                                    const minGap = n1.radius + n2.radius + 8.0;

                                    if (Math.abs(dX) > minGap || Math.abs(dY) > minGap) continue;

                                    if (distSq < minGap * minGap) {
                                        overlaps++;
                                        const dist = Math.sqrt(distSq);
                                        const overlap = minGap - dist;

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

                    if (!isFinite(n1.x) || !isFinite(n1.y)) {
                        n1.x = 0;
                        n1.y = 0;
                    }
                }
                collIters++;

                if (collIters % 5 === 0) {
                    self.postMessage({ type: "progress", percent: Math.round(((ITERS + collIters) / TOTAL_STEPS) * 100) });
                }
            }

            const coords = nodes.map(n => ({ id: n.id, x: n.x, y: n.y }));
            self.postMessage({ type: "complete", coords });
        };
        `;

        const blob = new Blob([workerCode], {type: "application/javascript"});
        const workerUrl = URL.createObjectURL(blob);
        const worker = new Worker(workerUrl);

        worker.postMessage({
            nodes: workerNodes,
            edges: workerEdges,
            config: {INITIAL_LAYOUT_RADIUS: GRAPH_CONFIG.INITIAL_LAYOUT_RADIUS}
        });

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
                        n.vx = 0;
                        n.vy = 0;
                    }
                });

                worker.terminate();
                URL.revokeObjectURL(workerUrl);
                resolve();
            }
        };
    });
}