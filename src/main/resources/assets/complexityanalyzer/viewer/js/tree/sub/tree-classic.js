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

function flattenTree(node, isLastChildAtDepth, resultList) {
    resultList.push({
        node,
        isLastChildAtDepth: [...isLastChildAtDepth]
    });

    if (!node.collapsed && node.children && node.children.length > 0) for (let i = 0; i < node.children.length; i++) {
        const child = node.children[i];
        const isLast = (i === node.children.length - 1);
        flattenTree(child, [...isLastChildAtDepth, isLast], resultList);
    }
}

export function renderClassicTree(craftContainer, treeData) {
    const list = [];
    flattenTree(treeData, [], list);

    craftContainer.innerHTML = `
        <div style="display: flex; flex-direction: column;">
            ${list.map(item => {
        const n = item.node;
        const d = n.depth;
        const path = item.isLastChildAtDepth;

        let connectorHtml = "";
        if (d > 0) {
            for (let lvl = 0; lvl < d - 1; lvl++) {
                const isLast = path[lvl];
                if (isLast) {
                    connectorHtml += `<div class="craft-tree-connector"></div>`;
                } else {
                    connectorHtml += `<div class="craft-tree-connector craft-tree-connector-line"></div>`;
                }
            }
            const isDirectLast = path[d - 1];
            if (isDirectLast) {
                connectorHtml += `<div class="craft-tree-connector craft-tree-connector-corner" title="└──"></div>`;
            } else {
                connectorHtml += `<div class="craft-tree-connector craft-tree-connector-junction" title="├──"></div>`;
            }
        }

        const catName = n.categoryName || "Uncalculable";

        return `
                    <div class="craft-tree-row">
                        ${connectorHtml}
                        <div class="craft-tree-node-card cat-${catName}" data-kind="${n.kind}" data-index="${n.index}" data-depth="${d}">
                            <div class="node-content-main">
                                <span style="font-size: 13px;">${n.kind === "item" ? "📦" : "💧"}</span>
                                ${n.amountText ? `<span class="node-amount">${n.amountText}</span>` : ""}
                                <span class="node-name" title="${TreeUtils.escape(n.name)}">${TreeUtils.escape(n.name)}</span>
                            </div>
                            <div class="node-meta-right">
                                <span class="node-complexity cat-${catName}">
                                    ${TreeUtils.formatComplexity(n.complexity)}
                                </span>
                                ${TreeUtils.renderActionsHTML(n, true)}
                            </div>
                        </div>
                    </div>
                `;
    }).join("")}
        </div>
    `;
}
