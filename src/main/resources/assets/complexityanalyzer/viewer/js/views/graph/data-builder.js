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
            category: item.categoryName || "Unknown",
            complexity: item.complexity,
            depth: item.depth,
            flags: item.flags,
            degree: 0,
            radius: 6
        };

        nodes.push(node);
        nodeMap.set(i, node);
        neighborMap.set(i, new Set());
    }

    const addEdge = (idA, idB) => {
        if (idA < 0 || idA >= itemsCount || idB < 0 || idB >= itemsCount) return;

        const minVal = Math.min(idA, idB);
        const maxVal = Math.max(idA, idB);
        const edgeKey = `${minVal}-${maxVal}`;

        if (!edgeKeySet.has(edgeKey)) {
            edgeKeySet.add(edgeKey);
            edges.push({source: minVal, target: maxVal});

            const nA = nodeMap.get(minVal);
            const nB = nodeMap.get(maxVal);
            if (nA) nA.degree++;
            if (nB) nB.degree++;

            neighborMap.get(minVal).add(maxVal);
            neighborMap.get(maxVal).add(minVal);
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

    const activeNodes = nodes.filter(n => n.degree > 0);
    const activeNodeMap = new Map();

    activeNodes.forEach(n => {
        n.radius = Math.max(
            GRAPH_CONFIG.MIN_NODE_RADIUS,
            GRAPH_CONFIG.NODE_BASE_RADIUS + Math.sqrt(n.degree) * GRAPH_CONFIG.NODE_RADIUS_SCALE
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

export function applyInitialLayout(nodes) {
    nodes.forEach((n, idx) => {
        const angle = idx * GRAPH_CONFIG.INITIAL_LAYOUT_ANGLE;
        const radius = GRAPH_CONFIG.INITIAL_LAYOUT_RADIUS * Math.sqrt(idx);
        n.x = radius * Math.cos(angle);
        n.y = radius * Math.sin(angle);
    });
}
