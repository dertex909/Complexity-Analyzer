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

import {LAYOUT_CONSTANTS} from './constants.js';

export class Layout {
    static applyPipeline(nodes) {
        const cols = new Map();
        for (const n of nodes) {
            const d = n.depth;
            if (!cols.has(d)) cols.set(d, []);
            cols.get(d).push(n);
        }

        const {colSpacing, rowSpacing, subColSpacing, nodesPerSubCol} = LAYOUT_CONSTANTS.PIPELINE;

        for (const [d, colNodes] of cols.entries()) {
            colNodes.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
            const N = colNodes.length;
            const subColsCount = Math.ceil(N / nodesPerSubCol);

            for (let i = 0; i < N; i++) {
                const n = colNodes[i];
                const subCol = i % subColsCount;
                const row = Math.floor(i / subColsCount);
                const numRows = Math.ceil(N / subColsCount);

                n.targetX = d * colSpacing + subCol * subColSpacing;
                n.targetY = (row - (numRows - 1) / 2) * rowSpacing;
            }
        }
    }

    static applyRadial(nodes) {
        const rings = new Map();
        for (const n of nodes) {
            const d = n.depth;
            if (!rings.has(d)) rings.set(d, []);
            rings.get(d).push(n);
        }

        const {baseRadius, radiusStep, angleOffset} = LAYOUT_CONSTANTS.RADIAL;

        for (const [d, ringNodes] of rings.entries()) {
            ringNodes.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
            const N = ringNodes.length;
            const radius = baseRadius + d * radiusStep;

            for (let i = 0; i < N; i++) {
                const n = ringNodes[i];
                const angle = (i / N) * 2 * Math.PI + (d * angleOffset);
                n.targetX = radius * Math.cos(angle);
                n.targetY = radius * Math.sin(angle);
            }
        }
    }

    static applyCategory(nodes) {
        const catGroups = new Map();
        for (const n of nodes) {
            const cat = n.category;
            if (!catGroups.has(cat)) catGroups.set(cat, []);
            catGroups.get(cat).push(n);
        }

        const categories = Array.from(catGroups.keys()).sort();
        const numCats = categories.length;
        const gridCols = Math.ceil(Math.sqrt(numCats));

        const {blockSpacingX, blockSpacingY, nodeSpacing} = LAYOUT_CONSTANTS.CATEGORY;

        categories.forEach((cat, catIdx) => {
            const catNodes = catGroups.get(cat);
            const numNodes = catNodes.length;
            const colsCount = Math.ceil(Math.sqrt(numNodes));

            const blockCol = catIdx % gridCols;
            const blockRow = Math.floor(catIdx / gridCols);
            const centerX = blockCol * blockSpacingX;
            const centerY = blockRow * blockSpacingY;

            catNodes.sort((a, b) => b.degree - a.degree || a.name.localeCompare(b.name));

            for (let i = 0; i < numNodes; i++) {
                const n = catNodes[i];
                const r = Math.floor(i / colsCount);
                const c = i % colsCount;
                const rowsCount = Math.ceil(numNodes / colsCount);

                n.targetX = centerX + (c - (colsCount - 1) / 2) * nodeSpacing;
                n.targetY = centerY + (r - (rowsCount - 1) / 2) * nodeSpacing;
            }
        });
    }

    static applyFocused(nodes, focusedLevels) {
        const focusedNodes = nodes.filter(n => focusedLevels.has(n.id));
        if (focusedNodes.length === 0) return;

        const levelGroups = new Map();
        for (const n of focusedNodes) {
            const lvl = focusedLevels.get(n.id);
            if (!levelGroups.has(lvl)) levelGroups.set(lvl, []);
            levelGroups.get(lvl).push(n);
        }

        const {colSpacing, rowSpacing} = LAYOUT_CONSTANTS.FOCUSED;

        for (const [lvl, grp] of levelGroups.entries()) {
            grp.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
            const count = grp.length;

            for (let i = 0; i < count; i++) {
                const n = grp[i];
                n.targetX = lvl * colSpacing;
                n.targetY = (i - (count - 1) / 2) * rowSpacing;
            }
        }
    }

    static snapToTarget(nodes) {
        nodes.forEach(n => {
            if (n.x === 0 && n.y === 0) {
                n.x = n.targetX;
                n.y = n.targetY;
            }
        });
    }

    static updatePositions(nodes, lerpSpeed) {
        let changed = false;

        for (const n of nodes) {
            const dx = n.targetX - n.x;
            const dy = n.targetY - n.y;

            if (Math.abs(dx) > 0.05 || Math.abs(dy) > 0.05) {
                n.x += dx * lerpSpeed;
                n.y += dy * lerpSpeed;
                changed = true;
            } else {
                n.x = n.targetX;
                n.y = n.targetY;
            }
        }

        return changed;
    }
}
