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

import {debounce, escapeHtml, fmtInt, formatComplexity, formatRawTooltip, getItemFlags} from "../core/utils.js";
import {selectItem, setFilter, state} from "../core/state.js";
import {ITEM_FLAG} from "../core/cabin.js";
import {mountVirtualList} from "../components/virtual-list.js";
import {renderItemDetail} from "./details/item-detail.js";
import {setupResizableTable} from "../components/resizable-table.js";
import {
    generateSortControlsHtml,
    generateTableHeader,
    universalSort,
    wireSortControls
} from "../components/table-columns.js";
import {openFilterPopover} from "../components/filter-popover.js";
import {
    passesCategoryFilter,
    passesFlagsFilter,
    passesModFilter,
    passesRangeFilter
} from "../components/item-filter.js";

const ITEMS_COLUMNS = [
    {index: 1, label: "№", field: null, filter: null, sortable: false},
    {index: 2, label: "ID", field: "id", filter: "id", sortable: true},
    {index: 3, label: "Name", field: "name", filter: null, sortable: true},
    {index: 4, label: "Complexity", field: "complexity", filter: "complexity", sortable: true, numeric: true},
    {index: 5, label: "Depth", field: "depth", filter: "depth", sortable: true, numeric: true},
    {
        index: 6,
        label: "Total Ingredients",
        field: "totalIngredients",
        filter: "totalIngredients",
        sortable: true,
        numeric: true
    },
    {index: 7, label: "Usage", field: "usageCount", filter: "usageCount", sortable: true, numeric: true},
    {index: 8, label: "Category", field: "categoryName", filter: "category", sortable: true},
    {index: 9, label: "Flags", field: "flags", filter: "flags", sortable: true, numeric: true}
];

export function renderItems(container) {
    const db = state.db;
    if (!db) return;

    const tableConfig = setupResizableTable({
        tableId: "items",
        cssVarPrefix: "--col",
        columns: ITEMS_COLUMNS,
        db,
        tableType: "items"
    });

    const f = state.filters.items;

    container.innerHTML = `
        <div class="controls" id="items-controls">
            <input type="search" id="items-query" placeholder="Filter by name, id, category…" value="${escapeHtml(f.query)}" autocomplete="off">
            ${generateSortControlsHtml(ITEMS_COLUMNS, f.sort, "items")}
            <span class="flex-grow"></span>
            <span class="chip" id="items-count">0 items</span>
        </div>
        <div style="overflow: hidden; flex: 0 0 auto;">
            ${generateTableHeader(ITEMS_COLUMNS, "items-grid", "items-head")}
        </div>
        <div id="items-list" style="flex: 1; min-height: 0; position: relative;"></div>
    `;

    wireItemFilters(tableConfig, container);
    updateItemsView();

    if (state.selectedItem >= 0) {
        renderItemDetail(container, state.selectedItem);
    }
}

function updateHeaderIndicators() {
    const f = state.filters.items;
    const head = document.getElementById("items-head");
    if (!head) return;

    ITEMS_COLUMNS.forEach(col => {
        if (!col.filter) return;
        let isFiltered = false;
        if (col.filter === "id") isFiltered = f.modsFilter && f.modsFilter.length > 0;
        else if (col.filter === "complexity") isFiltered = f.minComplexity !== "" || f.maxComplexity !== "";
        else if (col.filter === "depth") isFiltered = f.minDepth !== "" || f.maxDepth !== "";
        else if (col.filter === "totalIngredients") isFiltered = f.minTotalIngredients !== "" || f.maxTotalIngredients !== "";
        else if (col.filter === "usageCount") isFiltered = f.minRecipeUsages !== "" || f.maxRecipeUsages !== "";
        else if (col.filter === "category") isFiltered = f.categoriesFilter && f.categoriesFilter.length > 0;
        else if (col.filter === "flags") isFiltered = f.flagsFilter && f.flagsFilter.length > 0;

        const el = head.querySelector(`[data-filter="${col.filter}"]`);
        if (el) el.classList.toggle("filtered", isFiltered);
    });
}

function wireItemFilters(tableConfig, container) {
    const onInput = debounce((key, val) => {
        setFilter("items", {[key]: val});
        updateItemsView();
    }, 120);

    $("items-query").addEventListener("input", e => onInput("query", e.target.value));

    wireSortControls(container, "items", () => state.filters.items.sort, (newSort) => {
        setFilter("items", {sort: newSort});
        updateItemsView();
    });

    const head = document.getElementById("items-head");
    if (head) head.querySelectorAll(".clickable-header").forEach(hdr => {
        hdr.addEventListener("click", (e) => {
            if (e.target.classList.contains("col-drag-handle")) return;
            openPopover(hdr, hdr.dataset.filter);
        });
    });

    tableConfig.initResizers("items-head");
}

function openPopover(headerCell, filterType) {
    const db = state.db;
    const f = state.filters.items;
    openFilterPopover(headerCell, filterType, "items", db, f, (patch) => {
        setFilter("items", patch);
        updateItemsView();
    });
}

function updateItemsView() {
    const db = state.db;
    const f = state.filters.items;
    const q = f.query.trim().toLowerCase();
    const list = [];
    const n = db.items.count;

    for (let i = 0; i < n; i++) {
        const it = db.items.get(i);

        if (q) {
            const hay = (it.name + " " + it.id + " " + it.categoryName).toLowerCase();
            if (!hay.includes(q)) continue;
        }

        if (!passesRangeFilter(it.complexity, f.minComplexity, f.maxComplexity)) continue;
        if (!passesRangeFilter(it.depth, f.minDepth, f.maxDepth)) continue;
        if (!passesRangeFilter(it.totalIngredients, f.minTotalIngredients, f.maxTotalIngredients)) continue;
        if (!passesRangeFilter(it.usageCount, f.minRecipeUsages, f.maxRecipeUsages)) continue;

        if (!passesCategoryFilter(it, f.categoriesFilter)) continue;
        if (!passesModFilter(it, f.modsFilter)) continue;
        if (!passesFlagsFilter(it, f.flagsFilter, ITEM_FLAG)) continue;

        list.push(it);
    }

    universalSort(list, ITEMS_COLUMNS, f.sort);

    $("items-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(n)} items`;

    updateHeaderIndicators();

    const itemsList = $("items-list");
    mountVirtualList(itemsList, {
        itemCount: list.length,
        itemHeight: 28,
        emptyMessage: "No items match your filter.",
        renderRow: (absIndex) => {
            const it = list[absIndex];
            const el = document.createElement("div");
            el.className = "row items-grid";
            el.innerHTML = `
                <span class="idx">${absIndex + 1}</span>
                <span class="id" title="${it.id}">${it.id}</span>
                <span title="${escapeHtml(it.name)}">${escapeHtml(it.name)}</span>
                <span class="num cat-${it.categoryName || "Uncalculable"}" title="${formatRawTooltip(it.complexity)}">${formatComplexity(it.complexity)}</span>
                <span class="num" title="${formatRawTooltip(it.depth)}">${fmtInt.format(it.depth)}</span>
                <span class="num" title="${formatRawTooltip(it.totalIngredients)}">${fmtInt.format(it.totalIngredients)}</span>
                <span class="num" title="${formatRawTooltip(it.usageCount)}">${fmtInt.format(it.usageCount)}</span>
                <span><span class="category-pill cat-${it.categoryName || "Uncalculable"}">${it.categoryName}</span></span>
                <span class="flags">${getItemFlags(it, true)}</span>
            `;
            el.addEventListener("click", () => {
                selectItem(it.index);
            });
            return el;
        }
    });
}

function $(id) {
    return document.getElementById(id);
}
