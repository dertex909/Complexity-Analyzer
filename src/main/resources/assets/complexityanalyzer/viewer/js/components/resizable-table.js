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

function calculateInitialWidths(config) {
    const {columnCount, headingColumns, db, tableType} = config;

    const pixelWidths = {};
    for (let i = 1; i <= columnCount; i++) {
        pixelWidths[i] = 40;
    }

    if (db) {
        const count = db[tableType].count;
        const text_1 = String(count);
        const w_1 = measureTextWidth(text_1, false) + 24;
        pixelWidths[1] = Math.max(pixelWidths[1], Math.ceil(w_1));
    }

    const w_2 = measureTextWidth("ID", false) + 32;
    pixelWidths[2] = Math.max(pixelWidths[2], Math.ceil(w_2));

    pixelWidths[3] = Math.max(pixelWidths[3], 24);

    if (headingColumns) {
        headingColumns.forEach(({index, label}) => {
            const headingWidth = measureTextWidth(label, false) + 40;
            pixelWidths[index] = Math.max(pixelWidths[index] || 40, headingWidth);
        });
    }

    let totalMinWidth = 0;
    for (let i = 1; i <= columnCount; i++) {
        totalMinWidth += pixelWidths[i] || 40;
    }

    const percentages = {};
    for (let i = 1; i <= columnCount; i++) {
        const pct = ((pixelWidths[i] || 40) / totalMinWidth) * 100;
        percentages[i] = `${Math.round(pct)}%`;
    }

    return percentages;
}

export function loadColumnWidths(tableId, config, cssVarPrefix) {
    if (tableId === "fluids") for (let i = 1; i <= 9; i++) {
        localStorage.removeItem(`fl-col-width-pct-${i}`);
    }

    const storageKey = tableId === "fluids" ? `fl-v2-col-width-pct` : `${tableId}-col-width-pct`;
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

    const w_2 = measureTextWidth("ID", false) + 32;
    mins[2] = Math.max(mins[2], Math.ceil(w_2));

    mins[3] = Math.max(mins[3], 24);

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
    const storageKey = tableId === "fluids" ? `fl-v2-col-width-pct` : `${tableId}-col-width-pct`;

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

                const newPct_i = startPct[i] + clampedDeltaPct;
                const newPct_j = startPct[j] - clampedDeltaPct;

                document.documentElement.style.setProperty(`${cssVarPrefix}-${i}`, `${newPct_i}%`);
                document.documentElement.style.setProperty(`${cssVarPrefix}-${j}`, `${newPct_j}%`);

                localStorage.setItem(`${storageKey}-${i}`, `${newPct_i}%`);
                localStorage.setItem(`${storageKey}-${j}`, `${newPct_j}%`);

                window.dispatchEvent(new Event('resize'));
            };

            const onPointerUp = (upEvent) => {
                handle.classList.remove("dragging");
                try {
                    handle.releasePointerCapture(upEvent.pointerId);
                } catch (err) {
                }
                handle.removeEventListener("pointermove", onPointerMove);
                handle.removeEventListener("pointerup", onPointerUp);
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