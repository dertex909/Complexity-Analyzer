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

import {debounce, escapeHtml, fmtInt, formatComplexity, getFluidFlags} from "../core/utils.js";
import {setFilter, setState, state} from "../core/state.js";
import {mountVirtualList} from "../components/virtual-list.js";
import {renderFluidDetail} from "./details/fluid-detail.js";
import {FLUID_FLAG} from "../core/cabin.js";
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

const FLUIDS_COLUMNS = [
    {index: 1, label: "№", field: null, filter: null, sortable: false},
    {index: 2, label: "ID", field: "id", filter: "id", sortable: true},
    {index: 3, label: "Name", field: "name", filter: null, sortable: true},
    {index: 4, label: "Complexity", field: "complexity", filter: "complexity", sortable: true, numeric: true},
    {index: 5, label: "Usage", field: "usageCount", filter: "usageCount", sortable: true, numeric: true},
    {index: 6, label: "Category", field: "categoryName", filter: "category", sortable: true},
    {index: 7, label: "Flags", field: "flags", filter: "flags", sortable: true, numeric: true}
];

export function renderFluids(container) {
    const db = state.db;
    if (!db) return;

    const tableConfig = setupResizableTable({
        tableId: "fluids",
        cssVarPrefix: "--fl-col",
        columns: FLUIDS_COLUMNS,
        db,
        tableType: "fluids"
    });

    const f = state.filters.fluids;

    container.innerHTML = `
        <div class="controls" id="fluids-controls">
            <input type="search" id="fluids-query" placeholder="Filter fluids by name, id or category…" value="${escapeHtml(f.query)}" autocomplete="off">
            ${generateSortControlsHtml(FLUIDS_COLUMNS, f.sort, "fluids")}
            <span class="flex-grow"></span>
            <span class="chip" id="fluids-count">0 fluids</span>
        </div>
        <div style="overflow: hidden; flex: 0 0 auto;">
            ${generateTableHeader(FLUIDS_COLUMNS, "fluids-grid", "fluids-head")}
        </div>
        <div id="fluids-list" style="flex: 1; min-height: 0; position: relative;"></div>
    `;

    wireFluidFilters(tableConfig, container);
    updateHeaderIndicators();
    updateFluidsView();
}

function updateHeaderIndicators() {
    const f = state.filters.fluids;
    const head = document.getElementById("fluids-head");
    if (!head) return;

    head.querySelectorAll(".clickable-header").forEach(hdr => {
        const type = hdr.dataset.filter;
        let active = false;
        if (type === "complexity") active = f.minComplexity !== "" || f.maxComplexity !== "";
        else if (type === "usageCount") active = f.minRecipeUsages !== "" || f.maxRecipeUsages !== "";
        else if (type === "category") active = f.categoriesFilter.length > 0;
        else if (type === "flags") active = f.flagsFilter.length > 0;
        else if (type === "id") active = f.modsFilter.length > 0;

        hdr.classList.toggle("filtered", active);
    });
}

function wireFluidFilters(tableConfig, container) {
    const onInput = debounce((key, val) => {
        setFilter("fluids", {[key]: val});
        updateFluidsView();
    }, 120);

    document.getElementById("fluids-query").addEventListener("input", e => onInput("query", e.target.value));

    wireSortControls(container, "fluids", () => state.filters.fluids.sort, (newSort) => {
        setFilter("fluids", {sort: newSort});
        updateFluidsView();
    });

    const head = document.getElementById("fluids-head");
    if (head) head.querySelectorAll(".clickable-header").forEach(hdr => {
        hdr.addEventListener("click", (e) => {
            if (e.target.classList.contains("col-drag-handle")) return;
            openPopover(hdr, hdr.dataset.filter);
        });
    });

    tableConfig.initResizers("fluids-head");
}

function openPopover(headerCell, filterType) {
    const db = state.db;
    const f = state.filters.fluids;
    openFilterPopover(headerCell, filterType, "fluids", db, f, (patch) => {
        setFilter("fluids", patch);
        updateFluidsView();
    });
}

function updateFluidsView() {
    const db = state.db;
    const f = state.filters.fluids;
    const q = f.query.trim().toLowerCase();

    updateHeaderIndicators();

    const list = [];
    for (let i = 0; i < db.fluids.count; i++) {
        const fl = db.fluids.get(i);
        if (!fl) continue;

        if (q && !(fl.name + " " + fl.id + " " + fl.categoryName).toLowerCase().includes(q)) continue;

        if (!passesModFilter(fl, f.modsFilter)) continue;
        if (!passesCategoryFilter(fl, f.categoriesFilter)) continue;
        if (!passesRangeFilter(fl.complexity, f.minComplexity, f.maxComplexity)) continue;
        if (!passesRangeFilter(fl.usageCount, f.minRecipeUsages, f.maxRecipeUsages)) continue;
        if (!passesFlagsFilter(fl, f.flagsFilter, FLUID_FLAG)) continue;

        list.push(fl);
    }

    universalSort(list, FLUIDS_COLUMNS, f.sort);

    document.getElementById("fluids-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(db.fluids.count)} fluids`;

    const listContainer = document.getElementById("fluids-list");

    mountVirtualList(listContainer, {
        itemCount: list.length,
        itemHeight: 28,
        emptyMessage: "No fluids match your filter.",
        renderRow: (absIndex) => {
            const fl = list[absIndex];
            const el = document.createElement("div");
            el.className = "row fluids-grid";
            el.dataset.index = fl.index;
            el.innerHTML = `
                <span class="idx">${absIndex + 1}</span>
                <span class="id" title="${fl.id}">${fl.id}</span>
                <span class="name" title="${escapeHtml(fl.name)}">${escapeHtml(fl.name)}</span>
                <span class="num cat-${fl.categoryName || "Uncalculable"}" style="font-weight:600;">${formatComplexity(fl.complexity)}</span>
                <span class="num">${fmtInt.format(fl.usageCount)}</span>
                <span><span class="category-pill cat-${fl.categoryName || "Uncalculable"}">${fl.categoryName}</span></span>
                <span class="flags">${getFluidFlags(fl, true)}</span>
            `;
            el.addEventListener("click", () => {
                setState({selectedItem: fl.index});
                renderFluidDetail(null, fl.index);
            });
            return el;
        }
    });
}
