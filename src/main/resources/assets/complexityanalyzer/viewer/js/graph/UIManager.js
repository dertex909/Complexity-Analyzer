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

import {getCatColor} from './utils.js';
import {DEFAULT_CAT_COLORS} from './constants.js';

export class UIManager {
    constructor(container, controller) {
        this.container = container;
        this.controller = controller;
        this.overlay = container.querySelector("#graph-overlay");
    }

    initialize() {
        if (!this.overlay) return;

        this.createUI();
        this.attachListeners();
    }

    createUI() {
        this.overlay.innerHTML = `
            <div id="graph-search-hud" style="
                position: absolute;
                top: 16px;
                left: 16px;
                pointer-events: auto;
                width: 280px;
                display: flex;
                flex-direction: column;
                gap: 4px;
                z-index: 10;
            ">
                <input type="search" id="graph-search-box" placeholder="Search item in graph..." style="
                    width: 100%;
                    box-sizing: border-box;
                    background: rgba(17, 21, 28, 0.88);
                    border: 1px solid var(--border);
                    padding: 8px 12px;
                    font-size: 11.5px;
                    color: var(--text);
                    backdrop-filter: blur(8px);
                ">
                <div id="graph-search-dropdown" style="
                    display: none;
                    max-height: 200px;
                    overflow-y: auto;
                    background: rgba(17, 21, 28, 0.95);
                    border: 1px solid var(--border);
                    backdrop-filter: blur(8px);
                    flex-direction: column;
                    box-shadow: var(--shadow-lg);
                "></div>
            </div>

            <div class="card" style="
                position: absolute;
                top: 16px;
                right: 16px;
                bottom: 16px;
                pointer-events: auto;
                padding: 16px;
                width: 280px;
                background: rgba(17, 21, 28, 0.88);
                border: 1px solid var(--border);
                box-shadow: var(--shadow-lg);
                font-family: var(--sans), sans-serif;
                display: flex;
                flex-direction: column;
                gap: 12px;
                backdrop-filter: blur(8px);
                z-index: 10;
            ">
                <div style="border-bottom: 1px solid var(--border-light); padding-bottom: 8px;">
                    <h3 style="margin: 0; color: var(--accent); font-size: 14px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase;">Production Web Graph</h3>
                    <div style="font-size: 10.5px; color: var(--text-dim); margin-top: 4px;">Dynamic GPU WebGL relation map</div>
                </div>

                <div style="display: flex; justify-content: space-between; font-size: 11px; background: rgba(0,0,0,0.18); padding: 8px 10px; border: 1px solid rgba(255,255,255,0.03);">
                    <div>Nodes: <strong id="hud-nodes-count" style="color: var(--text);">0</strong></div>
                    <div>Links: <strong id="hud-links-count" style="color: var(--text);">0</strong></div>
                </div>

                <div style="border: 1px solid var(--border); background: rgba(10,12,16,0.4); padding: 10px; min-height: 72px;">
                    <div style="font-size: 9.5px; color: var(--text-muted); font-weight: 600; text-transform: uppercase; margin-bottom: 4px; letter-spacing: 0.05em;">Active Node Inspector</div>
                    <div id="graph-hud-hover-info">
                        <div style="font-size: 11px; color: var(--text-muted); text-align: center; padding: 12px 0;">
                            Hover item to see recipe connection chains
                        </div>
                    </div>
                </div>

                <div style="display: flex; flex-direction: column; gap: 8px; flex: 1; overflow-y: auto; padding-right: 2px;">
                    
                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">View Mode:</span>
                        <select id="graph-opt-viewmode" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="focused">Focused Craft Chain</option>
                            <option value="global">Full Network Graph</option>
                        </select>
                    </div>

                    <div id="layout-select-container" style="display: none; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Graph Layout:</span>
                        <select id="graph-opt-layout" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="pipeline">Pipeline Flow (Horizontal)</option>
                            <option value="radial">Radial Web (Concentric)</option>
                            <option value="category">Category Clusters (Islands)</option>
                        </select>
                    </div>

                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Connection Lines:</span>
                        <select id="graph-opt-edges" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="smart">Smart Routing (Anti-Collision)</option>
                            <option value="orthogonal">Orthogonal Blueprint</option>
                            <option value="curved">Flowing Bezier</option>
                            <option value="straight">Straight Vector</option>
                        </select>
                    </div>

                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Hover Highlight Mode:</span>
                        <select id="graph-opt-highlight" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="chain">Full Craft Chain (Recursive)</option>
                            <option value="direct">Direct Neighbors Only</option>
                        </select>
                    </div>

                    <div style="display: flex; flex-direction: column; gap: 3px;">
                        <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; font-weight: 600; letter-spacing: 0.05em;">Highlight Category:</span>
                        <select id="graph-opt-catfilter" style="width: 100%; font-size: 11px; background: var(--bg-raised); border: 1px solid var(--border); color: var(--text); padding: 5px;">
                            <option value="All">Show All Categories</option>
                            ${Object.keys(DEFAULT_CAT_COLORS).map(cat => `
                                <option value="${cat}">${cat}</option>
                            `).join("")}
                        </select>
                    </div>

                </div>

                <button id="recenter-graph-btn" style="
                    background: var(--bg-hover);
                    border: 1px solid var(--border);
                    color: var(--text);
                    padding: 8px;
                    cursor: pointer;
                    font-size: 11px;
                    text-align: center;
                    width: 100%;
                    transition: background var(--transition-fast);
                    font-weight: 600;
                    text-transform: uppercase;
                    letter-spacing: 0.02em;
                    flex-shrink: 0;
                ">Recenter Viewport</button>
            </div>
        `;
    }

    attachListeners() {
        const recenterBtn = this.overlay.querySelector("#recenter-graph-btn");
        if (recenterBtn) {
            recenterBtn.addEventListener("click", () => this.controller.fitToView(true));
            recenterBtn.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const viewModeSelector = this.overlay.querySelector("#graph-opt-viewmode");
        const layoutContainer = this.overlay.querySelector("#layout-select-container");
        if (viewModeSelector) {
            viewModeSelector.value = this.controller.viewMode;
            viewModeSelector.addEventListener("change", e => {
                this.controller.viewMode = e.target.value;
                localStorage.setItem("ca_graph_viewmode", e.target.value);
                if (layoutContainer) layoutContainer.style.display = e.target.value === "global" ? "flex" : "none";
                this.controller.applyLayout();
                this.controller.fitToView(true);
                this.updateStats();
            });
            viewModeSelector.addEventListener("pointerdown", e => e.stopPropagation());

            if (layoutContainer && this.controller.viewMode === "global") layoutContainer.style.display = "flex";
        }

        const layoutSelector = this.overlay.querySelector("#graph-opt-layout");
        if (layoutSelector) {
            layoutSelector.value = this.controller.layoutStyle;
            layoutSelector.addEventListener("change", e => {
                this.controller.layoutStyle = e.target.value;
                localStorage.setItem("ca_graph_layout", e.target.value);
                this.controller.applyLayout();
                this.controller.fitToView(true);
            });
            layoutSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const edgeSelector = this.overlay.querySelector("#graph-opt-edges");
        if (edgeSelector) {
            edgeSelector.value = this.controller.edgeStyle;
            edgeSelector.addEventListener("change", e => {
                this.controller.edgeStyle = e.target.value;
                localStorage.setItem("ca_graph_edges", e.target.value);
                this.controller.uploadGeometry();
                this.controller.needsRedraw = true;
            });
            edgeSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const highlightSelector = this.overlay.querySelector("#graph-opt-highlight");
        if (highlightSelector) {
            highlightSelector.value = this.controller.highlightMode;
            highlightSelector.addEventListener("change", e => {
                this.controller.highlightMode = e.target.value;
                localStorage.setItem("ca_graph_highlight", e.target.value);
                this.controller.updateHighlights();
            });
            highlightSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        const catFilterSelector = this.overlay.querySelector("#graph-opt-catfilter");
        if (catFilterSelector) {
            catFilterSelector.addEventListener("change", e => {
                this.controller.selectedCategoryFilter = e.target.value;
                this.controller.needsRedraw = true;
            });
            catFilterSelector.addEventListener("pointerdown", e => e.stopPropagation());
        }

        this.setupSearch();
        this.updateStats();
    }

    setupSearch() {
        const searchBox = this.overlay.querySelector("#graph-search-box");
        const searchDropdown = this.overlay.querySelector("#graph-search-dropdown");

        if (!searchBox || !searchDropdown) return;

        searchBox.addEventListener("input", () => {
            const q = searchBox.value.trim().toLowerCase();
            if (q.length < 2) {
                searchDropdown.style.display = "none";
                searchDropdown.innerHTML = "";
                return;
            }

            const matches = this.controller.graphData.activeNodes
                .filter(n => n.name.toLowerCase().includes(q) || n.id.toString().includes(q))
                .slice(0, 15);

            if (matches.length > 0) {
                searchDropdown.style.display = "flex";
                searchDropdown.innerHTML = matches.map(m => `
                    <div class="search-item" data-id="${m.id}" style="
                        padding: 6px 10px;
                        cursor: pointer;
                        border-bottom: 1px solid rgba(255, 255, 255, 0.03);
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        font-size: 11px;
                    ">
                        <span style="font-weight: 500; color: var(--text);">${m.name}</span>
                        <span style="font-size: 9.5px; color: ${getCatColor(m.category)}; font-weight: 600; text-transform: uppercase;">${m.category}</span>
                    </div>
                `).join("");
            } else {
                searchDropdown.style.display = "flex";
                searchDropdown.innerHTML = `
                    <div style="padding: 8px; font-size: 11px; color: var(--text-muted); text-align: center;">No matches in graph</div>
                `;
            }
        });

        searchDropdown.addEventListener("click", e => {
            const item = e.target.closest(".search-item");
            if (!item) return;

            const nodeId = parseInt(item.dataset.id, 10);
            this.controller.focusOnNode(nodeId);
            this.updateStats();
            this.updateHoverDetails(this.controller.graphData.getNode(nodeId));

            searchDropdown.style.display = "none";
            searchBox.value = "";
        });

        searchBox.addEventListener("pointerdown", e => e.stopPropagation());
        searchDropdown.addEventListener("pointerdown", e => e.stopPropagation());

        window.addEventListener("click", ev => {
            if (!ev.target.closest("#graph-search-hud")) searchDropdown.style.display = "none";
        });
    }

    updateHoverDetails(node) {
        const detailSection = this.overlay.querySelector("#graph-hud-hover-info");
        if (!detailSection) return;

        if (!node) {
            detailSection.innerHTML = `
                <div style="font-size: 11px; color: var(--text-muted); text-align: center; padding: 12px 0;">
                    Hover item to see recipe connection chains
                </div>
            `;
            return;
        }

        const catColor = getCatColor(node.category);
        detailSection.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 6px;">
                <div style="display: flex; align-items: center; gap: 8px;">
                    <div style="width: 8px; height: 8px; border-radius: 50%; background: ${catColor}; box-shadow: 0 0 6px ${catColor};"></div>
                    <strong style="color: var(--text); font-size: 13px; font-weight: 600;">${node.name}</strong>
                </div>
                <div style="font-size: 10px; color: var(--text-dim); display: grid; grid-template-columns: 80px 1fr; gap: 4px;">
                    <span>Category:</span><span style="color: ${catColor}; font-weight: 500;">${node.category}</span>
                    <span>Craft Depth:</span><span style="color: var(--text); font-family: var(--mono);">${node.depth}</span>
                    <span>Complexity:</span><span style="color: var(--accent); font-family: var(--mono); font-weight: 600;">${node.complexity === -1 ? "Infinite" : node.complexity}</span>
                    <span>Total Links:</span><span style="color: var(--text); font-family: var(--mono);">${node.degree}</span>
                </div>
            </div>
        `;
    }

    updateStats() {
        const nodesCountEl = this.overlay.querySelector("#hud-nodes-count");
        const linksCountEl = this.overlay.querySelector("#hud-links-count");

        if (nodesCountEl && linksCountEl) {
            const nodesCount = this.controller.getVisibleNodeSet().length;
            const linksCount = this.controller.getVisibleEdgeSet().length;
            nodesCountEl.textContent = nodesCount;
            linksCountEl.textContent = linksCount;
        }
    }
}
