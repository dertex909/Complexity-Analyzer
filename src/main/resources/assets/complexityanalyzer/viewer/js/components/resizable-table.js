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
    const columnCount = config.columnCount;
    const pixelWidths = calculateDynamicMinWidths(config);

    const nameColIndex = config.tableId === "mobs" ? 2 : 3;
    if (pixelWidths[nameColIndex] !== undefined) pixelWidths[nameColIndex] = Math.max(pixelWidths[nameColIndex], 150);

    let totalMinWidth = 0;
    for (let i = 1; i <= columnCount; i++) totalMinWidth += pixelWidths[i] || 40;

    const percentages = {};
    for (let i = 1; i <= columnCount; i++) {
        const pct = ((pixelWidths[i] || 40) / totalMinWidth) * 100;
        percentages[i] = `${pct}%`;
    }

    normalizeWidths(percentages, columnCount);
    return percentages;
}

export function loadColumnWidths(tableId, config, cssVarPrefix) {
    const keysToRemove = [];
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && key.includes("col-width-pct") && !key.includes("-v2-")) keysToRemove.push(key);
    }
    keysToRemove.forEach(key => localStorage.removeItem(key));

    const storageKey = `${tableId}-v2-col-width-pct`;
    const columnCount = config.columnCount;

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
    const {db, tableType, columnCount} = config;
    const mins = {};

    for (let i = 1; i <= columnCount; i++) mins[i] = 40;
    if (!db) return mins;

    const count = db[tableType].count;
    const text_1 = String(count);
    const w_1 = measureTextWidth(text_1, false) + 24;
    mins[1] = Math.max(mins[1], Math.ceil(w_1));

    const nameColIndex = config.tableId === "mobs" ? 2 : 3;
    const idColIndex = config.tableId === "mobs" ? 3 : 2;

    const w_id = measureTextWidth("ID", false) + 32;
    mins[idColIndex] = Math.max(mins[idColIndex], Math.ceil(w_id));

    mins[nameColIndex] = Math.max(mins[nameColIndex], 24);

    if (config.headingColumns) config.headingColumns.forEach(({index, label}) => {
        const w = measureTextWidth(label, false) + 32;
        mins[index] = Math.max(mins[index], Math.ceil(w));
    });

    if (config.flagsColumn) {
        const {index, flagChecks} = config.flagsColumn;
        let maxFlags = 1;

        for (let i = 0; i < count; i++) {
            const item = db[tableType].get(i);
            if (!item) continue;
            let fc = 0;
            const f = item.flags;
            flagChecks.forEach(check => {
                if (check(f)) fc++;
            });
            if (fc > maxFlags) maxFlags = fc;
        }

        const headingWidth = measureTextWidth("Flags", false) + 28;
        const contentWidth = maxFlags * 16 + Math.max(0, maxFlags - 1) * 3 + 24;
        mins[index] = Math.max(headingWidth, contentWidth);
    }

    return mins;
}

export function initColumnResizers(headId, tableId, cssVarPrefix, defaults, getMinWidths) {
    const head = document.getElementById(headId);
    if (!head) return;

    const handles = head.querySelectorAll(".col-drag-handle");
    const columnCount = parseInt(head.dataset.columnCount) || 7;
    const storageKey = `${tableId}-v2-col-width-pct`;

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
    if (!config.tableId || !config.cssVarPrefix || !config.columnCount) {
        throw new Error("setupResizableTable requires tableId, cssVarPrefix, and columnCount");
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