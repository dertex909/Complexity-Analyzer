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
    getRecipeSearchString,
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
            <span class="chip" id="recipes-count-chip" style="font-size: 11px; padding: 2px 8px;">${countText}</span>
        </div>
    `;
}

export function ensureRecipeLayout(body, activeSort, filteredCount, totalCount, searchQuery, onSortChange, onSearchChange) {
    let controlsWrapper = body.querySelector(".recipe-controls-wrapper");
    let resultsWrapper = body.querySelector(".recipe-results-wrapper");

    const isFiltered = filteredCount !== totalCount;
    const countText = isFiltered ? `${filteredCount} / ${totalCount} recipe(s)` : `${totalCount} recipe(s)`;

    if (!controlsWrapper || !resultsWrapper) {
        body.innerHTML = `
            <div class="recipe-controls-wrapper" style="flex: 0 0 auto;">
                ${renderRecipeControlsHtml(activeSort, filteredCount, totalCount, searchQuery)}
            </div>
            <div class="recipe-results-wrapper" style="flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column;"></div>
        `;
        controlsWrapper = body.querySelector(".recipe-controls-wrapper");
        resultsWrapper = body.querySelector(".recipe-results-wrapper");

        wireRecipeControls(controlsWrapper, onSortChange, onSearchChange);
    } else {
        const countChip = controlsWrapper.querySelector("#recipes-count-chip");
        if (countChip) countChip.textContent = countText;

        const searchInput = controlsWrapper.querySelector("#recipes-search");
        if (searchInput && document.activeElement !== searchInput && searchInput.value !== searchQuery) {
            searchInput.value = searchQuery;
        }

        const sortSelect = controlsWrapper.querySelector("#recipes-sort");
        if (sortSelect && sortSelect.value !== activeSort) sortSelect.value = activeSort;
    }

    return resultsWrapper;
}

export function renderAndWireGroupedRecipes(body, sorted, db, onSortChange, emptyMessage = "No crafting recipes found.") {
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

    const updateGroupedView = (shouldResetScroll = false) => {
        const q = (body._customState.searchQuery || "").trim().toLowerCase();
        let displayRecipes = resorted;
        if (q) displayRecipes = resorted.filter(r => getRecipeSearchString(r, db).includes(q));

        const isFiltered = displayRecipes.length !== merged.length;
        const countText = isFiltered ? `${displayRecipes.length} / ${merged.length} recipe(s)` : `${merged.length} recipe(s)`;

        const countChip = body.querySelector("#recipes-count-chip");
        if (countChip) countChip.textContent = countText;

        const resultsWrapper = body.querySelector(".recipe-results-wrapper");
        if (!resultsWrapper) return;

        if (displayRecipes.length === 0) {
            resultsWrapper.innerHTML = `
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">🔍</div>
                    <div class="message">${q ? "No recipes match your search." : escapeHtml(emptyMessage)}</div>
                </div>`;
            return;
        }

        const machineToRecipes = new Map();
        for (let i = 0; i < displayRecipes.length; i++) {
            const r = displayRecipes[i];
            const activeMachineIdx = resolveBestMachine(r, db, body);
            if (!machineToRecipes.has(activeMachineIdx)) machineToRecipes.set(activeMachineIdx, []);
            machineToRecipes.get(activeMachineIdx).push(r);
        }

        const getMinMetric = (recipes, metricFn) => {
            let minVal = Infinity;
            for (let i = 0; i < recipes.length; i++) {
                const v = metricFn(recipes[i]);
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
                const mTooltip = `Complexity: ${isMUncalc || mComp === -1 || !isFinite(mComp) ? 'Uncalculable' : formatComplexity(mComp)}`;
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

        resultsWrapper.innerHTML = `
            <div class="recipes-grouped-list" style="flex: 1 1 auto; overflow-y: auto; padding-right: 4px;">
                ${groupsHtml}
            </div>
        `;

        if (shouldResetScroll) {
            const listEl = resultsWrapper.querySelector(".recipes-grouped-list");
            if (listEl) listEl.scrollTop = 0;
        }
    };

    ensureRecipeLayout(
        body,
        activeSort,
        merged.length,
        merged.length,
        body._customState.searchQuery || "",
        () => {
            onSortChange();
        },
        (newQuery) => {
            body._customState.searchQuery = newQuery;
            updateGroupedView(true);
        }
    );

    updateGroupedView(false);

    if (!body._hasRecipeEvents) {
        body._hasRecipeEvents = true;
        wireRecipeEventsDelegated(body, body, () => {
            if (typeof body._onSortChangeRef === 'function') body._onSortChangeRef();
        });
    }
    body._onSortChangeRef = onSortChange;

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
        searchQuery: "",
        sortedCache: null,
        lastSortType: null,
        lastRecipesRef: null
    };

    body.classList.add("has-virtual");

    let sorted = body._customState.sortedCache;
    if (!sorted || body._customState.lastSortType !== activeSort || body._customState.lastRecipesRef !== recipes) {
        const merged = mergeDuplicateRecipes(recipes);
        sorted = sortRecipes(merged, db, activeSort);
        body._customState.sortedCache = sorted;
        body._customState.lastSortType = activeSort;
        body._customState.lastRecipesRef = recipes;
    }

    const updateFlatView = (shouldResetScroll = false) => {
        const q = (body._customState.searchQuery || "").trim().toLowerCase();
        let displayRecipes = sorted;
        if (q) displayRecipes = sorted.filter(r => getRecipeSearchString(r, db).includes(q));

        const isFiltered = displayRecipes.length !== sorted.length;
        const countText = isFiltered ? `${displayRecipes.length} / ${sorted.length} recipe(s)` : `${sorted.length} recipe(s)`;

        const countChip = body.querySelector("#recipes-count-chip");
        if (countChip) countChip.textContent = countText;

        const resultsWrapper = body.querySelector(".recipe-results-wrapper");
        if (!resultsWrapper) return;

        let listContainer = resultsWrapper.querySelector(".recipes-virtual-container");
        if (!listContainer) {
            resultsWrapper.innerHTML = `<div class="recipes-virtual-container" style="flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; position: relative;"></div>`;
            listContainer = resultsWrapper.querySelector(".recipes-virtual-container");
        }

        if (displayRecipes.length === 0) {
            if (body._activeVList) {
                body._activeVList.destroy();
                body._activeVList = null;
            }
            listContainer.innerHTML = `
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">∅</div>
                    <div class="message">${q ? "No recipes match your search." : "No recipes found."}</div>
                </div>`;
            return;
        }

        const prevScrollTop = (!shouldResetScroll && body._activeVList) ? body._activeVList.getScrollTop() : 0;
        if (body._activeVList) {
            body._activeVList.destroy();
            body._activeVList = null;
        }

        body._activeVList = mountVirtualList(listContainer, {
            itemCount: displayRecipes.length,
            itemHeight: 90,
            dynamicHeight: true,
            overscan: 5,
            initialScrollTop: shouldResetScroll ? 0 : prevScrollTop,
            renderRow: (index) => {
                const r = displayRecipes[index];
                const rowWrapper = document.createElement("div");
                rowWrapper.className = "virtual-recipe-row";
                rowWrapper.innerHTML = renderRecipeRow(r, db, body, machineOverride);
                return rowWrapper;
            }
        });
    };

    ensureRecipeLayout(
        body,
        activeSort,
        sorted.length,
        sorted.length,
        body._customState.searchQuery || "",
        () => {
            body._customState.sortedCache = null;
            onSortChange();
        },
        (newQuery) => {
            body._customState.searchQuery = newQuery;
            updateFlatView(true);
        }
    );

    updateFlatView(false);

    if (!body._hasRecipeEvents) {
        body._hasRecipeEvents = true;
        wireRecipeEventsDelegated(body, body, () => {
            if (typeof body._onSortChangeRef === 'function') {
                if (body._customState) body._customState.sortedCache = null;
                body._onSortChangeRef();
            }
        });
    }
    body._onSortChangeRef = onSortChange;
}