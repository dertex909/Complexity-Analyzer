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

import {escapeHtml, fmtInt, formatComplexity, formatRawTooltip, getItemFlags} from "../core/utils.js";
import {selectItem, state} from "../core/state.js";
import {ITEM_FLAG} from "../core/cabin.js";
import {renderItemDetail} from "./details/item-detail.js";
import {renderGenericTable} from "../components/generic-table.js";

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
    renderGenericTable(container, {
        id: "items",
        tableType: "items",
        cssVarPrefix: "--col",
        gridClass: "items-grid",
        columns: ITEMS_COLUMNS,
        flagEnum: ITEM_FLAG,
        searchPlaceholder: "Filter by name, id, category…",
        entityLabel: "items",
        renderRow: (it, absIndex) => {
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
            return el;
        },
        onRowClick: (it) => selectItem(it.index)
    });

    if (state.selectedItem >= 0) {
        renderItemDetail(container, state.selectedItem);
    }
}