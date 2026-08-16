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

import {escapeHtml, fmt, formatComplexityDetail} from "../../core/utils.js";
import {NodeType} from "./tree-builder.js";

const NODE_ICONS = {
    [NodeType.BASE_RESOURCE]: "⛏",
    [NodeType.CYCLE]: "🔁",
    [NodeType.NO_DATA]: "❌"
};

export class TreeUtils {
    static escape = escapeHtml;
    static formatComplexity = formatComplexityDetail;

    static getNodeIcon(node) {
        if (node.kind === "fluid") return "💧";
        return NODE_ICONS[node.type] || "🔨";
    }

    static renderMachineBadgeHTML(node) {
        if (!node.machineName || node.type === NodeType.BASE_RESOURCE || node.type === NodeType.CYCLE) return "";

        const hasAmort = node.amortization > 0;
        const amortText = hasAmort ? `+${fmt.format(node.amortization)}` : "";
        const escapedName = escapeHtml(node.machineName);
        const title = `Machine: ${escapedName}${hasAmort ? ` (Amortization: ${amortText})` : ""}`;

        return `
            <div class="node-machine-badge" title="${title}" style="display: inline-flex; align-items: center; gap: 4px; padding: 2px 6px; background: rgba(255, 255, 255, 0.03); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 4px; font-size: 11px; max-width: 200px; flex-shrink: 0;">
                <span style="font-size: 11px; opacity: 0.7;">🏭</span>
                <span style="color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500;">${escapedName}</span>
                ${hasAmort ? `<span style="color: var(--accent); font-family: var(--mono), monospace; font-size: 10px; font-weight: 600; padding-left: 4px; margin-left: 2px; border-left: 1px solid rgba(255,255,255,0.1);">${amortText}</span>` : ""}
            </div>
        `;
    }

    static renderActionsHTML(node, showToggle = true) {
        const canToggle = node.type === NodeType.CRAFTING && (node.children?.length > 0 || node.collapsed);
        const isCollapsed = node.collapsed;

        return `
            <div class="node-actions">
                <button class="node-action-btn act-details" title="Details" data-kind="${node.kind}" data-index="${node.index}">
                    ℹ️
                </button>
                <button class="node-action-btn act-root" title="Set as root" data-kind="${node.kind}" data-index="${node.index}">
                    🎯
                </button>
                ${showToggle && canToggle ? `
                    <button class="node-action-btn act-toggle" title="${isCollapsed ? "Expand" : "Collapse"}" data-uid="${node.uid}" style="font-weight: bold; width: 18px; font-size: 14px;">
                        ${isCollapsed ? "＋" : "－"}
                    </button>
                ` : ""}
            </div>
        `;
    }
}
