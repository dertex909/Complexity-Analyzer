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

export function generateTableHeader(columns, gridClass, headId) {
    if (!columns || !Array.isArray(columns)) {
        throw new Error("generateTableHeader requires an array of column definitions");
    }

    const headerCells = columns.map(col => {
        const isClickable = col.filter !== null && col.filter !== undefined;
        const clickableClass = isClickable ? "clickable-header" : "";
        const numClass = col.numeric ? "num" : "";
        const filterAttr = isClickable ? `data-filter="${col.filter}"` : "";
        const indicator = isClickable ? `<span class="filter-indicator">▼</span>` : "";

        return `
            <div class="th-cell ${numClass} ${clickableClass}" data-index="${col.index}" ${filterAttr}>
                <span class="th-text">${col.label} ${indicator}</span>
                <div class="col-drag-handle"></div>
            </div>
        `;
    }).join("");

    return `
        <div class="table-head ${gridClass}" id="${headId}" data-column-count="${columns.length}">
            ${headerCells}
        </div>
    `;
}

export function generateSortControlsHtml(columns, currentSort, idPrefix) {
    const sortableColumns = columns.filter(c => c.field && c.sortable !== false && c.label !== "№");
    const [currentField, currentDir] = (currentSort || `${sortableColumns[0]?.field || 'id'}-desc`).split("-");

    const optionsHtml = sortableColumns.map(col => `
        <option value="${col.field}" ${col.field === currentField ? "selected" : ""}>${col.label}</option>
    `).join("");

    const isAsc = currentDir === "asc";

    return `
        <div class="sort-control-group" id="${idPrefix}-sort-group" style="display: inline-flex; align-items: center; gap: 4px;">
            <select id="${idPrefix}-sort-field" class="sort-field-select" title="Sort Column" style="min-width: 130px;">
                ${optionsHtml}
            </select>
            <button id="${idPrefix}-sort-dir-btn" class="btn sort-dir-btn" title="Toggle Sort Order" style="display: inline-flex; align-items: center; gap: 4px; padding: 6px 10px; font-size: 12px; font-weight: 500;">
                <span class="sort-dir-text">${isAsc ? "▲ ASC" : "▼ DESC"}</span>
            </button>
        </div>
    `;
}

export function wireSortControls(container, idPrefix, getCurrentSort, onSortChange) {
    const fieldSelect = container.querySelector(`#${idPrefix}-sort-field`);
    const dirBtn = container.querySelector(`#${idPrefix}-sort-dir-btn`);

    if (!fieldSelect || !dirBtn) return;

    fieldSelect.addEventListener("change", () => {
        const dir = (getCurrentSort() || "desc").split("-")[1] || "desc";
        onSortChange(`${fieldSelect.value}-${dir}`);
    });

    dirBtn.addEventListener("click", () => {
        const [field, dir] = (getCurrentSort() || `${fieldSelect.value}-desc`).split("-");
        const newDir = dir === "asc" ? "desc" : "asc";
        const dirSpan = dirBtn.querySelector(".sort-dir-text");
        if (dirSpan) dirSpan.textContent = newDir === "asc" ? "▲ ASC" : "▼ DESC";
        onSortChange(`${field || fieldSelect.value}-${newDir}`);
    });
}

export function universalSort(list, columns, sortString, customGetter = null) {
    if (!sortString || !list || list.length === 0) return list;

    const [field, dir] = sortString.split("-");
    const sign = dir === "asc" ? 1 : -1;

    const colDef = columns.find(c => c.field === field);
    const isNumeric = colDef?.numeric ?? false;

    list.sort((a, b) => {
        let valA = customGetter ? customGetter(a, field) : a[field];
        let valB = customGetter ? customGetter(b, field) : b[field];

        if (field === "complexity") {
            const infA = valA === undefined || valA === null || !isFinite(valA) || valA < 0;
            const infB = valB === undefined || valB === null || !isFinite(valB) || valB < 0;
            if (infA && !infB) return 1;
            if (!infA && infB) return -1;
            if (infA && infB) return 0;
        }

        if (isNumeric || (typeof valA === "number" && typeof valB === "number")) {
            const numA = (valA === undefined || valA === null || isNaN(Number(valA))) ? -Infinity : Number(valA);
            const numB = (valB === undefined || valB === null || isNaN(Number(valB))) ? -Infinity : Number(valB);
            return sign * (numA - numB);
        }

        const strA = String(valA ?? "").toLowerCase();
        const strB = String(valB ?? "").toLowerCase();
        return sign * strA.localeCompare(strB);
    });

    return list;
}
