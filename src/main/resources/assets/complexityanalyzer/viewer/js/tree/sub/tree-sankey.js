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

import { state } from "../../core/state.js";
import { TreeUtils } from "./tree-utils.js";

export function renderSankeyTree(craftContainer, treeData) {
    const stages = new Map();
    let maxDepth = 0;

    function traverse(node, depth) {
        if (depth > maxDepth) maxDepth = depth;
        const key = `${node.kind}:${node.index}`;
        const existing = stages.get(key);
        if (!existing || depth > existing.depth) stages.set(key, { node, depth });
        if (node.children) for (const c of node.children) traverse(c, depth + 1);
    }
    traverse(treeData, 0);

    const columns = [];
    for (let i = 0; i <= maxDepth; i++) {
        columns.push([]);
    }
    for (const [key, val] of stages.entries()) {
        const colIndex = maxDepth - val.depth;
        columns[colIndex].push(val.node);
    }

    const colWidth = 220;
    const sankeyWidth = Math.max(800, (maxDepth + 1) * colWidth + 100);
    const sankeyHeight = 580;

    const nodeCoords = new Map();

    columns.forEach((nodes, colIdx) => {
        const x = 50 + colIdx * colWidth;
        const nodeCount = nodes.length;
        const totalGapHeight = 35 * (nodeCount - 1);
        const availableHeight = sankeyHeight - 100;
        const itemHeight = Math.max(30, Math.min(60, (availableHeight - totalGapHeight) / nodeCount));
        
        const usedHeight = nodeCount * itemHeight + (nodeCount - 1) * 25;
        const startY = 50 + (sankeyHeight - usedHeight) / 2;

        nodes.forEach((n, nodeIdx) => {
            const y = startY + nodeIdx * (itemHeight + 25);
            nodeCoords.set(`${n.kind}:${n.index}`, {
                x,
                y,
                w: 160,
                h: itemHeight
            });
        });
    });

    const paths = [];
    function buildPaths(node) {
        if (node.children) {
            const parentCoord = nodeCoords.get(`${node.kind}:${node.index}`);
            for (const child of node.children) {
                const childCoord = nodeCoords.get(`${child.kind}:${child.index}`);
                if (parentCoord && childCoord) {
                    const x0 = childCoord.x + childCoord.w;
                    const y0 = childCoord.y + childCoord.h / 2;
                    const x1 = parentCoord.x;
                    const y1 = parentCoord.y + parentCoord.h / 2;
                    const thickness = Math.max(4, Math.min(24, (child.amountText ? parseFloat(child.amountText) || 5 : 5)));
                    
                    const pathD = `M ${x0} ${y0} C ${(x0 + x1) / 2} ${y0}, ${(x0 + x1) / 2} ${y1}, ${x1} ${y1}`;
                    paths.push({
                        d: pathD,
                        thickness,
                        fromName: child.name,
                        toName: node.name,
                        qty: child.amountText || "1"
                    });
                }
                buildPaths(child);
            }
        }
    }
    buildPaths(treeData);

    const linksHtml = paths.map(p => {
        return `<path class="sankey-link-path" d="${p.d}" stroke-width="${p.thickness}" title="${TreeUtils.escape(p.fromName)} → ${TreeUtils.escape(p.toName)} (${p.qty})" />`;
    }).join("");

    const nodesHtml = Array.from(nodeCoords.entries()).map(([key, coords]) => {
        const [kind, idxStr] = key.split(":");
        const index = parseInt(idxStr, 10);
        const isItem = kind === "item";
        const name = isItem ? state.db.items.get(index)?.name : state.db.fluids.get(index)?.name;
        
        return `
            <div class="craft-tree-node-card" style="position: absolute; left: ${coords.x}px; top: ${coords.y}px; width: ${coords.w}px; height: ${coords.h}px; margin: 0; padding: 6px 10px; display: flex; align-items: center; justify-content: space-between; font-size: 11px;" data-kind="${kind}" data-index="${index}">
                <div style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; flex: 1; padding-right: 4px;">
                    <span>${isItem ? "📦" : "💧"}</span> ${TreeUtils.escape(name)}
                </div>
                <div class="node-actions" style="display: flex; gap: 2px; flex-shrink: 0;">
                    <button class="node-action-btn act-details" title="Details" data-kind="${kind}" data-index="${index}" style="padding: 2px;">ℹ️</button>
                    <button class="node-action-btn act-root" title="Set as root" data-kind="${kind}" data-index="${index}" style="padding: 2px;">🎯</button>
                </div>
            </div>
        `;
    }).join("");

    craftContainer.innerHTML = `
        <div class="sankey-container" style="min-height: ${sankeyHeight}px; width: 100%; overflow: auto; position: relative; background-color: var(--bg-panel);">
            <div style="width: ${sankeyWidth}px; height: ${sankeyHeight}px; position: relative;">
                <svg class="sankey-flow-svg" style="width: ${sankeyWidth}px; height: ${sankeyHeight}px;">
                    ${linksHtml}
                </svg>
                <div style="position: absolute; top: 0; left: 0; width: ${sankeyWidth}px; height: ${sankeyHeight}px; pointer-events: none;">
                    ${nodesHtml}
                </div>
            </div>
        </div>
    `;
}
