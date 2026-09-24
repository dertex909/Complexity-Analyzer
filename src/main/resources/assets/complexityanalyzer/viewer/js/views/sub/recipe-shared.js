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

import {escapeHtml, formatComplexity} from "../../core/utils.js";
import {mountVirtualList} from "../../components/virtual-list.js";
import {
    getRecipeUnitCost,
    getSelectedOutputComplexity,
    mergeDuplicateRecipes,
    resolveBestMachine,
    sortRecipes
} from "./recipe/recipe-calc.js";
import {renderRecipeRow} from "./recipe/recipe-row.js";
import {
    restoreScrollPositions,
    saveScrollPositions,
    wireRecipeControls,
    wireRecipeEventsDelegated
} from "./recipe/recipe-events.js";

export * from "./recipe/recipe-calc.js";
export * from "./recipe/recipe-row.js";
export * from "./recipe/recipe-events.js";

export function renderRecipeControlsHtml(activeSort, filteredCount, totalCount = filteredCount, searchQuery = "") {
    const isFiltered = filteredCount !== totalCount;
    const countText = isFiltered ? `${filteredCount} / ${totalCount} recipe(s)` : `${totalCount} recipe(s)`;

    return `
        <div class="controls" style="margin-bottom: 8px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; background: var(--bg-panel); padding: 8px 12px; border: 1px solid var(--border); border-radius: 4px; flex-shrink: 0;">
            <input type="search" id="recipes-search" placeholder="Filter recipes by name or ID…" value="${escapeHtml(searchQuery)}" autocomplete="off" style="min-width: 200px; flex: 1 1 200px; max-width: 380px; padding: 5px 10px; font-size: 12px; border-radius: 4px; background: var(--bg-raised); color: var(--text); border: 1px solid var(--border);">
            <div style="display: flex; align-items: center; gap: 6px;">
                <span style="font-size: 11px; color: var(--text-dim); text-transform: uppercase; font-weight: 500; letter-spacing: 1px;">Sort:</span>
                <select id="recipes-sort" style="font-size: 12px; padding: 4px 8px; border-radius: 4px; background: var(--bg-raised); color: var(--text); border: 1px solid var(--border);">
                    <option value="optimal" ${activeSort === "optimal" ? "selected" : ""}>Optimal (Primary first)</option>
                    <option value="cheapest" ${activeSort === "cheapest" ? "selected" : ""}>Cheapest first</option>
                </select>
            </div>
            <span class="flex-grow"></span>
            <span class="chip" style="font-size: 11px; padding: 2px 8px;">${countText}</span>
        </div>
    `;
}

export function renderAndWireGroupedRecipes(body, sorted, db, onSortChange, emptyMessage) {
    const scrollState = saveScrollPositions(body);

    if (body._activeVList) {
        body._activeVList.destroy();
        body._activeVList = null;
    }
    body.classList.remove("has-virtual");

    if (!body._customState) {
        body._customState = {
            selectedMachines: new Map(),
            selectedIngredients: new Map(),
            searchQuery: ""
        };
    }

    const merged = mergeDuplicateRecipes(sorted);
    const activeSort = localStorage.getItem("recipes-sort") || "optimal";
    const resorted = sortRecipes(merged, db, activeSort);

    const machineToRecipes = new Map();
    for (const r of resorted) {
        const activeMachineIdx = resolveBestMachine(r, db, body);
        if (!machineToRecipes.has(activeMachineIdx)) machineToRecipes.set(activeMachineIdx, []);
        machineToRecipes.get(activeMachineIdx).push(r);
    }

    if (machineToRecipes.size === 0) {
        body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">${escapeHtml(emptyMessage)}</div></div>`;
        return;
    }

    const getMinMetric = (recipes, metricFn) => {
        let minVal = Infinity;
        for (const r of recipes) {
            const v = metricFn(r);
            if (v < minVal) minVal = v;
        }
        return minVal;
    };

    const sortedMachines = Array.from(machineToRecipes.keys()).sort((a, b) => {
        const itemComp = getSelectedOutputComplexity(db);

        if (activeSort === "optimal" && itemComp !== null) {
            const diffA = getMinMetric(machineToRecipes.get(a) || [], r => Math.abs(getRecipeUnitCost(r, db, null) - itemComp));
            const diffB = getMinMetric(machineToRecipes.get(b) || [], r => Math.abs(getRecipeUnitCost(r, db, null) - itemComp));
            if (Math.abs(diffA - diffB) > 0.001) return diffA - diffB;
        } else if (activeSort === "cheapest") {
            const costA = getMinMetric(machineToRecipes.get(a) || [], r => getRecipeUnitCost(r, db, null));
            const costB = getMinMetric(machineToRecipes.get(b) || [], r => getRecipeUnitCost(r, db, null));
            if (Math.abs(costA - costB) > 0.001) return costA - costB;
        }

        const itemA = a >= 0 ? db.items.get(a) : null;
        const itemB = b >= 0 ? db.items.get(b) : null;
        if (!itemA && itemB) return 1;
        if (itemA && !itemB) return -1;
        return a - b;
    });

    const groupsHtml = sortedMachines.map(mi => {
        const mItem = mi >= 0 ? db.items.get(mi) : null;
        const mRecipes = machineToRecipes.get(mi);
        const isRaw = !mItem;

        let nameHtml;
        if (mItem) {
            const isMUncalc = (mItem.flags & 0x10) || mItem.categoryName === "Uncalculable";
            const mComp = mItem.complexity;
            const mTooltip = `Complexity: ${isMUncalc || mComp === -1 || !isFinite(mComp) ? 'Uncalculable' : (typeof formatComplexity === 'function' ? formatComplexity(mComp) : mComp)}`;
            const mStyle = isMUncalc ? "color: #fca5a5 !important;" : "color:var(--accent);";
            nameHtml = `<strong style="${mStyle} cursor:pointer;" class="machine-link" data-index="${mi}" title="${mTooltip}">${escapeHtml(mItem.name)}</strong>`;
        } else {
            nameHtml = `<strong style="color:#ef4444; margin-right: 8px;">Unknown Machine (${escapeHtml(mRecipes[0].recipeType || "Code")})</strong>`;
        }

        const idHtml = mItem ? `<span class="mono-code" style="font-size:11px;">${escapeHtml(mItem.id)}</span>` : ``;

        return `
            <div class="detail-card ${isRaw ? 'raw-craft-card' : ''}" style="margin-bottom: 16px;">
                <div style="display:flex; justify-content:space-between; align-items:center; border-bottom: 1px solid ${isRaw ? 'rgba(239, 68, 68, 0.2)' : 'var(--border)'}; padding-bottom: 8px; margin-bottom: 8px;">
                    <span style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
                        ${nameHtml}
                        ${idHtml}
                    </span>
                    <span class="chip" style="${isRaw ? 'background: rgba(239, 68, 68, 0.15); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.2);' : ''}">${mRecipes.length} recipe(s)</span>
                </div>
                ${mRecipes.map(r => renderRecipeRow(r, db, body)).join("")}
            </div>
        `;
    }).join("");

    body.innerHTML = `
        <div style="flex: 0 0 auto;">
            ${renderRecipeControlsHtml(activeSort, merged.length, merged.length, "")}
        </div>
        <div class="recipes-grouped-list" style="flex: 1 1 auto; overflow-y: auto; padding-right: 4px;">
            ${groupsHtml}
        </div>
    `;

    wireRecipeControls(body, onSortChange);
    wireRecipeEventsDelegated(body, body, onSortChange);

    restoreScrollPositions(scrollState);
    requestAnimationFrame(() => {
        restoreScrollPositions(scrollState);
    });
}

export function renderAndWireFlatRecipes(body, recipes, db, onSortChange, machineOverride = null) {
    const activeSort = localStorage.getItem("recipes-sort") || "optimal";

    if (!body._customState) body._customState = {
        selectedMachines: new Map(),
        selectedIngredients: new Map(),
        searchQuery: ""
    };

    const prevScrollTop = body._activeVList ? body._activeVList.getScrollTop() : 0;
    if (body._activeVList) {
        body._activeVList.destroy();
        body._activeVList = null;
    }

    body.classList.add("has-virtual");

    const merged = mergeDuplicateRecipes(recipes);
    const sorted = sortRecipes(merged, db, activeSort);

    const q = (body._customState.searchQuery || "").trim().toLowerCase();
    let displayRecipes = sorted;

    if (q) {
        displayRecipes = sorted.filter(r => {
            if (r.itemOutputs) {
                for (const o of r.itemOutputs) {
                    const it = db.items.get(o.itemIndex);
                    if (it && ((it.name || "").toLowerCase().includes(q) || (it.id || "").toLowerCase().includes(q))) return true;
                }
            }
            if (r.fluidOutputs) {
                for (const o of r.fluidOutputs) {
                    const fl = db.fluids.get(o.fluidIndex);
                    if (fl && ((fl.name || "").toLowerCase().includes(q) || (fl.id || "").toLowerCase().includes(q))) return true;
                }
            }
            if (r.outputItemIndex >= 0) {
                const it = db.items.get(r.outputItemIndex);
                if (it && ((it.name || "").toLowerCase().includes(q) || (it.id || "").toLowerCase().includes(q))) return true;
            }
            if (r.ingredients) {
                for (const slot of r.ingredients) {
                    if (slot.variants) {
                        for (const v of slot.variants) {
                            const it = db.items.get(v);
                            if (it && ((it.name || "").toLowerCase().includes(q) || (it.id || "").toLowerCase().includes(q))) return true;
                        }
                    }
                }
            }
            if (r.fluidIngredients) {
                for (const slot of r.fluidIngredients) {
                    if (slot.variants) {
                        for (const v of slot.variants) {
                            const fl = db.fluids.get(v);
                            if (fl && ((fl.name || "").toLowerCase().includes(q) || (fl.id || "").toLowerCase().includes(q))) return true;
                        }
                    }
                }
            }
            if (r.recipeType && r.recipeType.toLowerCase().includes(q)) return true;
            if (r.machineItemIndex >= 0) {
                const m = db.items.get(r.machineItemIndex);
                if (m && ((m.name || "").toLowerCase().includes(q) || (m.id || "").toLowerCase().includes(q))) return true;
            }
            return false;
        });
    }

    body.innerHTML = `
        ${renderRecipeControlsHtml(activeSort, displayRecipes.length, merged.length, body._customState.searchQuery || "")}
        <div class="recipes-virtual-container"></div>
    `;

    wireRecipeControls(body, onSortChange);

    const listContainer = body.querySelector(".recipes-virtual-container");
    if (!listContainer) return;

    if (displayRecipes.length === 0) {
        listContainer.innerHTML = `
            <div class="empty-state" style="padding: 40px 0;">
                <div class="icon">∅</div>
                <div class="message">${q ? "No recipes match your search." : "No recipes found."}</div>
            </div>`;
        return;
    }

    body._activeVList = mountVirtualList(listContainer, {
        itemCount: displayRecipes.length,
        itemHeight: 90,
        dynamicHeight: true,
        overscan: 5,
        initialScrollTop: prevScrollTop,
        renderRow: (index) => {
            const r = displayRecipes[index];
            const rowWrapper = document.createElement("div");
            rowWrapper.className = "virtual-recipe-row";
            rowWrapper.innerHTML = renderRecipeRow(r, db, body, machineOverride);
            return rowWrapper;
        }
    });

    wireRecipeEventsDelegated(listContainer, body, onSortChange);
}