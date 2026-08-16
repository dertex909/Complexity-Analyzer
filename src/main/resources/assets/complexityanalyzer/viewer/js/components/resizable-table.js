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

const STORAGE_VERSION = 4;

let activePopover = null;

document.addEventListener("click", (e) => {
    if (activePopover && !activePopover.contains(e.target) && !e.target.closest(".clickable-header")) {
        closeActivePopover();
    }
});

export function closeActivePopover() {
    if (activePopover) {
        activePopover.remove();
        activePopover = null;
    }
}

export function setActivePopover(popover) {
    activePopover = popover;
}

export function measureTextWidth(text, isMono = false) {
    const dummy = document.createElement("span");
    dummy.style.visibility = "hidden";
    dummy.style.position = "absolute";
    dummy.style.whiteSpace = "nowrap";
    dummy.style.fontFamily = isMono ? "var(--mono), monospace" : "inherit";
    dummy.style.fontSize = "13px";
    dummy.textContent = text;
    document.body.appendChild(dummy);
    const w = dummy.getBoundingClientRect().width;
    dummy.remove();
    return w;
}

function normalizeWidths(widths, columnCount) {
    let sum = 0;
    const parsed = {};
    for (let i = 1; i <= columnCount; i++) {
        const val = parseFloat(widths[i]);
        parsed[i] = isNaN(val) ? 0 : val;
        sum += parsed[i];
    }

    if (sum === 0) {
        for (let i = 1; i <= columnCount; i++) widths[i] = `${(100 / columnCount).toFixed(4)}%`;
        return;
    }

    let normalizedSum = 0;
    for (let i = 1; i < columnCount; i++) {
        const normalizedVal = (parsed[i] / sum) * 100;
        widths[i] = `${normalizedVal.toFixed(4)}%`;
        normalizedSum += normalizedVal;
    }
    widths[columnCount] = `${(100 - normalizedSum).toFixed(4)}%`;
}

function calculateInitialWidths(config) {
    const cols = config.columns || [];
    const columnCount = cols.length || config.columnCount || 1;
    const pixelWidths = calculateDynamicMinWidths(config);

    let totalWeight = 0;
    for (let i = 1; i <= columnCount; i++) totalWeight += pixelWidths[i] || 40;

    const percentages = {};
    for (let i = 1; i <= columnCount; i++) {
        const pct = ((pixelWidths[i] || 40) / totalWeight) * 100;
        percentages[i] = `${pct}%`;
    }

    normalizeWidths(percentages, columnCount);
    return percentages;
}

export function loadColumnWidths(tableId, config, cssVarPrefix) {
    const keysToRemove = [];
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && key.includes("col-width-pct") && !key.includes(`-${STORAGE_VERSION}-`)) keysToRemove.push(key);
    }
    keysToRemove.forEach(key => localStorage.removeItem(key));

    const storageKey = `${tableId}-${STORAGE_VERSION}-col-width-pct`;
    const cols = config.columns || [];
    const columnCount = cols.length || config.columnCount || 1;
    const initialWidths = calculateInitialWidths(config);

    let sum = 0;
    const savedWidths = {};

    for (let i = 1; i <= columnCount; i++) {
        const saved = localStorage.getItem(`${storageKey}-${i}`);
        if (saved) {
            savedWidths[i] = saved;
            sum += parseFloat(saved);
        }
    }

    const useSaved = sum >= 95 && sum <= 105 && Object.keys(savedWidths).length === columnCount;

    if (useSaved) normalizeWidths(savedWidths, columnCount);

    for (let i = 1; i <= columnCount; i++) {
        const w = useSaved ? savedWidths[i] : initialWidths[i];
        document.documentElement.style.setProperty(`${cssVarPrefix}-${i}`, w);
        if (!useSaved) localStorage.setItem(`${storageKey}-${i}`, w);
    }
}

export function calculateDynamicMinWidths(config) {
    const {db, tableType, columns, columnCount} = config;
    const cols = columns || [];
    const count = cols.length || columnCount || 1;
    const mins = {};
    const totalRows = (db && tableType && db[tableType]) ? db[tableType].count : 0;
    const sampleSize = Math.min(totalRows, 80);

    for (let i = 1; i <= count; i++) {
        const col = cols[i - 1] || {};
        const label = col.label || "";
        const field = col.field;
        const isNumeric = col.numeric || false;
        const isMono = field === "id" || field === "complexity" || field === "combatPower";

        let maxContentWidth = measureTextWidth(label, false) + (col.filter ? 32 : 18);

        if (label === "№" || (!field && i === 1)) {
            const rowNumWidth = measureTextWidth(String(totalRows || 9999), false) + 20;
            mins[i] = Math.max(38, Math.ceil(Math.max(maxContentWidth, rowNumWidth)));
            continue;
        }

        if (field === "flags" || label.toLowerCase().includes("flag")) {
            mins[i] = Math.max(52, Math.ceil(maxContentWidth));
            continue;
        }

        if (field === "categoryName" || label.toLowerCase().includes("category")) {
            mins[i] = Math.max(85, Math.ceil(maxContentWidth + 14));
            continue;
        }

        if (totalRows > 0 && field) {
            for (let s = 0; s < sampleSize; s++) {
                const entity = db[tableType].get(s);
                if (!entity) continue;
                const val = entity[field];
                if (val !== undefined && val !== null) {
                    let text = String(val);
                    if (typeof val === "number") {
                        if (field === "complexity") {
                            text = val >= 1e6 ? val.toExponential(2) : String(Math.round(val * 100) / 100);
                        } else {
                            text = String(Math.round(val * 100) / 100);
                        }
                    }
                    const sampleW = measureTextWidth(text, isMono) + 20;
                    if (sampleW > maxContentWidth) maxContentWidth = sampleW;
                }
            }
        }

        if (field === "name" || label.toLowerCase().includes("name")) {
            mins[i] = Math.max(200, Math.ceil(maxContentWidth * 1.25));
        } else if (field === "id") {
            mins[i] = Math.min(300, Math.max(150, Math.ceil(maxContentWidth + 16)));
        } else if (isNumeric) {
            mins[i] = Math.min(140, Math.max(48, Math.ceil(maxContentWidth + 10)));
        } else {
            mins[i] = Math.max(55, Math.ceil(maxContentWidth + 12));
        }
    }

    return mins;
}

export function initColumnResizers(headId, tableId, cssVarPrefix, defaults, getMinWidths) {
    const head = document.getElementById(headId);
    if (!head) return;

    const handles = head.querySelectorAll(".col-drag-handle");
    const columnCount = parseInt(head.dataset.columnCount) || 7;
    const storageKey = `${tableId}-${STORAGE_VERSION}-col-width-pct`;

    handles.forEach(handle => {
        handle.addEventListener("pointerdown", (e) => {
            e.preventDefault();
            e.stopPropagation();
            closeActivePopover();

            const cell = handle.closest(".th-cell");
            const index = parseInt(cell.dataset.index, 10);
            if (index >= columnCount) return;

            const startX = e.clientX;
            const rightPadding = parseFloat(window.getComputedStyle(head).paddingRight || 0);
            const totalWidth = head.getBoundingClientRect().width - rightPadding;

            const startPct = {};
            for (let i = 1; i <= columnCount; i++) {
                const val = document.documentElement.style.getPropertyValue(`${cssVarPrefix}-${i}`);
                startPct[i] = parseFloat(val) || (100 / columnCount);
            }

            handle.classList.add("dragging");
            handle.setPointerCapture(e.pointerId);

            const minWidths = getMinWidths();

            let currentPct_i = startPct[index];
            let currentPct_j = startPct[index + 1];

            const onPointerMove = (moveEvent) => {
                const deltaX = moveEvent.clientX - startX;
                const deltaPct = (deltaX / totalWidth) * 100;

                const i = index;
                const j = index + 1;

                const minPct_i = (minWidths[i] / totalWidth) * 100;
                const minPct_j = (minWidths[j] / totalWidth) * 100;

                let minDeltaPct = minPct_i - startPct[i];
                let maxDeltaPct = startPct[j] - minPct_j;

                if (minDeltaPct > maxDeltaPct) {
                    minDeltaPct = 0;
                    maxDeltaPct = 0;
                }

                const clampedDeltaPct = Math.max(minDeltaPct, Math.min(deltaPct, maxDeltaPct));

                currentPct_i = startPct[i] + clampedDeltaPct;
                currentPct_j = startPct[j] - clampedDeltaPct;

                document.documentElement.style.setProperty(`${cssVarPrefix}-${i}`, `${currentPct_i}%`);
                document.documentElement.style.setProperty(`${cssVarPrefix}-${j}`, `${currentPct_j}%`);
            };

            const onPointerUp = (upEvent) => {
                handle.classList.remove("dragging");
                try {
                    handle.releasePointerCapture(upEvent.pointerId);
                } catch (err) {
                }
                handle.removeEventListener("pointermove", onPointerMove);
                handle.removeEventListener("pointerup", onPointerUp);

                localStorage.setItem(`${storageKey}-${index}`, `${currentPct_i}%`);
                localStorage.setItem(`${storageKey}-${index + 1}`, `${currentPct_j}%`);

                window.dispatchEvent(new Event('resize'));
            };

            handle.addEventListener("pointermove", onPointerMove);
            handle.addEventListener("pointerup", onPointerUp);
        });
    });
}

export function setupResizableTable(config) {
    if (!config.tableId || !config.cssVarPrefix) {
        throw new Error("setupResizableTable requires tableId and cssVarPrefix");
    }

    loadColumnWidths(config.tableId, config, config.cssVarPrefix);

    const getMinWidths = () => calculateDynamicMinWidths(config);

    const initResizers = (headId) => {
        initColumnResizers(headId, config.tableId, config.cssVarPrefix, null, getMinWidths);
    };

    return {
        getMinWidths,
        initResizers
    };
}
