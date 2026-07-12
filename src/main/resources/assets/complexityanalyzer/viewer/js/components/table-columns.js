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
        const isClickable = col.filter !== null;
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