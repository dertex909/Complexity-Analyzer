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

export function applyInitialLayout(nodes, edges) {
    nodes.sort((a, b) => b.degree - a.degree);

    const PHI = Math.PI * (3 - Math.sqrt(5));
    nodes.forEach((n, idx) => {
        const angle = idx * PHI;
        const baseRadius = GRAPH_CONFIG.INITIAL_LAYOUT_RADIUS * Math.sqrt(idx);

        const jitterX = (Math.random() - 0.5) * 5;
        const jitterY = (Math.random() - 0.5) * 5;

        n.x = baseRadius * Math.cos(angle) + jitterX;
        n.y = baseRadius * Math.sin(angle) + jitterY;
        n.vx = 0;
        n.vy = 0;
    });

    const ITERS = 120;
    const SPRING_K = 0.08;
    const REPULSION_K = 250.0;
    const CELL_SIZE = 120;
    const DAMPING = 0.7;

    for (let iter = 0; iter < ITERS; iter++) {
        for (let i = 0; i < edges.length; i++) {
            const edge = edges[i];
            const dx = edge.target.x - edge.source.x;
            const dy = edge.target.y - edge.source.y;

            const w1 = 1 / Math.max(1, Math.sqrt(edge.source.degree || 1));
            const w2 = 1 / Math.max(1, Math.sqrt(edge.target.degree || 1));

            edge.source.vx += dx * SPRING_K * w1;
            edge.source.vy += dy * SPRING_K * w1;
            edge.target.vx -= dx * SPRING_K * w2;
            edge.target.vy -= dy * SPRING_K * w2;
        }

        const grid = new Map();
        for (let i = 0; i < nodes.length; i++) {
            const n = nodes[i];
            const cx = Math.floor(n.x / CELL_SIZE);
            const cy = Math.floor(n.y / CELL_SIZE);
            const key = cx + "," + cy;

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
                    const key = (cx + dx) + "," + (cy + dy);
                    const cell = grid.get(key);
                    if (cell) for (let j = 0; j < cell.length; j++) {
                        const n2 = cell[j];
                        if (n1.id >= n2.id) continue;

                        const dX = n2.x - n1.x;
                        const dY = n2.y - n1.y;
                        const minGap = n1.radius + n2.radius + 3.0;

                        if (Math.abs(dX) > CELL_SIZE || Math.abs(dY) > CELL_SIZE) continue;

                        const distSq = dX * dX + dY * dY;
                        if (distSq < minGap * minGap) {
                            const dist = Math.sqrt(distSq) || 0.1;
                            const overlap = minGap - dist;
                            const force = (overlap / dist) * 0.5;
                            n1.vx -= dX * force;
                            n1.vy -= dY * force;
                            n2.vx += dX * force;
                            n2.vy += dY * force;
                        } else if (distSq < CELL_SIZE * CELL_SIZE) {
                            const repForce = REPULSION_K / distSq;
                            n1.vx -= dX * repForce;
                            n1.vy -= dY * repForce;
                            n2.vx += dX * repForce;
                            n2.vy += dY * repForce;
                        }
                    }
                }
            }

            n1.vx -= n1.x * 0.0001;
            n1.vy -= n1.y * 0.0001;
        }

        for (let i = 0; i < nodes.length; i++) {
            const n = nodes[i];
            n.x += n.vx;
            n.y += n.vy;
            n.vx *= DAMPING;
            n.vy *= DAMPING;
        }
    }

    let overlaps = 1;
    let collIters = 0;
    while (overlaps > 0 && collIters < 100) {
        overlaps = 0;

        const grid = new Map();
        for (let i = 0; i < nodes.length; i++) {
            const n = nodes[i];
            const cx = Math.floor(n.x / CELL_SIZE);
            const cy = Math.floor(n.y / CELL_SIZE);
            const key = cx + "," + cy;
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
                    const key = (cx + dx) + "," + (cy + dy);
                    const cell = grid.get(key);
                    if (cell) for (let j = 0; j < cell.length; j++) {
                        const n2 = cell[j];
                        if (n1.id >= n2.id) continue;

                        const dX = n2.x - n1.x;
                        const dY = n2.y - n1.y;
                        const minGap = n1.radius + n2.radius + 3.0;

                        if (Math.abs(dX) > minGap || Math.abs(dY) > minGap) continue;

                        const distSq = dX * dX + dY * dY;
                        if (distSq < minGap * minGap) {
                            overlaps++;
                            const dist = Math.sqrt(distSq) || 0.1;
                            const overlap = minGap - dist;
                            const force = (overlap / dist) * 0.55;
                            n1.x -= dX * force;
                            n1.y -= dY * force;
                            n2.x += dX * force;
                            n2.y += dY * force;
                        }
                    }
                }
            }
        }
        collIters++;
    }
}
