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

export function renderHorizontalTree(craftContainer, treeData) {
    function buildHtml(node) {
        const hasChildren = node.children && node.children.length > 0 && !node.collapsed;
        const catName = node.categoryName || "Uncalculable";

        const cardHtml = `
            <div class="craft-tree-node-card cat-${catName}" data-kind="${node.kind}" data-index="${node.index}" data-depth="${node.depth}" style="margin: 0; min-width: 240px; max-width: 300px;">
                <div class="node-content-main">
                    <span style="font-size: 13px;">${node.kind === "item" ? "📦" : "💧"}</span>
                    ${node.amountText ? `<span class="node-amount">${node.amountText}</span>` : ""}
                    <span class="node-name" title="${TreeUtils.escape(node.name)}">${TreeUtils.escape(node.name)}</span>
                </div>
                <div class="node-meta-right">
                    <span class="node-complexity cat-${catName}">
                        ${TreeUtils.formatComplexity(node.complexity)}
                    </span>
                    ${TreeUtils.renderActionsHTML(node, true)}
                </div>
            </div>
        `;

        if (hasChildren) {
            return `
                <div class="horiz-node-wrapper">
                    ${cardHtml}
                    <div class="horiz-children">
                        ${node.children.map(child => buildHtml(child)).join("")}
                    </div>
                </div>
            `;
        } else {
            return `
                <div class="horiz-node-wrapper no-children">
                    ${cardHtml}
                </div>
            `;
        }
    }

    craftContainer.innerHTML = `
        <div style="width: 100%; height: 100%; overflow: auto; display: flex; align-items: center; justify-content: flex-start; padding: 20px 40px;">
            <div class="horiz-container">
                ${buildHtml(treeData)}
            </div>
        </div>
    `;
}
