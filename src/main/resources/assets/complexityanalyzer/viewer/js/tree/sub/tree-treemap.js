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

export function renderTreemapTree(craftContainer, treeData) {
    const allNodes = [];
    const seen = new Set();

    function collect(node) {
        const key = `${node.kind}:${node.index}`;
        if (!seen.has(key)) {
            seen.add(key);
            allNodes.push(node);
        }
        if (node.children) for (const c of node.children) collect(c);
    }

    collect(treeData);

    allNodes.sort((a, b) => (b.complexity?.rawComplexity || 0) - (a.complexity?.rawComplexity || 0));

    const totalComplexity = allNodes.reduce((acc, n) => acc + (n.complexity?.rawComplexity || 0), 0) || 1;

    craftContainer.innerHTML = `
        <div class="treemap-container">
            ${allNodes.map((n) => {
        const pct = Math.max(1, Math.round(((n.complexity?.rawComplexity || 0) / totalComplexity) * 100));
        let span = "span 3";
        if (pct > 30) span = "span 6";
        else if (pct > 12) span = "span 4";
        const catName = n.categoryName || "Uncalculable";

        return `
                    <div class="treemap-tile cat-${catName}" style="grid-column: ${span};">
                       <div class="treemap-percentage">${pct}%</div>
                       <div>
                           <div style="font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-dim); margin-bottom: 4px;">
                               ${catName === "Uncalculable" ? "Primary Resources" : catName}
                           </div>
                           <div style="font-weight: 700; font-size: 14px; color: var(--text-bright); display: flex; align-items: center; gap: 6px;">
                               <span>${n.kind === "item" ? "📦" : "💧"}</span>
                               <span>${TreeUtils.escape(n.name)}</span>
                           </div>
                       </div>
                       
                       <div style="margin-top: 12px; display: flex; align-items: flex-end; justify-content: space-between;">
                           <div style="font-size: 11px; color: var(--text-dim);">
                               Complexity: <strong style="color: var(--accent);">${TreeUtils.formatComplexity(n.complexity)}</strong>
                           </div>
                           ${TreeUtils.renderActionsHTML(n, false)}
                       </div>
                   </div>
                `;
    }).join("")}
        </div>
    `;
}
