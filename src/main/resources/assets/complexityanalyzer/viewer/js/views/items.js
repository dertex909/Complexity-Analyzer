import {
    escapeHtml,
    debounce,
    formatComplexity,
    formatRawTooltip,
    getItemFlags,
    fmt,
    fmtInt
} from "../core/utils.js";
import { state, setFilter, selectItem } from "../core/state.js";
import { ITEM_FLAG } from "../core/cabin.js";
import { mountVirtualList } from "../components/virtual-list.js";
import { renderItemDetail } from "./details/item-detail.js";

let activePopover = null;

function closeActivePopover() {
    if (activePopover) {
        activePopover.remove();
        activePopover = null;
    }
}

// Global click listener to close popovers on outer click
document.addEventListener("click", (e) => {
    if (activePopover && !activePopover.contains(e.target) && !e.target.closest(".clickable-header")) {
        closeActivePopover();
    }
});

export function renderItems(container) {
    const db = state.db;
    if (!db) return;

    loadColumnWidths();

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
        <div class="table-head items-grid" id="items-head">
            <div class="th-cell" data-index="1"><span class="th-text">№</span><div class="col-drag-handle"></div></div>
            <div class="th-cell clickable-header" data-index="2" data-filter="id"><span class="th-text">ID <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell" data-index="3"><span class="th-text" style="padding-right: 12px;">Name</span><div class="col-drag-handle"></div></div>
            <div class="th-cell num clickable-header" data-index="4" data-filter="complexity"><span class="th-text">Complexity <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell num clickable-header" data-index="5" data-filter="depth"><span class="th-text">Depth <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell num clickable-header" data-index="6" data-filter="totalIngredients"><span class="th-text">Total Ingredients <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell num clickable-header" data-index="7" data-filter="usageCount"><span class="th-text">Usage <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell clickable-header" data-index="8" data-filter="category"><span class="th-text">Category <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell clickable-header" data-index="9" data-filter="flags"><span class="th-text">Flags <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
        </div>
        <div id="items-list"></div>
    `;

    wireItemFilters();
    updateItemsView();

    if (state.selectedItem >= 0) {
        renderItemDetail(container, state.selectedItem);
    }
}

function loadColumnWidths() {
    const defaults = {
        1: "4%",
        2: "12%",
        3: "28%",
        4: "11%",
        5: "7%",
        6: "10%",
        7: "8%",
        8: "12%",
        9: "8%"
    };
    for (let i = 1; i <= 9; i++) {
        const w = localStorage.getItem(`col-width-pct-${i}`) || defaults[i];
        document.documentElement.style.setProperty(`--col-${i}`, w);
    }
}

function measureTextWidth(text, isMono = false) {
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

function getDynamicMinColumnWidths() {
    const db = state.db;
    const mins = {
        1: 50,
        2: 40,
        3: 24, // Safety padding limit (20px) + 4px for some content representation
        4: 60,
        5: 45,
        6: 80,
        7: 45,
        8: 60,
        9: 40
    };
    if (!db) return mins;

    // Col 1 (№)
    const count = db.items.count;
    const text_1 = String(count);
    const w_1 = measureTextWidth(text_1, false) + 24; // text + padding
    mins[1] = Math.max(45, Math.ceil(w_1));

    // Col 2 (ID) - Heading is "ID" + indicator (approx 12px) + padding
    const w_2 = measureTextWidth("ID", false) + 32;
    mins[2] = Math.max(35, Math.ceil(w_2));

    // Col 3 (Name) - keep very small as requested ("can shrink to 10px", but safety limit is 24px because of padding)
    mins[3] = 24;

    // Col 4 (Complexity) - "Complexity" heading + indicator + padding
    const w_4 = measureTextWidth("Complexity", false) + 32;
    mins[4] = Math.max(50, Math.ceil(w_4));

    // Col 5 (Depth) - "Depth" heading + indicator + padding
    const w_5 = measureTextWidth("Depth", false) + 32;
    mins[5] = Math.max(40, Math.ceil(w_5));

    // Col 6 (Total Ingredients) - "Total Ingredients" + indicator + padding
    const w_6 = measureTextWidth("Total Ingredients", false) + 32;
    mins[6] = Math.max(60, Math.ceil(w_6));

    // Col 7 (Usage) - "Usage" + indicator + padding
    const w_7 = measureTextWidth("Usage", false) + 32;
    mins[7] = Math.max(40, Math.ceil(w_7));

    // Col 8 (Category) - "Category" + indicator + padding
    const w_8 = measureTextWidth("Category", false) + 32;
    mins[8] = Math.max(50, Math.ceil(w_8));

    // Col 9 (Flags) - Max flags dynamically calculated based on actual flags combinations in DB
    let maxFlags = 0;
    for (let i = 0; i < count; i++) {
        const it = db.items.get(i);
        let currentFlags = 0;
        const f = it.flags;
        if (f & ITEM_FLAG.HAS_CYCLE) currentFlags++;
        if (f & ITEM_FLAG.IS_INFINITE) currentFlags++;
        if (!(f & ITEM_FLAG.HAS_RECIPE)) currentFlags++;
        if (f & ITEM_FLAG.IS_HARDCODED) currentFlags++;
        if (currentFlags > maxFlags) maxFlags = currentFlags;
    }
    const heading_9_width = measureTextWidth("Flags", false) + 28;
    const content_9_width = maxFlags * 16 + Math.max(0, maxFlags - 1) * 3 + 24;
    mins[9] = Math.max(heading_9_width, content_9_width);

    return mins;
}

function initColumnResizers() {
    const head = document.getElementById("items-head");
    if (!head) return;
    const handles = head.querySelectorAll(".col-drag-handle");
    handles.forEach(handle => {
        handle.addEventListener("pointerdown", (e) => {
            e.preventDefault();
            e.stopPropagation();
            closeActivePopover();

            const cell = handle.closest(".th-cell");
            const index = parseInt(cell.dataset.index, 10);
            if (index >= 9) return; // Last column has no next column to resize against

            const startX = e.clientX;
            const rightPadding = parseFloat(window.getComputedStyle(head).paddingRight || 0);
            const totalWidth = head.getBoundingClientRect().width - rightPadding;

            const defaults = {
                1: 4,
                2: 12,
                3: 28,
                4: 11,
                5: 7,
                6: 10,
                7: 8,
                8: 12,
                9: 8
            };
            const startPct = {};
            for (let i = 1; i <= 9; i++) {
                const val = document.documentElement.style.getPropertyValue(`--col-${i}`);
                startPct[i] = parseFloat(val) || defaults[i];
            }

            handle.classList.add("dragging");
            handle.setPointerCapture(e.pointerId);

            const minWidths = getDynamicMinColumnWidths();

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

                document.documentElement.style.setProperty(`--col-${i}`, `${newPct_i}%`);
                document.documentElement.style.setProperty(`--col-${j}`, `${newPct_j}%`);

                localStorage.setItem(`col-width-pct-${i}`, `${newPct_i}%`);
                localStorage.setItem(`col-width-pct-${j}`, `${newPct_j}%`);

                // Dispatch window resize event so that virtual scroll knows layout updated
                window.dispatchEvent(new Event('resize'));
            };

            const onPointerUp = (upEvent) => {
                handle.classList.remove("dragging");
                try {
                    handle.releasePointerCapture(upEvent.pointerId);
                } catch (err) {}
                handle.removeEventListener("pointermove", onPointerMove);
                handle.removeEventListener("pointerup", onPointerUp);
            };

            handle.addEventListener("pointermove", onPointerMove);
            handle.addEventListener("pointerup", onPointerUp);
        });
    });
}

function updateHeaderIndicators() {
    const f = state.filters.items;
    const head = document.getElementById("items-head");
    if (!head) return;

    const itemsDef = [
        { key: "id", isFiltered: () => f.modsFilter && f.modsFilter.length > 0 },
        { key: "complexity", isFiltered: () => f.minComplexity !== "" || f.maxComplexity !== "" },
        { key: "depth", isFiltered: () => f.minDepth !== "" || f.maxDepth !== "" },
        { key: "totalIngredients", isFiltered: () => f.minTotalIngredients !== "" || f.maxTotalIngredients !== "" },
        { key: "usageCount", isFiltered: () => f.minRecipeUsages !== "" || f.maxRecipeUsages !== "" },
        { key: "category", isFiltered: () => f.categoriesFilter && f.categoriesFilter.length > 0 },
        { key: "flags", isFiltered: () => f.flagsFilter && f.flagsFilter.length < 4 },
    ];

    itemsDef.forEach(item => {
        const el = head.querySelector(`[data-filter="${item.key}"]`);
        if (el) {
            el.classList.toggle("filtered", item.isFiltered());
        }
    });
}

function wireItemFilters() {
    const onInput = debounce((key, val) => setFilter("items", { [key]: val }), 120);
    $("items-query").addEventListener("input", e => onInput("query", e.target.value));
    $("items-sort").addEventListener("change", e => { setFilter("items", { sort: e.target.value }); updateItemsView(); });

    // Clickable header filters wiring
    const head = document.getElementById("items-head");
    if (head) {
        head.querySelectorAll(".clickable-header").forEach(hdr => {
            hdr.addEventListener("click", (e) => {
                if (e.target.classList.contains("col-drag-handle")) return;
                openPopover(hdr, hdr.dataset.filter);
            });
        });
    }

    initColumnResizers();
}

function openPopover(headerCell, filterType) {
    closeActivePopover();

    const pop = document.createElement("div");
    pop.className = "filter-popover";
    document.body.appendChild(pop);
    activePopover = pop;

    let popWidth = 240;
    if (filterType === "category") popWidth = 250;
    else if (filterType === "id") popWidth = 200;
    else if (filterType === "flags") popWidth = 180;

    pop.style.width = `${popWidth}px`;

    const rect = headerCell.getBoundingClientRect();
    let leftPos = rect.left;
    const viewportWidth = document.documentElement.clientWidth;
    if (leftPos + popWidth > viewportWidth) {
        leftPos = viewportWidth - popWidth - 16;
    }
    pop.style.left = `${Math.max(10, leftPos)}px`;
    pop.style.top = `${rect.bottom + 4}px`;

    const f = state.filters.items;
    const db = state.db;

    if (filterType === "complexity" || filterType === "depth" || filterType === "totalIngredients" || filterType === "usageCount") {
        let minKey, maxKey;
        if (filterType === "complexity") { minKey = "minComplexity"; maxKey = "maxComplexity"; }
        else if (filterType === "depth") { minKey = "minDepth"; maxKey = "maxDepth"; }
        else if (filterType === "totalIngredients") { minKey = "minTotalIngredients"; maxKey = "maxTotalIngredients"; }
        else { minKey = "minRecipeUsages"; maxKey = "maxRecipeUsages"; }

        const minVal = f[minKey] ?? "";
        const maxVal = f[maxKey] ?? "";

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div style="font-size:11px; color:var(--text-dim); margin:2px 0 4px 0;">Define range:</div>
            <div class="range-inputs">
                <input type="number" id="filter-min-input" placeholder="From" value="${minVal}">
                <span class="range-label">—</span>
                <input type="number" id="filter-max-input" placeholder="To" value="${maxVal}">
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("items", { [minKey]: "", [maxKey]: "" });
            updateItemsView();
            closeActivePopover();
        });

        const onRangeChange = () => {
            const minV = pop.querySelector("#filter-min-input").value;
            const maxV = pop.querySelector("#filter-max-input").value;
            setFilter("items", { [minKey]: minV, [maxKey]: maxV });
            updateItemsView();
        };

        pop.querySelector("#filter-min-input").addEventListener("input", debounce(onRangeChange, 200));
        pop.querySelector("#filter-max-input").addEventListener("input", debounce(onRangeChange, 200));

    } else if (filterType === "category") {
        const selectedCats = f.categoriesFilter || [];
        const categoriesSet = new Set(db.categories.map(c => c.name));
        categoriesSet.add("Uncalculable");
        const categoriesList = Array.from(categoriesSet).sort();

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${categoriesList.map(cat => {
                    const checked = selectedCats.length === 0 || selectedCats.includes(cat);
                    return `
                        <label class="checkbox-item">
                            <input type="checkbox" class="cat-cb" value="${escapeHtml(cat)}" ${checked ? "checked" : ""}>
                            <span>${escapeHtml(cat)}</span>
                        </label>
                    `;
                }).join("")}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("items", { categoriesFilter: [] });
            updateItemsView();
            closeActivePopover();
        });

        pop.querySelectorAll(".cat-cb").forEach(cb => {
            cb.addEventListener("change", () => {
                const checkboxes = pop.querySelectorAll(".cat-cb");
                const checkedValues = [];
                checkboxes.forEach(c => {
                    if (c.checked) checkedValues.push(c.value);
                });
                if (checkedValues.length === checkboxes.length || checkedValues.length === 0) {
                    setFilter("items", { categoriesFilter: [] });
                } else {
                    setFilter("items", { categoriesFilter: checkedValues });
                }
                updateItemsView();
            });
        });

    } else if (filterType === "id") {
        const selectedMods = f.modsFilter || [];

        const allMods = new Set();
        for (let i = 0; i < db.items.count; i++) {
            const it = db.items.get(i);
            const colonIdx = it.id.indexOf(":");
            const mod = colonIdx >= 0 ? it.id.substring(0, colonIdx) : "minecraft";
            allMods.add(mod);
        }
        const modsList = Array.from(allMods).sort();

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${modsList.map(mod => {
                    const checked = selectedMods.length === 0 || selectedMods.includes(mod);
                    return `
                        <label class="checkbox-item">
                            <input type="checkbox" class="mod-cb" value="${escapeHtml(mod)}" ${checked ? "checked" : ""}>
                            <span>${escapeHtml(mod)}</span>
                        </label>
                    `;
                }).join("")}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("items", { modsFilter: [] });
            updateItemsView();
            closeActivePopover();
        });

        pop.querySelectorAll(".mod-cb").forEach(cb => {
            cb.addEventListener("change", () => {
                const checkboxes = pop.querySelectorAll(".mod-cb");
                const checkedValues = [];
                checkboxes.forEach(c => {
                    if (c.checked) checkedValues.push(c.value);
                });
                if (checkedValues.length === checkboxes.length || checkedValues.length === 0) {
                    setFilter("items", { modsFilter: [] });
                } else {
                    setFilter("items", { modsFilter: checkedValues });
                }
                updateItemsView();
            });
        });

    } else if (filterType === "flags") {
        const selectedFlags = f.flagsFilter || [];
        const flagsList = [
            { key: "cycle", label: "⟲ Cycle" },
            { key: "infinite", label: "∞ Unobtainable" },
            { key: "recipe", label: "∅ No Recipe" },
            { key: "hardcoded", label: "H Hardcoded" }
        ];

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${flagsList.map(flg => {
                    const checked = selectedFlags.includes(flg.key);
                    return `
                        <label class="checkbox-item">
                            <input type="checkbox" class="flag-cb" value="${escapeHtml(flg.key)}" ${checked ? "checked" : ""}>
                            <span>${escapeHtml(flg.label)}</span>
                        </label>
                    `;
                }).join("")}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("items", { flagsFilter: ["cycle", "infinite", "recipe", "hardcoded"] });
            updateItemsView();
            closeActivePopover();
        });

        pop.querySelectorAll(".flag-cb").forEach(cb => {
            cb.addEventListener("change", () => {
                const checkboxes = pop.querySelectorAll(".flag-cb");
                const checkedValues = [];
                checkboxes.forEach(c => {
                    if (c.checked) checkedValues.push(c.value);
                });
                setFilter("items", { flagsFilter: checkedValues });
                updateItemsView();
            });
        });
    }
}

function updateItemsView() {
    const db = state.db;
    const f = state.filters.items;
    const q = f.query.trim().toLowerCase();
    const list = [];
    const n = db.items.count;

    for (let i = 0; i < n; i++) {
        const it = db.items.get(i);

        // Standard text Search Query
        if (q) {
            const hay = (it.name + " " + it.id + " " + it.categoryName).toLowerCase();
            if (!hay.includes(q)) continue;
        }

        // Numeric ranges: Complexity
        const compVal = it.complexity;
        if (f.minComplexity !== "" && compVal < parseFloat(f.minComplexity)) continue;
        if (f.maxComplexity !== "" && compVal > parseFloat(f.maxComplexity)) continue;

        // Numeric ranges: Depth
        const depthVal = it.depth;
        if (f.minDepth !== "" && depthVal < parseInt(f.minDepth, 10)) continue;
        if (f.maxDepth !== "" && depthVal > parseInt(f.maxDepth, 10)) continue;

        // Numeric ranges: Total Ingredients
        const ingVal = it.totalIngredients;
        if (f.minTotalIngredients !== "" && ingVal < parseInt(f.minTotalIngredients, 10)) continue;
        if (f.maxTotalIngredients !== "" && ingVal > parseInt(f.maxTotalIngredients, 10)) continue;

        // Numeric ranges: Recipe Usages
        const useVal = it.usageCount;
        if (f.minRecipeUsages !== "" && useVal < parseInt(f.minRecipeUsages, 10)) continue;
        if (f.maxRecipeUsages !== "" && useVal > parseInt(f.maxRecipeUsages, 10)) continue;

        // Category Checkbox Lists
        const catName = it.categoryName;
        if (f.categoriesFilter && f.categoriesFilter.length > 0 && !f.categoriesFilter.includes(catName)) continue;

        // Mod ID Checkbox Lists
        const colonIdx = it.id.indexOf(":");
        const modId = colonIdx >= 0 ? it.id.substring(0, colonIdx) : "minecraft";
        if (f.modsFilter && f.modsFilter.length > 0 && !f.modsFilter.includes(modId)) continue;

        // Flags filters
        if (f.flagsFilter) {
            if (!f.flagsFilter.includes("cycle") && (it.flags & ITEM_FLAG.HAS_CYCLE)) continue;
            if (!f.flagsFilter.includes("infinite") && (it.flags & ITEM_FLAG.IS_INFINITE)) continue;
            if (!f.flagsFilter.includes("recipe") && !(it.flags & ITEM_FLAG.HAS_RECIPE)) continue;
            if (!f.flagsFilter.includes("hardcoded") && (it.flags & ITEM_FLAG.IS_HARDCODED)) continue;
        }

        list.push(it);
    }

    // Sorting
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

    if (list.length === 0) {
        $("items-list").innerHTML = `
            <div class="virtual-viewport" style="display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; width: 100%; flex: 1;">
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">🔍</div>
                    <div class="message">No items match your filter.</div>
                </div>
            </div>`;
        return;
    }

    const vl = mountVirtualList($("items-list"), {
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
            el.addEventListener("click", () => { selectItem(it.index); });
            return el;
        }
    });
}

function $(id) { return document.getElementById(id); }