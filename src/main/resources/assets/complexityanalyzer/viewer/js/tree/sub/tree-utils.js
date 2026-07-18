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

import {escapeHtml, formatComplexityDetail} from "../../core/utils.js";

export class TreeUtils {
    static escape(str) {
        return escapeHtml(str);
    }

    static formatComplexity(complexity) {
        return formatComplexityDetail(complexity);
    }

    static renderActionsHTML(node, showToggle = true) {
        const hasAnyChildren = node.children && node.children.length > 0;
        const isCollapsed = node.collapsed;
        return `
            <div class="node-actions">
                <button class="node-action-btn act-details" title="Details" data-kind="${node.kind}" data-index="${node.index}">
                    ℹ️
                </button>
                <button class="node-action-btn act-root" title="Set as root" data-kind="${node.kind}" data-index="${node.index}">
                    🎯
                </button>
                ${showToggle && hasAnyChildren ? `
                    <button class="node-action-btn act-toggle" title="${isCollapsed ? "Expand" : "Collapse"}" data-depth="${node.depth}" data-kind="${node.kind}" data-index="${node.index}" style="font-weight: bold; width: 18px; font-size: 14px;">
                        ${isCollapsed ? "＋" : "－"}
                    </button>
                ` : ""}
            </div>
        `;
    }

    static setupPanZoom(viewport, canvas, stateObj, onUpdate) {
        let isDragging = false;
        let startX, startY;

        viewport.addEventListener("mousedown", e => {
            if (e.target.closest(".node-actions") || e.target.closest(".web-node-badge") || e.target.closest(".craft-tree-node-card")) return;
            isDragging = true;
            viewport.style.cursor = "grabbing";
            startX = e.clientX - stateObj.panX;
            startY = e.clientY - stateObj.panY;
        });

        window.addEventListener("mousemove", e => {
            if (!isDragging) return;
            stateObj.panX = e.clientX - startX;
            stateObj.panY = e.clientY - startY;
            canvas.style.transform = `translate(${stateObj.panX}px, ${stateObj.panY}px) scale(${stateObj.zoom})`;
            if (onUpdate) onUpdate();
        });

        window.addEventListener("mouseup", () => {
            isDragging = false;
            viewport.style.cursor = "grab";
        });

        viewport.addEventListener("wheel", e => {
            e.preventDefault();
            const rect = canvas.getBoundingClientRect();
            const mouseX = e.clientX - rect.left;
            const mouseY = e.clientY - rect.top;

            const zoomFactor = 1.1;
            let nextZoom;
            if (e.deltaY < 0) {
                nextZoom = Math.min(2.0, stateObj.zoom * zoomFactor);
            } else {
                nextZoom = Math.max(0.3, stateObj.zoom / zoomFactor);
            }

            stateObj.panX = e.clientX - viewport.getBoundingClientRect().left - mouseX * (nextZoom / stateObj.zoom);
            stateObj.panY = e.clientY - viewport.getBoundingClientRect().top - mouseY * (nextZoom / stateObj.zoom);
            stateObj.zoom = nextZoom;

            canvas.style.transform = `translate(${stateObj.panX}px, ${stateObj.panY}px) scale(${stateObj.zoom})`;
            if (onUpdate) onUpdate();
        }, {passive: false});
    }

    static setupNodeHoverActions(viewport) {
        viewport.querySelectorAll(".web-node-element").forEach(el => {
            const badge = el.querySelector(".web-node-badge");
            const actions = el.querySelector(".node-actions");
            if (!badge || !actions) return;

            badge.addEventListener("mouseenter", () => {
                actions.style.display = "flex";
            });

            el.addEventListener("mouseleave", () => {
                actions.style.display = "none";
            });
        });
    }
}
