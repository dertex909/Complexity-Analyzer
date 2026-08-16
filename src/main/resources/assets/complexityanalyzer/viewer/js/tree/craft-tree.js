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

import {state} from "../core/state.js";
import {escapeHtml} from "../core/utils.js";
import {renderItemDetail} from "../views/details/item-detail.js";
import {renderFluidDetail} from "../views/details/fluid-detail.js";

import {TreeBuilder} from "./sub/tree-builder.js";
import {renderLandingHub} from "./sub/tree-landing.js";
import {saveRecentItem} from "./sub/tree-recents.js";
import {renderClassicTree} from "./sub/tree-classic.js";
import {renderHorizontalTree} from "./sub/tree-horizontal.js";
import {renderPipelineTree} from "./sub/tree-pipeline.js";

let selectedRoot = null;
let treeData = null;
let selectedFormat = "classic";
let treeBuilder = null;

function getFormatDescription(format) {
    switch (format) {
        case "classic":
            return "Standard hierarchical list structure with step connectors. Clean, responsive, and supports node collapsing.";
        case "horizontal":
            return "Horizontal tree layout spreading left-to-right. Ideal for mapping complex ingredient branching visually.";
        case "pipeline":
            return "Assembly conveyor system. Groups operations into chronological stages, from raw inputs up to the target product.";
        default:
            return "Select a layout above to visualize the production chain.";
    }
}

export async function renderCraftTreeView(container) {
    const db = state.db;
    if (!db) {
        container.innerHTML = `<div class="craft-tree-empty"><div class="craft-tree-empty-title">Database not loaded</div></div>`;
        return;
    }

    if (!treeBuilder || treeBuilder.db !== db) treeBuilder = new TreeBuilder(db);

    if (window.lastSelectedCraftNode) {
        selectedRoot = window.lastSelectedCraftNode;
        window.lastSelectedCraftNode = null;
        treeData = null;
    } else if (!selectedRoot && state.selectedItem >= 0) {
        selectedRoot = {
            kind: state.tab === "fluids" ? "fluid" : "item",
            index: state.selectedItem
        };
        treeData = null;
    }

    drawLayout();

    function drawLayout() {
        if (!selectedRoot) {
            renderLandingHub(
                container,
                db,
                selectedFormat,
                (kind, index) => {
                    selectedRoot = {kind, index};
                    treeData = null;
                    drawLayout();
                },
                (format) => {
                    selectedFormat = format;
                }
            );
        } else {
            const rootName = selectedRoot.kind === "item"
                ? db.items.get(selectedRoot.index)?.name || "Item"
                : db.fluids.get(selectedRoot.index)?.name || "Fluid";

            container.innerHTML = `
                <div class="craft-tree-layout">
                    <div class="craft-tree-header" style="justify-content: space-between;">
                        <div style="display: flex; align-items: center; gap: 16px; flex: 1;">
                            <button class="btn" id="btn-back-search" style="display: flex; align-items: center; gap: 6px; padding: 6px 12px; font-size: 13px;">
                                ← Back to search
                            </button>
                            <span style="font-weight: 600; color: var(--text-bright); font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 300px;">
                                ${escapeHtml(rootName)}
                            </span>
                        </div>
                        <div class="craft-search-container" style="width: 250px; position: relative; flex: 0 0 auto;">
                            <input type="text" class="craft-search-input" placeholder="Quick jump..." autocomplete="off" style="font-size: 13px; padding: 6px 12px; border-radius: 6px; background-color: var(--bg-raised); border: 1px solid var(--border); width: 100%; color: var(--text);">
                            <div class="craft-search-results" id="craft-search-results" hidden style="max-height: 250px; width: 100%;"></div>
                        </div>
                    </div>
                    <div style="display: flex; flex: 1; min-height: 0; width: 100%;">
                        <div class="craft-tree-viewport" id="craft-viewport" style="flex: 1; overflow: auto; position: relative;">
                            <div class="craft-tree-container" id="craft-container"></div>
                        </div>
                        
                        <div class="craft-tree-sidebar" style="width: 280px; flex-shrink: 0; border-left: 1px solid var(--border); background-color: var(--bg-panel); display: flex; flex-direction: column; overflow-y: auto; padding: 20px; box-sizing: border-box; gap: 16px;">
                            <h3 style="font-size: 14px; font-weight: 700; color: var(--text-bright); margin: 0; display: flex; align-items: center; gap: 8px;">
                                <span>🛠️</span> Tree Layout
                            </h3>
                            <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 4px;">
                                <label style="font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-dim); font-weight: 700;">Layout Format</label>
                                
                                <button class="format-select-btn btn ${selectedFormat === "classic" ? "active" : ""}" data-format="classic" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">📋</span> Classic Tree
                                </button>
                                
                                <button class="format-select-btn btn ${selectedFormat === "horizontal" ? "active" : ""}" data-format="horizontal" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">🌿</span> Horizontal Hierarchy
                                </button>
                                
                                <button class="format-select-btn btn ${selectedFormat === "pipeline" ? "active" : ""}" data-format="pipeline" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">⚙️</span> Vertical Conveyor
                                </button>
                            </div>
                            
                            <div id="format-description-box" style="padding: 12px; border-radius: 6px; background-color: rgba(92,217,154,0.03); border: 1px solid rgba(92,217,154,0.1); font-size: 12px; line-height: 1.5; color: var(--text-dim);">
                                ${getFormatDescription(selectedFormat)}
                            </div>
                        </div>
                    </div>
                </div>
            `;

            initHeaderControls();
            void renderTree();
        }
    }

    function initHeaderControls() {
        const btnBack = container.querySelector("#btn-back-search");
        if (btnBack) {
            btnBack.addEventListener("click", () => {
                selectedRoot = null;
                treeData = null;
                drawLayout();
            });
        }

        container.querySelectorAll(".format-select-btn").forEach(btn => {
            btn.addEventListener("click", () => {
                const format = btn.dataset.format;
                selectedFormat = format;

                container.querySelectorAll(".format-select-btn").forEach(b => {
                    b.classList.toggle("active", b.dataset.format === format);
                });

                const descBox = container.querySelector("#format-description-box");
                if (descBox) descBox.innerHTML = getFormatDescription(format);

                void renderTree();
            });
        });

        const jumpInput = container.querySelector(".craft-search-input");
        const jumpResults = container.querySelector("#craft-search-results");
        if (jumpInput && jumpResults) {
            jumpInput.addEventListener("input", () => {
                const q = jumpInput.value.trim().toLowerCase();
                if (q.length < 2) {
                    jumpResults.hidden = true;
                    return;
                }
                const matched = [];
                for (let i = 0; i < db.items.count && matched.length < 30; i++) {
                    const it = db.items.get(i);
                    if (it && (it.name + " " + it.id).toLowerCase().includes(q)) matched.push({
                        kind: "item",
                        index: i,
                        name: it.name
                    });
                }
                for (let i = 0; i < db.fluids.count && matched.length < 50; i++) {
                    const fl = db.fluids.get(i);
                    if (fl && (fl.name + " " + fl.id).toLowerCase().includes(q)) matched.push({
                        kind: "fluid",
                        index: i,
                        name: fl.name
                    });
                }
                if (matched.length > 0) {
                    jumpResults.innerHTML = matched.map(m => `
                        <div class="craft-search-item" data-kind="${m.kind}" data-index="${m.index}" style="padding: 6px 10px; font-size: 12px; cursor: pointer;">
                            <span class="name">${escapeHtml(m.name)}</span>
                        </div>
                    `).join("");
                    jumpResults.hidden = false;
                } else {
                    jumpResults.hidden = true;
                }
            });

            jumpResults.addEventListener("click", (e) => {
                const it = e.target.closest(".craft-search-item");
                if (!it) return;
                const kind = it.dataset.kind;
                const index = parseInt(it.dataset.index, 10);
                saveRecentItem(kind, index, db);
                selectedRoot = {kind, index};
                treeData = null;
                drawLayout();
            });
        }
    }

    async function renderTree() {
        const craftContainer = container.querySelector("#craft-container");
        if (!craftContainer) return;

        if (!treeData) {
            craftContainer.innerHTML = `<div style="padding: 32px; color: var(--text-dim); text-align: center;">Building tree...</div>`;
            treeData = await treeBuilder.buildRoot(selectedRoot.kind, selectedRoot.index, 2);
        }

        if (selectedFormat === "classic") {
            renderClassicTree(craftContainer, treeData);
        } else if (selectedFormat === "horizontal") {
            renderHorizontalTree(craftContainer, treeData);
        } else if (selectedFormat === "pipeline") {
            renderPipelineTree(craftContainer, treeData);
        }

        bindTreeEvents(craftContainer);
    }

    function bindTreeEvents(craftContainer) {
        craftContainer.querySelectorAll(".act-details").forEach(btn => {
            btn.addEventListener("click", () => {
                const kind = btn.dataset.kind;
                const index = parseInt(btn.dataset.index, 10);
                if (kind === "item") {
                    const modal = document.getElementById("item-modal");
                    if (modal) {
                        modal.hidden = false;
                        renderItemDetail(null, index);
                    }
                } else {
                    const modal = document.getElementById("fluid-modal");
                    if (modal) {
                        modal.hidden = false;
                        renderFluidDetail(null, index);
                    }
                }
            });
        });

        craftContainer.querySelectorAll(".act-root").forEach(btn => {
            btn.addEventListener("click", () => {
                const kind = btn.dataset.kind;
                const index = parseInt(btn.dataset.index, 10);
                saveRecentItem(kind, index, db);
                selectedRoot = {kind, index};
                treeData = null;
                drawLayout();
            });
        });

        craftContainer.querySelectorAll(".act-toggle").forEach(btn => {
            btn.addEventListener("click", async () => {
                const uid = btn.dataset.uid;
                const node = treeBuilder.nodeMap.get(uid);
                if (!node) return;

                if (node.collapsed) {
                    if (!node.children || node.children.length === 0) await treeBuilder.expandNode(node);
                    node.collapsed = false;
                } else {
                    node.collapsed = true;
                }

                void renderTree();
            });
        });
    }
}