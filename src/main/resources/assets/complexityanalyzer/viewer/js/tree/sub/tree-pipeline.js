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

export function renderPipelineTree(craftContainer, treeData) {
    const stages = new Map();
    let maxDepth = 0;

    function traverse(node, depth) {
        if (depth > maxDepth) maxDepth = depth;
        const key = `${node.kind}:${node.index}`;
        const existing = stages.get(key);
        if (!existing || depth > existing.depth) stages.set(key, {node, depth});
        if (node.children) for (const c of node.children) traverse(c, depth + 1);
    }

    traverse(treeData, 0);

    const buckets = [];
    for (let i = 0; i <= maxDepth; i++) {
        buckets.push([]);
    }
    for (const [key, val] of stages.entries()) {
        const stageIndex = maxDepth - val.depth;
        buckets[stageIndex].push(val.node);
    }

    let pipelineHtml = `<div class="pipeline-container">`;

    for (let i = 0; i <= maxDepth; i++) {
        const nodes = buckets[i];
        if (nodes.length === 0) continue;

        let stageTitle = "";
        if (i === 0) {
            stageTitle = "Stage 1: Primary Resources";
        } else if (i === maxDepth) {
            stageTitle = `Stage ${i + 1}: Final Synthesis & Assembly`;
        } else {
            stageTitle = `Stage ${i + 1}: Intermediate Processors`;
        }

        pipelineHtml += `
            <div class="pipeline-stage">
                <div class="pipeline-stage-header">
                    <span class="pipeline-stage-title">${stageTitle}</span>
                    <span class="pipeline-stage-badge">${nodes.length} Item${nodes.length > 1 ? "s" : ""}</span>
                </div>
                <div class="pipeline-nodes-grid">
                    ${nodes.map(n => {
            const catName = n.categoryName || "Uncalculable";
            return `
                            <div class="craft-tree-node-card cat-${catName}" data-kind="${n.kind}" data-index="${n.index}" data-depth="${n.depth}" style="margin: 0; width: 100%;">
                                <div class="node-content-main">
                                    <span style="font-size: 13px;">${n.kind === "item" ? "📦" : "💧"}</span>
                                    ${n.amountText ? `<span class="node-amount">${n.amountText}</span>` : ""}
                                    <span class="node-name" title="${TreeUtils.escape(n.name)}">${TreeUtils.escape(n.name)}</span>
                                </div>
                                <div class="node-meta-right">
                                    <span class="node-complexity cat-${catName}">
                                        ${TreeUtils.formatComplexity(n.complexity)}
                                    </span>
                                    ${TreeUtils.renderActionsHTML(n, false)}
                                </div>
                            </div>
                        `;
        }).join("")}
                </div>
            </div>
        `;

        if (i < maxDepth) pipelineHtml += `
                <div class="pipeline-arrow">
                    ⚙️ Conveyor Flow ↓
                </div>
            `;
    }

    pipelineHtml += `</div>`;
    craftContainer.innerHTML = pipelineHtml;
}
