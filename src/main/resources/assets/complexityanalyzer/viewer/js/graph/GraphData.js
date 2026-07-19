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

import {readRecipesAt} from "../core/cabin.js";
import {NODE_CONSTANTS} from './constants.js';

export class GraphData {
    constructor(db) {
        this.db = db;
        this.nodes = [];
        this.nodeMap = new Map();
        this.neighborMap = new Map();
        this.edges = [];
        this.edgeKeySet = new Set();
        this.activeNodes = [];
    }

    build() {
        const itemsCount = this.db.items.count;

        for (let i = 0; i < itemsCount; i++) {
            const item = this.db.items.get(i);
            if (!item) continue;

            const node = {
                id: i,
                name: item.name,
                category: item.categoryName || "Unknown",
                complexity: item.complexity,
                depth: item.depth || 0,
                flags: item.flags,
                degree: 0,
                radius: 8,
                x: 0, y: 0,
                targetX: 0, targetY: 0
            };

            this.nodes.push(node);
            this.nodeMap.set(i, node);
            this.neighborMap.set(i, new Set());
        }

        if (this.db._rB) for (let i = 0; i < itemsCount; i++) {
            const ref = this.db.recipeIndex.get(i);
            if (ref && ref.offset !== 0xFFFFFFFF && ref.count > 0) try {
                const recipes = readRecipesAt(this.db._rB, this.db.strings, ref.offset, ref.count);
                for (const recipe of recipes) {
                    const outInd = recipe.outputItemIndex;
                    if (outInd >= 0 && outInd < itemsCount) {
                        for (const ing of recipe.ingredients) for (const vi of ing.variants) this.addEdge(vi, outInd);
                        for (const otherOut of recipe.itemOutputs) this.addEdge(outInd, otherOut.itemIndex);
                    }
                }
            } catch (e) {
                console.error("Error building recipe link indexes:", e);
            }
        }

        this.activeNodes = this.nodes.filter(n => n.degree > 0);
        this.activeNodes.forEach(n => {
            n.radius = Math.max(
                NODE_CONSTANTS.MIN_RADIUS,
                Math.min(
                    NODE_CONSTANTS.MAX_RADIUS,
                    NODE_CONSTANTS.BASE_RADIUS + Math.sqrt(n.degree) * NODE_CONSTANTS.RADIUS_SCALE
                )
            );
        });
    }

    addEdge(idIng, idOut) {
        const itemsCount = this.db.items.count;
        if (idIng < 0 || idIng >= itemsCount || idOut < 0 || idOut >= itemsCount || idIng === idOut) return;

        const edgeKey = `${idIng}->${idOut}`;
        if (!this.edgeKeySet.has(edgeKey)) {
            this.edgeKeySet.add(edgeKey);
            const nodeA = this.nodeMap.get(idIng);
            const nodeB = this.nodeMap.get(idOut);

            if (nodeA && nodeB) {
                nodeA.degree++;
                nodeB.degree++;
                this.edges.push({source: nodeA, target: nodeB});
                this.neighborMap.get(idIng).add(idOut);
                this.neighborMap.get(idOut).add(idIng);
            }
        }
    }

    getNode(id) {
        return this.nodeMap.get(id);
    }
}
