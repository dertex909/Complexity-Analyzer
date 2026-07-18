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

import {TreeUtils} from "./tree-utils.js";

export function renderRadialTree(craftContainer, treeData, webState, onUpdate) {
    const radialNodes = [];
    const radialLinks = [];

    function layoutConcentric(node, depth, minAngle, maxAngle) {
        const key = `${node.kind}:${node.index}`;
        const radius = depth * 110;
        const midAngle = (minAngle + maxAngle) / 2;

        const x = 500 + radius * Math.cos(midAngle);
        const y = 350 + radius * Math.sin(midAngle);

        const radialNode = {node, x, y, angle: midAngle, key};
        radialNodes.push(radialNode);

        if (node.children && node.children.length > 0 && !node.collapsed) {
            const count = node.children.length;
            const angleSpan = maxAngle - minAngle;
            const step = angleSpan / count;

            for (let i = 0; i < count; i++) {
                const child = node.children[i];
                const childMin = minAngle + i * step;
                const childMax = childMin + step;

                const childLayout = layoutConcentric(child, depth + 1, childMin, childMax);
                radialLinks.push({from: radialNode, to: childLayout});
            }
        }
        return radialNode;
    }

    layoutConcentric(treeData, 0, 0, Math.PI * 2);

    const linksHtml = radialLinks.map(link => {
        const mx = (link.from.x + link.to.x) / 2;
        const my = (link.from.y + link.to.y) / 2;
        return `<path class="radial-link" d="M ${link.from.x} ${link.from.y} Q ${mx} ${my} ${link.to.x} ${link.to.y}" />`;
    }).join("");

    const nodesHtml = radialNodes.map(rn => {
        const n = rn.node;
        const hasAnyChildren = n.children && n.children.length > 0;
        const isCollapsed = n.collapsed;
        const rotationDeg = (rn.angle * 180 / Math.PI) % 360;
        const textRotation = (rn.angle > Math.PI / 2 && rn.angle < 3 * Math.PI / 2) ? rotationDeg + 180 : rotationDeg;
        const catName = n.categoryName || "Uncalculable";

        return `
            <div class="web-node-element" style="left: ${rn.x}px; top: ${rn.y}px;" data-key="${rn.key}">
                <div class="web-node-badge cat-${catName}" data-kind="${n.kind}" data-index="${n.index}">
                    <span style="font-size: 14px;">${n.kind === "item" ? "📦" : "💧"}</span>
                </div>
                <div class="web-node-label" style="transform: translate(-50%, -100%) rotate(${textRotation}deg); top: -12px;">
                    ${n.amountText ? `<span style="color: var(--accent); margin-right: 2px;">${n.amountText}</span>` : ""}
                    ${TreeUtils.escape(n.name)}
                </div>
                
                <div class="node-actions" style="position: absolute; top: -35px; left: 50%; transform: translateX(-50%); background-color: var(--bg-panel); padding: 4px; border-radius: 6px; border: 1px solid var(--border); display: none; gap: 4px; z-index: 10; box-shadow: var(--shadow-md);">
                    <button class="node-action-btn act-details" title="Details" data-kind="${n.kind}" data-index="${n.index}">ℹ️</button>
                    <button class="node-action-btn act-root" title="Set as root" data-kind="${n.kind}" data-index="${n.index}">🎯</button>
                    ${hasAnyChildren ? `
                        <button class="node-action-btn act-toggle" title="${isCollapsed ? "Expand" : "Collapse"}" data-depth="${n.depth}" data-kind="${n.kind}" data-index="${n.index}" style="font-weight: bold;">
                            ${isCollapsed ? "＋" : "－"}
                        </button>
                    ` : ""}
                </div>
            </div>
        `;
    }).join("");

    craftContainer.innerHTML = `
        <div style="font-size: 11px; color: var(--text-dim); margin-bottom: 8px; display: flex; justify-content: space-between; align-items: center; padding: 0 4px;">
            <span>Drag to Pan • Scroll to Zoom • Radial Symmetrical View</span>
            <button class="btn" id="btn-reset-radial-view" style="font-size: 11px; padding: 2px 8px;">Reset View</button>
        </div>
        <div class="radial-container" id="radial-viewport">
            <div id="radial-canvas" style="position: absolute; width: 1000px; height: 700px; transform-origin: 0 0; transform: translate(${webState.panX}px, ${webState.panY}px) scale(${webState.zoom});">
                <svg style="position: absolute; top: 0; left: 0; width: 1000px; height: 700px; pointer-events: none;">
                    ${linksHtml}
                </svg>
                ${nodesHtml}
            </div>
        </div>
    `;

    const viewport = craftContainer.querySelector("#radial-viewport");
    const canvas = craftContainer.querySelector("#radial-canvas");

    TreeUtils.setupPanZoom(viewport, canvas, webState, onUpdate);
    TreeUtils.setupNodeHoverActions(viewport);

    craftContainer.querySelector("#btn-reset-radial-view").addEventListener("click", () => {
        webState.panX = 0;
        webState.panY = 0;
        webState.zoom = 1.0;
        canvas.style.transform = `translate(0px, 0px) scale(1)`;
        if (onUpdate) onUpdate();
    });
}
