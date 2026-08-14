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
        <option value="${col.field}" style="background-color: #181d24; color: #e8edf2; padding: 6px 10px;" ${col.field === currentField ? "selected" : ""}>${col.label}</option>
    `).join("");

    const isAsc = currentDir === "asc";

    return `
        <div class="sort-widget" id="${idPrefix}-sort-group" style="display: inline-flex; align-items: center; background: var(--bg-raised, #181d24); border: 1px solid var(--border, #262e3a); border-radius: 6px; height: 32px; box-sizing: border-box; transition: border-color 0.15s, box-shadow 0.15s; position: relative;">
            <div style="display: flex; align-items: center; padding-left: 9px; color: var(--text-dim, #9aa8b8); pointer-events: none;">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="m3 16 4 4 4-4"/>
                    <path d="M7 20V4"/>
                    <path d="m21 8-4-4-4 4"/>
                    <path d="M17 4v16"/>
                </svg>
            </div>
            
            <select id="${idPrefix}-sort-field" title="Sort Column" style="color-scheme: dark; background: transparent !important; border: none !important; outline: none !important; box-shadow: none !important; color: var(--text, #e8edf2); font-family: var(--sans), sans-serif; font-size: 12px; font-weight: 500; padding: 0 24px 0 8px; height: 100%; cursor: pointer; appearance: none; -webkit-appearance: none; margin: 0;">
                ${optionsHtml}
            </select>
            
            <span style="position: absolute; right: 38px; pointer-events: none; color: var(--text-muted, #5a6878); font-size: 8px;">▼</span>
            
            <div style="width: 1px; height: 16px; background: var(--border, #262e3a);"></div>
            
            <button id="${idPrefix}-sort-dir-btn" class="btn" title="${isAsc ? 'Ascending (lowest first)' : 'Descending (highest first)'}" style="background: transparent; border: none !important; outline: none !important; box-shadow: none !important; color: ${isAsc ? 'var(--accent, #5bc0ff)' : 'var(--text-dim, #9aa8b8)'}; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 100%; padding: 0; border-radius: 0 5px 5px 0; transition: all 0.15s ease;" onmouseover="this.style.background='var(--bg-hover, #202630)';this.style.color='var(--text, #e8edf2)';" onmouseout="this.style.background='transparent';this.style.color='${isAsc ? 'var(--accent, #5bc0ff)' : 'var(--text-dim, #9aa8b8)'}';">
                <svg class="sort-arrow-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="transform: ${isAsc ? 'rotate(180deg)' : 'rotate(0deg)'}; transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);">
                    <path d="M12 5v14"/>
                    <path d="m19 12-7 7-7-7"/>
                </svg>
            </button>
        </div>
    `;
}

export function wireSortControls(container, idPrefix, getCurrentSort, onSortChange) {
    const fieldSelect = container.querySelector(`#${idPrefix}-sort-field`);
    const dirBtn = container.querySelector(`#${idPrefix}-sort-dir-btn`);
    const sortGroup = container.querySelector(`#${idPrefix}-sort-group`);

    if (!fieldSelect || !dirBtn) return;

    if (sortGroup) {
        fieldSelect.addEventListener("focus", () => {
            sortGroup.style.borderColor = "var(--accent, #5bc0ff)";
            sortGroup.style.boxShadow = "0 0 0 2px rgba(91, 192, 255, 0.15)";
        });
        fieldSelect.addEventListener("blur", () => {
            sortGroup.style.borderColor = "var(--border, #262e3a)";
            sortGroup.style.boxShadow = "none";
        });
    }

    fieldSelect.addEventListener("change", () => {
        const dir = (getCurrentSort() || "desc").split("-")[1] || "desc";
        onSortChange(`${fieldSelect.value}-${dir}`);
    });

    dirBtn.addEventListener("click", () => {
        const [field, dir] = (getCurrentSort() || `${fieldSelect.value}-desc`).split("-");
        const newDir = dir === "asc" ? "desc" : "asc";

        const isAsc = newDir === "asc";
        dirBtn.title = isAsc ? "Ascending (lowest first)" : "Descending (highest first)";
        dirBtn.style.color = isAsc ? "var(--accent, #5bc0ff)" : "var(--text-dim, #9aa8b8)";

        const arrow = dirBtn.querySelector(".sort-arrow-icon");
        if (arrow) arrow.style.transform = isAsc ? "rotate(180deg)" : "rotate(0deg)";

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
