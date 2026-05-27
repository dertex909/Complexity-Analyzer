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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import {
    escapeHtml,
    debounce,
    formatComplexity,
    formatRawTooltip,
    getItemFlags,
    fmtInt
} from "../core/utils.js";
import {state, setFilter, selectItem} from "../core/state.js";
import {ITEM_FLAG} from "../core/cabin.js";
import {mountVirtualList} from "../components/virtual-list.js";
import {renderItemDetail} from "./details/item-detail.js";
import {setupResizableTable} from "../components/resizable-table.js";
import {generateTableHeader} from "../components/table-columns.js";
import {openFilterPopover} from "../components/filter-popover.js";
import {
    passesModFilter,
    passesFlagsFilter,
    passesCategoryFilter,
    passesRangeFilter
} from "../components/item-filter.js";

const ITEMS_COLUMNS = [
    {index: 1, label: "№", filter: null, sortable: false},
    {index: 2, label: "ID", filter: "id", sortable: true},
    {index: 3, label: "Name", filter: null, sortable: true},
    {index: 4, label: "Complexity", filter: "complexity", sortable: true, numeric: true},
    {index: 5, label: "Depth", filter: "depth", sortable: true, numeric: true},
    {index: 6, label: "Total Ingredients", filter: "totalIngredients", sortable: true, numeric: true},
    {index: 7, label: "Usage", filter: "usageCount", sortable: true, numeric: true},
    {index: 8, label: "Category", filter: "category", sortable: true},
    {index: 9, label: "Flags", filter: "flags", sortable: false}
];

export function renderItems(container) {
    const db = state.db;
    if (!db) return;

    const tableConfig = setupResizableTable({
        tableId: "items",
        cssVarPrefix: "--col",
        columnCount: 9,
        headingColumns: [
            {index: 4, label: "Complexity"},
            {index: 5, label: "Depth"},
            {index: 6, label: "Total Ingredients"},
            {index: 7, label: "Usage"},
            {index: 8, label: "Category"}
        ],
        flagsColumn: {
            index: 9,
            flagChecks: [
                f => f & ITEM_FLAG.HAS_CYCLE,
                f => f & ITEM_FLAG.IS_UNCALCULABLE,
                f => !(f & ITEM_FLAG.HAS_RECIPE),
                f => f & ITEM_FLAG.IS_HARDCODED
            ]
        },
        db,
        tableType: "items"
    });

    const f = state.filters.items;
    container.innerHTML = `
        <div class="controls" id="items-controls">
            <input type="search" id="items-query" placeholder="Filter by name, id, category…" value="${escapeHtml(f.query)}" autocomplete="off">
            <select id="items-sort">
                <option value="complexity-desc" ${f.sort === "complexity-desc" ? "selected" : ""}>Complexity ▼</option>
                <option value="complexity-asc" ${f.sort === "complexity-asc" ? "selected" : ""}>Complexity ▲</option>
                <option value="name-asc" ${f.sort === "name-asc" ? "selected" : ""}>Name A-Z</option>
                <option value="id-asc" ${f.sort === "id-asc" ? "selected" : ""}>ID A-Z</option>
                <option value="depth-desc" ${f.sort === "depth-desc" ? "selected" : ""}>Depth ▼</option>
                <option value="usage-desc" ${f.sort === "usage-desc" ? "selected" : ""}>Usage ▼</option>
            </select>
            <span class="flex-grow"></span>
            <span class="chip" id="items-count">0 items</span>
        </div>
        ${generateTableHeader(ITEMS_COLUMNS, "items-grid", "items-head")}
        <div id="items-list"></div>
    `;

    wireItemFilters(tableConfig);
    updateItemsView();

    if (state.selectedItem >= 0) {
        renderItemDetail(container, state.selectedItem);
    }
}


function updateHeaderIndicators() {
    const f = state.filters.items;
    const head = document.getElementById("items-head");
    if (!head) return;

    const itemsDef = [
        {key: "id", isFiltered: () => f.modsFilter && f.modsFilter.length > 0},
        {key: "complexity", isFiltered: () => f.minComplexity !== "" || f.maxComplexity !== ""},
        {key: "depth", isFiltered: () => f.minDepth !== "" || f.maxDepth !== ""},
        {key: "totalIngredients", isFiltered: () => f.minTotalIngredients !== "" || f.maxTotalIngredients !== ""},
        {key: "usageCount", isFiltered: () => f.minRecipeUsages !== "" || f.maxRecipeUsages !== ""},
        {key: "category", isFiltered: () => f.categoriesFilter && f.categoriesFilter.length > 0},
        {key: "flags", isFiltered: () => f.flagsFilter && f.flagsFilter.length > 0},
    ];

    itemsDef.forEach(item => {
        const el = head.querySelector(`[data-filter="${item.key}"]`);
        if (el) el.classList.toggle("filtered", item.isFiltered());
    });
}

function wireItemFilters(tableConfig) {
    const onInput = debounce((key, val) => {
        setFilter("items", {[key]: val});
        updateItemsView();
    }, 120);
    $("items-query").addEventListener("input", e => onInput("query", e.target.value));
    $("items-sort").addEventListener("change", e => {
        setFilter("items", {sort: e.target.value});
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

    const [field, dir] = f.sort.split("-");
    const sign = dir === "asc" ? 1 : -1;
    const getVal = {
        complexity: x => isFinite(x.complexity) ? x.complexity : Number.MAX_VALUE,
        name: x => x.name, id: x => x.id, depth: x => x.depth, usage: x => x.usageCount,
    }[field] || (x => x.complexity);

    list.sort((a, b) => {
        const av = getVal(a), bv = getVal(b);
        if (typeof av === "number") return sign * (av - bv);
        return sign * String(av).localeCompare(String(bv));
    });

    $("items-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(n)} items`;

    updateHeaderIndicators();

    const itemsList = $("items-list");
    if (list.length === 0) {
        itemsList.innerHTML = `
            <div class="virtual-viewport" style="display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; width: 100%; flex: 1;">
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">🔍</div>
                    <div class="message">No items match your filter.</div>
                </div>
            </div>`;
        return;
    }
    mountVirtualList(itemsList, {
        itemCount: list.length,
        itemHeight: 28,
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