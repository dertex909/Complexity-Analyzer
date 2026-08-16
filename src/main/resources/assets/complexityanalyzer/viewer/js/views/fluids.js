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

import {escapeHtml, fmtInt, formatComplexity, getFluidFlags} from "../core/utils.js";
import {setState} from "../core/state.js";
import {FLUID_FLAG} from "../core/cabin.js";
import {renderFluidDetail} from "./details/fluid-detail.js";
import {renderGenericTable} from "../components/generic-table.js";

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
    renderGenericTable(container, {
        id: "fluids",
        tableType: "fluids",
        cssVarPrefix: "--fl-col",
        gridClass: "fluids-grid",
        columns: FLUIDS_COLUMNS,
        flagEnum: FLUID_FLAG,
        searchPlaceholder: "Filter fluids by name, id or category…",
        entityLabel: "fluids",
        renderRow: (fl, absIndex) => {
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
            return el;
        },
        onRowClick: (fl) => {
            setState({selectedItem: fl.index});
            renderFluidDetail(null, fl.index);
        }
    });
}