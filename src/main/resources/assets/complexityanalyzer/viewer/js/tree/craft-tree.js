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

import {renderClassicTree} from "./sub/tree-classic.js";
import {renderHorizontalTree} from "./sub/tree-horizontal.js";
import {renderPipelineTree} from "./sub/tree-pipeline.js";
import {renderNestedTree} from "./sub/tree-nested.js";
import {renderWebTree} from "./sub/tree-web.js";
import {renderRadialTree} from "./sub/tree-radial.js";
import {renderSankeyTree} from "./sub/tree-sankey.js";
import {renderTreemapTree} from "./sub/tree-treemap.js";

let selectedRoot = null;
let treeData = null;
let nodeMap = new Map();
let selectedFormat = "classic";
let webPanX = 0;
let webPanY = 0;
let webZoom = 1.0;

const webState = {
    get panX() {
        return webPanX;
    }, set panX(v) {
        webPanX = v;
    }, get panY() {
        return webPanY;
    }, set panY(v) {
        webPanY = v;
    }, get zoom() {
        return webZoom;
    }, set zoom(v) {
        webZoom = v;
    }
};

function getFormatDescription(format) {
    switch (format) {
        case "classic":
            return "Standard hierarchical list structure with step connectors. Clean, responsive, and supports node collapsing.";
        case "horizontal":
            return "Horizontal tree layout spreading left-to-right. Ideal for mapping complex ingredient branching visually.";
        case "pipeline":
            return "Assembly conveyor system. Groups operations into chronological stages, from raw inputs up to the target product.";
        case "nested":
            return "Ultra-compact box-in-box module nesting. Shows child components physically nested inside their parent modules.";
        case "web":
            return "Fully interactive 2D production network. Nodes fan out radially, linked by animated material flow lines.";
        case "radial":
            return "Radial Dendrogram. Arranges components circularly around the root, demonstrating elegant symmetric fanout of deep ingredients.";
        case "sankey":
            return "Sankey Flow Chart. Represents the relative input quantities flowing through processors to visualize mass balance.";
        case "treemap":
            return "Compact Treemap. Visualizes recipe density and nesting. The relative surface area corresponds to total ingredient complexity.";
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

    drawMainLayout();

    function drawMainLayout() {
        if (!selectedRoot) {
            container.innerHTML = `
                <div class="craft-tree-layout" style="justify-content: center; align-items: center; min-height: 100%;">
                    <div class="craft-tree-empty" style="width: 100%; max-width: 600px; padding: 24px; box-sizing: border-box; display: flex; flex-direction: column; align-items: center; justify-content: center;">
                        <div style="font-size: 56px; margin-bottom: 12px; filter: drop-shadow(0 0 10px rgba(92,217,154,0.2));">🌿</div>
                        <div class="craft-tree-empty-title" style="margin-bottom: 8px; font-size: 24px; font-weight: 700; color: var(--text-bright); text-align: center;">Visual Craft Tree</div>
                        <div style="max-width: 460px; font-size: 13px; line-height: 1.6; margin-bottom: 24px; text-align: center; color: var(--text-dim);">
                            Enter the name of an item or fluid below to build the craft tree.
                        </div>
                        
                        <div class="craft-search-container" style="width: 100%; max-width: 500px; margin-bottom: 24px; position: relative;">
                            <input type="text" class="craft-search-input" placeholder="Search for an item or fluid..." autocomplete="off" style="font-size: 15px; padding: 12px 18px; border-radius: 8px; background-color: var(--bg-raised); border: 1px solid var(--border); width: 100%; color: var(--text);">
                            <div class="craft-search-results" id="craft-search-results" hidden style="max-height: 250px;"></div>
                        </div>

                        <div class="craft-tree-empty-suggestions" style="margin-top: 8px;">
                        </div>
                    </div>
                </div>
            `;

            const suggestionsContainer = container.querySelector(".craft-tree-empty-suggestions");
            const dbItems = [];
            for (let i = 0; i < Math.min(db.items.count, 200); i++) {
                const it = db.items.get(i);
                if (it && it.complexity > 50 && !(it.flags & 0x10)) {
                    dbItems.push({kind: "item", name: it.name, id: it.id, index: i});
                }
            }
            dbItems.sort(() => Math.random() - 0.5);
            const extraSugs = dbItems.slice(0, 4);

            if (extraSugs.length > 0 && suggestionsContainer) {suggestionsContainer.innerHTML = extraSugs.map(s => `
                    <button class="craft-tree-suggestion-pill" data-kind="${s.kind}" data-index="${s.index}">
                        🔍 ${escapeHtml(s.name)}
                    </button>
                `).join("");
            }

            initSearchEvents();
            initSuggestionEvents();
        } else {
            let rootName;
            if (selectedRoot.kind === "item") {
                rootName = db.items.get(selectedRoot.index)?.name || "Item";
            } else {
                rootName = db.fluids.get(selectedRoot.index)?.name || "Fluid";
            }

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
                        <div class="craft-search-container" style="width: 250px; position: relative;">
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
                                
                                <button class="format-select-btn btn ${selectedFormat === "nested" ? "active" : ""}" data-format="nested" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">🗂️</span> Compact Nested
                                </button>
                                
                                <button class="format-select-btn btn ${selectedFormat === "web" ? "active" : ""}" data-format="web" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">🕸️</span> Production Web
                                </button>

                                <button class="format-select-btn btn ${selectedFormat === "radial" ? "active" : ""}" data-format="radial" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">🔆</span> Radial Dendrogram
                                </button>

                                <button class="format-select-btn btn ${selectedFormat === "sankey" ? "active" : ""}" data-format="sankey" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">📊</span> Sankey Flow
                                </button>

                                <button class="format-select-btn btn ${selectedFormat === "treemap" ? "active" : ""}" data-format="treemap" style="text-align: left; padding: 10px 12px; display: flex; align-items: center; gap: 10px; font-size: 13px; width: 100%;">
                                    <span style="font-size: 14px;">🍱</span> Treemap Enclosure
                                </button>
                            </div>
                            
                            <div id="format-description-box" style="padding: 12px; border-radius: 6px; background-color: rgba(92,217,154,0.03); border: 1px solid rgba(92,217,154,0.1); font-size: 12px; line-height: 1.5; color: var(--text-dim);">
                                ${getFormatDescription(selectedFormat)}
                            </div>
                        </div>
                    </div>
                </div>
            `;

            initSearchEvents();
            initTreeControls();
            renderTree();
        }
    }

    function initSearchEvents() {
        const searchInput = container.querySelector(".craft-search-input");
        const searchResults = container.querySelector("#craft-search-results");

        if (!searchInput || !searchResults) return;

        searchInput.addEventListener("input", () => {
            const query = searchInput.value.trim().toLowerCase();
            if (query.length < 2) {
                searchResults.hidden = true;
                return;
            }

            const matched = [];
            for (let i = 0; i < db.items.count && matched.length < 50; i++) {
                const it = db.items.get(i);
                if (it && (it.name + " " + it.id).toLowerCase().includes(query)) {
                    matched.push({kind: "item", name: it.name, id: it.id, index: i});
                }
            }
            for (let i = 0; i < db.fluids.count && matched.length < 100; i++) {
                const fl = db.fluids.get(i);
                if (fl && (fl.name + " " + fl.id).toLowerCase().includes(query)) {
                    matched.push({kind: "fluid", name: fl.name, id: fl.id, index: i});
                }
            }

            if (matched.length > 0) {
                searchResults.innerHTML = matched.map(m => `
                    <div class="craft-search-item" data-kind="${m.kind}" data-index="${m.index}">
                        <span class="name">${escapeHtml(m.name)}</span>
                        <span class="kind kind-${m.kind}">${m.kind}</span>
                    </div>
                `).join("");
                searchResults.hidden = false;
            } else {
                searchResults.innerHTML = `<div style="padding: 12px; color: var(--text-dim); text-align: center;">No results found</div>`;
                searchResults.hidden = false;
            }
        });

        const closeOnOutsideClick = e => {
            if (!e.target.closest(".craft-search-container")) searchResults.hidden = true;
        };
        document.addEventListener("click", closeOnOutsideClick);
        container.addEventListener("destroy", () => {
            document.removeEventListener("click", closeOnOutsideClick);
        });

        searchResults.addEventListener("click", e => {
            const item = e.target.closest(".craft-search-item");
            if (!item) return;

            const kind = item.dataset.kind;
            const index = parseInt(item.dataset.index, 10);

            selectedRoot = {kind, index};
            treeData = null;
            searchResults.hidden = true;
            searchInput.value = "";

            drawMainLayout();
        });
    }

    function initSuggestionEvents() {
        container.querySelectorAll(".craft-tree-suggestion-pill").forEach(btn => {
            btn.addEventListener("click", () => {
                const kind = btn.dataset.kind;
                const index = parseInt(btn.dataset.index, 10);
                selectedRoot = {kind, index};
                treeData = null;
                drawMainLayout();
            });
        });
    }

    function initTreeControls() {
        const btnBack = container.querySelector("#btn-back-search");
        if (btnBack) {
            btnBack.addEventListener("click", () => {
                selectedRoot = null;
                treeData = null;
                drawMainLayout();
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

                renderTree();
            });
        });
    }

    async function buildTreeData() {
        if (!selectedRoot) return null;
        nodeMap.clear();
        return await buildNode(db, selectedRoot.kind, selectedRoot.index, 0, new Set());
    }

    async function buildNode(db, kind, index, depth, path) {
        const key = `${kind}:${index}`;
        const mapKey = `${depth}_${key}`;

        const isCircular = path.has(key);
        const node = {
            kind,
            index,
            depth,
            isCircular,
            collapsed: nodeMap.get(mapKey)?.collapsed ?? false,
            children: []
        };

        nodeMap.set(mapKey, node);

        if (kind === "item") {
            const it = db.items.get(index);
            if (it) {
                node.name = it.name;
                node.id = it.id;
                node.complexity = it.complexity;
                node.categoryName = it.categoryName;
            }
        } else {
            const fl = db.fluids.get(index);
            if (fl) {
                node.name = fl.name;
                node.id = fl.id;
                node.complexity = fl.complexity;
                node.categoryName = fl.categoryName;
            }
        }

        if (isCircular || depth >= 15) return node;

        const newPath = new Set(path);
        newPath.add(key);

        try {
            let recipes;
            if (kind === "item") {
                recipes = await db.getItemRecipes(index);
            } else {
                recipes = await db.getFluidRecipes(index);
            }

            if (recipes && recipes.length > 0) {
                const nodeComplexity = node.complexity;
                if (recipes.length > 1 && typeof nodeComplexity === "number" && nodeComplexity > 0) {
                    const isReverseRecipe = async (rec) => {
                        if (rec.ingredients) for (const slot of rec.ingredients) {
                            if (slot.variants) for (const v of slot.variants) {
                                const it = db.items.get(v);
                                if (it && it.complexity !== -1 && it.complexity > nodeComplexity + 0.1) {
                                    const base = await db.getItemBaseData(v);
                                    const isOre = base && base.sourceType === "complexityanalyzer.source_type.block_transformation";
                                    if (!isOre) return true;
                                }
                            }
                        }
                        if (rec.fluidIngredients) for (const slot of rec.fluidIngredients) {
                            if (slot.variants) for (const v of slot.variants) {
                                const fl = db.fluids.get(v);
                                if (fl && fl.complexity !== -1 && fl.complexity > nodeComplexity + 0.1) return true;
                            }
                        }
                        return false;
                    };

                    const recipeStatuses = await Promise.all(recipes.map(async (rec) => {
                        const isRev = await isReverseRecipe(rec);
                        return {rec, isRev};
                    }));

                    recipeStatuses.sort((a, b) => {
                        if (a.isRev !== b.isRev) return a.isRev ? 1 : -1;
                        return 0;
                    });

                    recipes = recipeStatuses.map(x => x.rec);
                }

                const bestRecipe = recipes[0];
                node.recipe = bestRecipe;

                const itemObj = kind === "item" ? db.items.get(index) : null;
                const isBaseResource = kind === "item" && itemObj && itemObj.sourceCount > 0;
                const isNodeCompValid = typeof node.complexity === "number" && node.complexity >= 0;

                if (isBaseResource && isNodeCompValid) {
                    let isMiningRecipe = false;
                    if (bestRecipe.ingredients) for (const slot of bestRecipe.ingredients) {
                        if (slot.variants) for (const v of slot.variants) {
                            const base = await db.getItemBaseData(v);
                            if (base && base.sourceType === "complexityanalyzer.source_type.block_transformation") {
                                isMiningRecipe = true;
                                break;
                            }
                        }
                        if (isMiningRecipe) break;
                    }

                    let anyIngredientMoreExpensive = false;
                    let totalIngredientsComplexity = 0;

                    if (bestRecipe.ingredients) for (const slot of bestRecipe.ingredients) {
                        if (slot.variants && slot.variants.length > 0) {
                            let minComp = Infinity;
                            for (const v of slot.variants) {
                                const it = db.items.get(v);
                                if (it && it.complexity !== -1 && it.complexity < minComp) minComp = it.complexity;
                            }
                            if (minComp !== Infinity) {
                                totalIngredientsComplexity += minComp * (slot.count || 1);
                                if (minComp > node.complexity) anyIngredientMoreExpensive = true;
                            }
                        }
                    }

                    if (bestRecipe.fluidIngredients) for (const slot of bestRecipe.fluidIngredients) {
                        if (slot.variants && slot.variants.length > 0) {
                            let minComp = Infinity;
                            for (const v of slot.variants) {
                                const fl = db.fluids.get(v);
                                if (fl && fl.complexity !== -1 && fl.complexity < minComp) minComp = fl.complexity;
                            }
                            if (minComp !== Infinity) {
                                totalIngredientsComplexity += minComp * (slot.count || 1);
                                if (minComp > node.complexity) anyIngredientMoreExpensive = true;
                            }
                        }
                    }

                    const isCraftMoreExpensive = anyIngredientMoreExpensive || (totalIngredientsComplexity > node.complexity);

                    if (isCraftMoreExpensive && !isMiningRecipe) return node;
                }

                const seen = new Set();

                if (bestRecipe.ingredients) for (const slot of bestRecipe.ingredients) {
                    if (slot.variants && slot.variants.length > 0) {
                        let bestVariant = slot.variants[0];
                        let minComp = Infinity;
                        for (const v of slot.variants) {
                            const it = db.items.get(v);
                            if (it && it.complexity !== -1 && it.complexity < minComp) {
                                minComp = it.complexity;
                                bestVariant = v;
                            }
                        }

                        const childKey = `item:${bestVariant}`;
                        if (!seen.has(childKey)) {
                            seen.add(childKey);
                            const child = await buildNode(db, "item", bestVariant, depth + 1, newPath);
                            child.amountText = slot.count + "×";
                            node.children.push(child);
                        }
                    }
                }

                if (bestRecipe.fluidIngredients) for (const slot of bestRecipe.fluidIngredients) {
                    if (slot.variants && slot.variants.length > 0) {
                        let bestVariant = slot.variants[0];
                        let minComp = Infinity;
                        for (const v of slot.variants) {
                            const fl = db.fluids.get(v);
                            if (fl && fl.complexity !== -1 && fl.complexity < minComp) {
                                minComp = fl.complexity;
                                bestVariant = v;
                            }
                        }

                        const childKey = `fluid:${bestVariant}`;
                        if (!seen.has(childKey)) {
                            seen.add(childKey);
                            const child = await buildNode(db, "fluid", bestVariant, depth + 1, newPath);
                            child.amountText = (slot.amount >= 1000 ? (slot.amount / 1000) + "B" : slot.amount + "mB");
                            node.children.push(child);
                        }
                    }
                }
            }
        } catch (e) {
            console.error("Failed to fetch recipes for tree node:", e);
        }

        return node;
    }

    async function renderTree() {
        const craftContainer = container.querySelector("#craft-container");
        if (!craftContainer) return;

        if (!treeData) {
            craftContainer.innerHTML = `<div style="padding: 32px; color: var(--text-dim); text-align: center;">Building tree...</div>`;
            treeData = await buildTreeData();
        }

        if (selectedFormat === "classic") {
            renderClassicTree(craftContainer, treeData);
        } else if (selectedFormat === "horizontal") {
            renderHorizontalTree(craftContainer, treeData);
        } else if (selectedFormat === "pipeline") {
            renderPipelineTree(craftContainer, treeData);
        } else if (selectedFormat === "nested") {
            renderNestedTree(craftContainer, treeData);
        } else if (selectedFormat === "web") {
            renderWebTree(craftContainer, treeData, webState, () => {
            });
        } else if (selectedFormat === "radial") {
            renderRadialTree(craftContainer, treeData, webState, () => {
            });
        } else if (selectedFormat === "sankey") {
            renderSankeyTree(craftContainer, treeData);
        } else if (selectedFormat === "treemap") {
            renderTreemapTree(craftContainer, treeData);
        }

        bindNodeEvents(craftContainer);
    }

    function bindNodeEvents(craftContainer) {
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
                selectedRoot = {kind, index};
                treeData = null;
                drawMainLayout();
            });
        });

        craftContainer.querySelectorAll(".act-toggle").forEach(btn => {
            btn.addEventListener("click", () => {
                const kind = btn.dataset.kind;
                const index = parseInt(btn.dataset.index, 10);
                const depth = parseInt(btn.dataset.depth, 10);
                const key = `${depth}_${kind}:${index}`;

                const node = nodeMap.get(key);
                if (node) {
                    node.collapsed = !node.collapsed;
                    renderTree();
                }
            });
        });
    }
}
