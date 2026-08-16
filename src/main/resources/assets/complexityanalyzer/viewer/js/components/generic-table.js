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

import {debounce, escapeHtml, fmtInt} from "../core/utils.js";
import {setFilter, state} from "../core/state.js";
import {mountVirtualList} from "./virtual-list.js";
import {setupResizableTable} from "./resizable-table.js";
import {generateSortControlsHtml, generateTableHeader, universalSort, wireSortControls} from "./table-columns.js";
import {openFilterPopover, RANGE_KEYS} from "./filter-popover.js";
import {passesCategoryFilter, passesFlagsFilter, passesModFilter, passesRangeFilter} from "./item-filter.js";

export function renderGenericTable(container, {
    id,
    tableType = id,
    cssVarPrefix,
    gridClass,
    columns,
    flagEnum,
    searchPlaceholder = "Filter…",
    entityLabel = "items",
    itemHeight = 28,
    emptyMessage = "No items match your filter.",
    getEntities,
    renderRow,
    onRowClick,
    customFilter,
    customSortGetter,
    controlsPrefixHtml = "",
    controlsSuffixHtml = ""
}) {
    const db = state.db;
    if (!db) return null;

    const f = state.filters[id];
    const headId = `${id}-head`;
    const listId = `${id}-list`;
    const queryId = `${id}-query`;
    const countId = `${id}-count`;

    const tableConfig = setupResizableTable({
        tableId: id,
        cssVarPrefix,
        columns,
        db,
        tableType
    });

    container.innerHTML = `
        <div class="controls" id="${id}-controls">
            ${controlsPrefixHtml}
            <input type="search" id="${queryId}" placeholder="${escapeHtml(searchPlaceholder)}" value="${escapeHtml(f.query || "")}" autocomplete="off">
            ${generateSortControlsHtml(columns, f.sort, id)}
            ${controlsSuffixHtml}
            <span class="flex-grow"></span>
            <span class="chip" id="${countId}">0 ${entityLabel}</span>
        </div>
        <div style="overflow: hidden; flex: 0 0 auto;">
            ${generateTableHeader(columns, gridClass, headId)}
        </div>
        <div id="${listId}" style="flex: 1; min-height: 0; position: relative;"></div>
    `;

    function updateHeaderIndicators() {
        const currentFilters = state.filters[id];
        const head = document.getElementById(headId);
        if (!head) return;

        columns.forEach(col => {
            if (!col.filter) return;
            let isFiltered = false;
            if (col.filter === "id") {
                isFiltered = currentFilters.modsFilter && currentFilters.modsFilter.length > 0;
            } else if (col.filter === "category") {
                isFiltered = currentFilters.categoriesFilter && currentFilters.categoriesFilter.length > 0;
            } else if (col.filter === "flags") {
                isFiltered = currentFilters.flagsFilter && currentFilters.flagsFilter.length > 0;
            } else if (RANGE_KEYS[col.filter]) {
                const [minKey, maxKey] = RANGE_KEYS[col.filter];
                isFiltered = (currentFilters[minKey] !== "" && currentFilters[minKey] !== undefined) ||
                    (currentFilters[maxKey] !== "" && currentFilters[maxKey] !== undefined);
            }

            const el = head.querySelector(`[data-filter="${col.filter}"]`);
            if (el) el.classList.toggle("filtered", isFiltered);
        });
    }

    let activeVirtualList = null;

    function updateView() {
        if (activeVirtualList) {
            activeVirtualList.destroy();
            activeVirtualList = null;
        }

        const currentFilters = state.filters[id];
        const q = (currentFilters.query || "").trim().toLowerCase();

        let rawList = [];
        if (getEntities) {
            rawList = getEntities();
        } else if (db[tableType]) {
            const count = db[tableType].count;
            for (let i = 0; i < count; i++) {
                const entity = db[tableType].get(i);
                if (entity) rawList.push(entity);
            }
        }

        const filtered = [];
        for (let i = 0; i < rawList.length; i++) {
            const item = rawList[i];

            if (q) {
                const hay = ((item.name || "") + " " + (item.id || "") + " " + (item.categoryName || "")).toLowerCase();
                if (!hay.includes(q)) continue;
            }

            let passesRanges = true;
            for (let c = 0; c < columns.length; c++) {
                const col = columns[c];
                if (col.numeric && col.filter && RANGE_KEYS[col.filter]) {
                    const [minKey, maxKey] = RANGE_KEYS[col.filter];
                    const val = item[col.field];
                    if (!passesRangeFilter(val, currentFilters[minKey] ?? "", currentFilters[maxKey] ?? "")) {
                        passesRanges = false;
                        break;
                    }
                }
            }
            if (!passesRanges) continue;

            if (currentFilters.categoriesFilter && !passesCategoryFilter(item, currentFilters.categoriesFilter)) continue;
            if (currentFilters.modsFilter && !passesModFilter(item, currentFilters.modsFilter)) continue;
            if (flagEnum && currentFilters.flagsFilter && !passesFlagsFilter(item, currentFilters.flagsFilter, flagEnum)) continue;
            if (customFilter && !customFilter(item, currentFilters)) continue;

            filtered.push(item);
        }

        universalSort(filtered, columns, currentFilters.sort, customSortGetter);

        const countEl = document.getElementById(countId);
        if (countEl) {
            const totalCount = rawList.length;
            countEl.textContent = `${fmtInt.format(filtered.length)} / ${fmtInt.format(totalCount)} ${entityLabel}`;
        }

        updateHeaderIndicators();

        const listContainer = document.getElementById(listId);
        if (!listContainer) return;

        activeVirtualList = mountVirtualList(listContainer, {
            itemCount: filtered.length,
            itemHeight,
            emptyMessage,
            renderRow: (absIndex) => {
                const entity = filtered[absIndex];
                const rowEl = renderRow(entity, absIndex);
                if (onRowClick) {
                    rowEl.addEventListener("click", () => onRowClick(entity, absIndex));
                }
                return rowEl;
            }
        });
    }

    const onSearchInput = debounce((val) => {
        setFilter(id, {query: val});
        updateView();
    }, 120);

    const queryInput = document.getElementById(queryId);
    if (queryInput) {
        queryInput.addEventListener("input", e => onSearchInput(e.target.value));
    }

    wireSortControls(container, id, () => state.filters[id].sort, (newSort) => {
        setFilter(id, {sort: newSort});
        updateView();
    });

    const head = document.getElementById(headId);
    if (head) {
        head.querySelectorAll(".clickable-header").forEach(hdr => {
            hdr.addEventListener("click", (e) => {
                if (e.target.classList.contains("col-drag-handle")) return;
                openFilterPopover(hdr, hdr.dataset.filter, id, db, state.filters[id], (patch) => {
                    setFilter(id, patch);
                    updateView();
                });
            });
        });
    }

    tableConfig.initResizers(headId);
    updateHeaderIndicators();
    updateView();

    return {
        update: updateView,
        tableConfig
    };
}