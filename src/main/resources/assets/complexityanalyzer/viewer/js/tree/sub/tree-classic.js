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
        isLastChildAtDepth
    });

    if (node.collapsed || !node.children?.length) return;

    node.children.forEach((child, i, arr) => {
        const isLast = i === arr.length - 1;
        flattenTree(child, [...isLastChildAtDepth, isLast], resultList);
    });
}

function renderConnectors(depth, path) {
    if (depth <= 0) return "";

    let html = "";
    for (let lvl = 0; lvl < depth - 1; lvl++) {
        html += path[lvl]
            ? '<div class="craft-tree-connector"></div>'
            : '<div class="craft-tree-connector craft-tree-connector-line"></div>';
    }

    html += path[depth - 1]
        ? '<div class="craft-tree-connector craft-tree-connector-corner" title="└──"></div>'
        : '<div class="craft-tree-connector craft-tree-connector-junction" title="├──"></div>';

    return html;
}

export function renderClassicTree(craftContainer, treeData) {
    const list = [];
    flattenTree(treeData, [], list);

    const rowsHtml = list.map(({node: n, isLastChildAtDepth: path}) => {
        const catName = n.categoryName || "Uncalculable";
        const escapedName = TreeUtils.escape(n.name);
        const connectors = renderConnectors(n.depth, path);

        return `
            <div class="craft-tree-row">
                ${connectors}
                <div class="craft-tree-node-card cat-${catName}" data-kind="${n.kind}" data-index="${n.index}" data-depth="${n.depth}">
                    <div class="node-content-main">
                        <span style="font-size: 13px;" title="${n.type}">${TreeUtils.getNodeIcon(n)}</span>
                        ${n.amountText ? `<span class="node-amount">${n.amountText}</span>` : ""}
                        <span class="node-name" title="${escapedName}">${escapedName}</span>
                    </div>
                    <div class="node-meta-right">
                        ${TreeUtils.renderMachineBadgeHTML(n)}
                        <span class="node-complexity cat-${catName}">
                            ${TreeUtils.formatComplexity(n.complexity)}
                        </span>
                        ${TreeUtils.renderActionsHTML(n, true)}
                    </div>
                </div>
            </div>
        `;
    }).join("");

    craftContainer.innerHTML = `<div style="display: flex; flex-direction: column;">${rowsHtml}</div>`;
}
