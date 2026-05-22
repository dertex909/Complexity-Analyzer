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

const SOURCE_TYPE_TRANSLATIONS = {
    "loot_table": "Loot Tables",
    "world_gen": "World Gen",
    "piglin_barter": "Piglin Barter",
    "ore": "Ore Veins",
    "slaying": "Slaying",
    "crop_drop": "Crop Drops",
    "bee_drop": "Bee Drops",
    "quest": "Quests",
    "trading": "Trading",
    "fishing": "Fishing",
    "alchemy": "Alchemy",
    "archaeology": "Archaeology"
};

export function formatSourceTypeName(name) {
    if (!name) return "";
    if (SOURCE_TYPE_TRANSLATIONS[name]) return SOURCE_TYPE_TRANSLATIONS[name];

    // Remove raw prefix like Complexityanalyzer.source_type. (case-insensitive)
    let clean = name;
    const rawPrefix = /^complexityanalyzer\.source_type\./i;
    if (rawPrefix.test(clean)) {
        clean = clean.replace(rawPrefix, "");
    }

    const rawDisplayPrefix = /^complexityanalyzer\.source\s+type\./i;
    if (rawDisplayPrefix.test(clean)) {
        clean = clean.replace(rawDisplayPrefix, "");
    }

    // Split words, capitalize each, join back
    let displayName = clean.split('_')
        .map(w => w.charAt(0).toUpperCase() + w.slice(1))
        .join(' ');

    const displayPrefix = /^complexityanalyzer\.source\s+type\./i;
    if (displayPrefix.test(displayName)) {
        displayName = displayName.replace(displayPrefix, "");
    }

    // Capitalize the first letter of the overall result for beauty
    if (displayName.length > 0) {
        displayName = displayName.charAt(0).toUpperCase() + displayName.slice(1);
    }
    return displayName;
}

let resolvedSourceTypes = null;

async function ensureSourceTypes(db) {
    if (resolvedSourceTypes) return resolvedSourceTypes;

    const types = [];
    for (const entry of db.sourceTypes) {
        let name = "";
        if (entry.items.length > 0) {
            const itIdx = entry.items[0];
            const base = await db.getItemBaseData(itIdx);
            if (base && base.sourceTypeEnum === entry.typeEnum) {
                name = base.sourceType;
            } else {
                const sources = await db.getItemSources(itIdx);
                const matched = sources.find(s => s.sourceTypeEnum === entry.typeEnum);
                if (matched) {
                    name = matched.sourceType;
                }
            }
        }
        if (!name) name = `type_${entry.typeEnum}`;
        types.push({
            typeEnum: entry.typeEnum,
            items: entry.items,
            rawName: name,
            displayName: formatSourceTypeName(name)
        });
    }

    // Sort by count of items descending
    types.sort((a, b) => b.items.length - a.items.length);
    resolvedSourceTypes = types;
    return resolvedSourceTypes;
}

let currentListInstance = null;
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

function loadColumnWidths() {
    const defaults = {
        1: "6%",
        2: "22%",
        3: "38%",
        4: "20%",
        5: "14%"
    };
    for (let i = 1; i <= 5; i++) {
        const w = localStorage.getItem(`src-col-width-pct-${i}`) || defaults[i];
        document.documentElement.style.setProperty(`--src-col-${i}`, w);
    }
}

function getDynamicMinColumnWidths() {
    return {
        1: 45,
        2: 40,
        3: 24,
        4: 50,
        5: 40
    };
}

function initColumnResizers() {
    const head = document.getElementById("sources-head");
    if (!head) return;
    const handles = head.querySelectorAll(".col-drag-handle");
    handles.forEach(handle => {
        handle.addEventListener("pointerdown", (e) => {
            e.preventDefault();
            e.stopPropagation();
            closeActivePopover();

            const cell = handle.closest(".th-cell");
            const index = parseInt(cell.dataset.index, 10);
            if (index >= 5) return; // Last column has no next column to resize against

            const startX = e.clientX;
            const rightPadding = parseFloat(window.getComputedStyle(head).paddingRight || 0);
            const totalWidth = head.getBoundingClientRect().width - rightPadding;

            const defaults = {
                1: 6,
                2: 22,
                3: 38,
                4: 20,
                5: 14
            };
            const startPct = {};
            for (let i = 1; i <= 5; i++) {
                const val = document.documentElement.style.getPropertyValue(`--src-col-${i}`);
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

                document.documentElement.style.setProperty(`--src-col-${i}`, `${newPct_i}%`);
                document.documentElement.style.setProperty(`--src-col-${j}`, `${newPct_j}%`);

                localStorage.setItem(`src-col-width-pct-${i}`, `${newPct_i}%`);
                localStorage.setItem(`src-col-width-pct-${j}`, `${newPct_j}%`);

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
    const f = state.filters.sources;
    const head = document.getElementById("sources-head");
    if (!head) return;

    const itemsDef = [
        { key: "id", isFiltered: () => f.modsFilter && f.modsFilter.length > 0 },
        { key: "complexity", isFiltered: () => f.minComplexity !== "" || f.maxComplexity !== "" },
        { key: "flags", isFiltered: () => f.flagsFilter && f.flagsFilter.length < 4 },
    ];

    itemsDef.forEach(item => {
        const el = head.querySelector(`[data-filter="${item.key}"]`);
        if (el) {
            el.classList.toggle("filtered", item.isFiltered());
        }
    });
}

function openPopover(headerCell, filterType) {
    closeActivePopover();

    const pop = document.createElement("div");
    pop.className = "filter-popover";
    document.body.appendChild(pop);
    activePopover = pop;

    let popWidth = 240;
    if (filterType === "id") popWidth = 200;
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

    const f = state.filters.sources;
    const db = state.db;

    if (filterType === "complexity") {
        const minKey = "minComplexity";
        const maxKey = "maxComplexity";
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
            setFilter("sources", { [minKey]: "", [maxKey]: "" });
            updateSourcesResults(db, resolvedSourceTypes);
            closeActivePopover();
        });

        const onRangeChange = () => {
            const minV = pop.querySelector("#filter-min-input").value;
            const maxV = pop.querySelector("#filter-max-input").value;
            setFilter("sources", { [minKey]: minV, [maxKey]: maxV });
            updateSourcesResults(db, resolvedSourceTypes);
        };

        pop.querySelector("#filter-min-input").addEventListener("input", debounce(onRangeChange, 200));
        pop.querySelector("#filter-max-input").addEventListener("input", debounce(onRangeChange, 200));

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
            setFilter("sources", { modsFilter: [] });
            updateSourcesResults(db, resolvedSourceTypes);
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
                    setFilter("sources", { modsFilter: [] });
                } else {
                    setFilter("sources", { modsFilter: checkedValues });
                }
                updateSourcesResults(db, resolvedSourceTypes);
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
            setFilter("sources", { flagsFilter: ["cycle", "infinite", "recipe", "hardcoded"] });
            updateSourcesResults(db, resolvedSourceTypes);
            closeActivePopover();
        });

        pop.querySelectorAll(".flag-cb").forEach(cb => {
            cb.addEventListener("change", () => {
                const checkboxes = pop.querySelectorAll(".flag-cb");
                const checkedValues = [];
                checkboxes.forEach(c => {
                    if (c.checked) checkedValues.push(c.value);
                });
                setFilter("sources", { flagsFilter: checkedValues });
                updateSourcesResults(db, resolvedSourceTypes);
            });
        });
    }
}

export async function renderSources(container) {
    const db = state.db;
    if (!db) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">Database not initialized.</div></div>`;
        return;
    }

    loadColumnWidths();

    // Set layout parameters directly on the wrapper container
    container.style.padding = "0";
    container.style.overflow = "hidden";
    container.style.display = "flex";
    container.style.flexDirection = "row";
    container.style.gap = "0";
    container.style.alignItems = "stretch";

    const f = state.filters.sources;

    container.innerHTML = `
        <div class="sources-sidebar" style="width: 250px; flex: 0 0 250px; border-right: 1px solid var(--border); overflow-y: auto; padding: 18px; display: flex; flex-direction: column; gap: 16px; background: linear-gradient(180deg, rgba(17, 20, 24, 0.98), rgba(24, 29, 36, 0.98));">
            <div class="sidebar-header" style="display: flex; flex-direction: column; gap: 8px; padding-bottom: 14px; border-bottom: 1px solid var(--border); flex: 0 0 auto;">
                <div class="sidebar-title" style="font-size: 12px; font-weight: 700; letter-spacing: 1px; color: var(--text-muted); text-transform: uppercase; font-family: var(--sans);">Sources</div>
            </div>
            <div id="sources-categories-list" style="display: flex; flex-direction: column; gap: 4px;">
                <div class="hint">Loading sources…</div>
            </div>
        </div>
        <div class="sources-main" style="flex: 1; display: flex; flex-direction: column; overflow: hidden;">
            <div class="controls" id="sources-controls">
                <input type="search" id="sources-query" placeholder="Filter items by name, ID..." value="${escapeHtml(f.query)}" autocomplete="off">
                <select id="sources-sort">
                    <option value="complexity-desc" ${f.sort === "complexity-desc" ? "selected" : ""}>Source Complexity ▼</option>
                    <option value="complexity-asc" ${f.sort === "complexity-asc" ? "selected" : ""}>Source Complexity ▲</option>
                    <option value="name-asc" ${f.sort === "name-asc" ? "selected" : ""}>Name A-Z</option>
                    <option value="id-asc" ${f.sort === "id-asc" ? "selected" : ""}>ID A-Z</option>
                </select>
                <span class="flex-grow"></span>
                <span class="chip" id="sources-count">0 items</span>
            </div>
            <div class="table-head sources-grid" id="sources-head">
                <div class="th-cell" data-index="1"><span class="th-text">№</span><div class="col-drag-handle"></div></div>
                <div class="th-cell clickable-header" data-index="2" data-filter="id"><span class="th-text">ID <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
                <div class="th-cell" data-index="3"><span class="th-text" style="padding-right: 12px;">Name</span><div class="col-drag-handle"></div></div>
                <div class="th-cell num clickable-header" data-index="4" data-filter="complexity"><span class="th-text">Source Complexity <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
                <div class="th-cell clickable-header" data-index="5" data-filter="flags"><span class="th-text">Flags <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            </div>
            <div id="sources-list" style="flex: 1; min-height: 0; overflow: hidden; position: relative;"></div>
        </div>
    `;

    try {
        const types = await ensureSourceTypes(db);
        if (types.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="icon">❔</div><div class="message">No base sources found in the database.</div></div>`;
            return;
        }

        // Auto select first category if none or invalid selected
        if (state.filters.sources.sourceType === null || !types.some(t => t.typeEnum === state.filters.sources.sourceType)) {
            state.filters.sources.sourceType = types[0].typeEnum;
        }

        const catList = container.querySelector("#sources-categories-list");
        renderCategoryButtons(catList, types);

        const queryInput = container.querySelector("#sources-query");
        queryInput.addEventListener("input", debounce((e) => {
            setFilter("sources", { query: e.target.value });
            updateSourcesResults(db, types);
        }, 120));

        const sortSelect = container.querySelector("#sources-sort");
        sortSelect.addEventListener("change", (e) => {
            setFilter("sources", { sort: e.target.value });
            updateSourcesResults(db, types);
        });

        // Clickable headers wiring
        const head = container.querySelector("#sources-head");
        if (head) {
            head.querySelectorAll(".clickable-header").forEach(hdr => {
                hdr.addEventListener("click", (e) => {
                    if (e.target.classList.contains("col-drag-handle")) return;
                    openPopover(hdr, hdr.dataset.filter);
                });
            });
        }

        initColumnResizers();
        updateSourcesResults(db, types);

    } catch (e) {
        container.innerHTML = `<div style="padding: 20px; color:var(--err)">Error loading base sources: ${escapeHtml(String(e))}</div>`;
    }
}

function renderCategoryButtons(containerEl, types) {
    const activeEnum = state.filters.sources.sourceType;
    containerEl.innerHTML = types.map(t => `
        <button class="tab ${t.typeEnum === activeEnum ? "active" : ""}" data-type="${t.typeEnum}" style="display:flex; justify-content:space-between; align-items:center; width:100%; padding: 10px 14px; font-size:13px; font-weight: 500;">
            <span style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding-right:8px;" title="${escapeHtml(t.displayName)}">${escapeHtml(t.displayName)}</span>
            <span class="chip" style="font-size:10px; padding:1px 6px; background:rgba(255,255,255,0.06); font-family:var(--mono); color: var(--text-muted); border-radius: 10px;">${t.items.length}</span>
        </button>
    `).join("");

    containerEl.querySelectorAll("button").forEach(btn => {
        btn.addEventListener("click", () => {
            const typeEnum = parseInt(btn.dataset.type, 10);
            state.filters.sources.sourceType = typeEnum;
            containerEl.querySelectorAll("button").forEach(b => b.classList.toggle("active", b === btn));
            
            const types = resolvedSourceTypes;
            const db = state.db;
            if (db && types) {
                updateSourcesResults(db, types);
            }
        });
    });
}

async function ensureSourceComplexities(db, activeType) {
    if (activeType.sourceComplexities) return activeType.sourceComplexities;

    if (activeType.items.length > 0) {
        try {
            await db.getItemSources(activeType.items[0]);
        } catch (e) {
            console.error(e);
        }
    }

    const complexities = new Map();
    const promises = activeType.items.map(async itemIdx => {
        const item = db.items.get(itemIdx);
        if (!item) return;

        let sc = item.complexity; // Default fallback to item's global complexity
        try {
            const sources = await db.getItemSources(itemIdx);
            const matched = sources.find(s => s.sourceTypeEnum === activeType.typeEnum);
            if (matched && isFinite(matched.estimatedCost) && matched.estimatedCost >= 0) {
                sc = matched.estimatedCost;
            }
        } catch (e) {
            console.error("Error loading source complexity for", itemIdx, e);
        }
        complexities.set(itemIdx, sc);
    });

    await Promise.all(promises);
    activeType.sourceComplexities = complexities;
    return complexities;
}

async function updateSourcesResults(db, types) {
    if (currentListInstance) {
        currentListInstance.destroy();
        currentListInstance = null;
    }

    const activeEnum = state.filters.sources.sourceType;
    const activeType = types.find(t => t.typeEnum === activeEnum);
    if (!activeType) return;

    const listContainer = document.getElementById("sources-list");
    if (!activeType.sourceComplexities && listContainer) {
        listContainer.innerHTML = `<div class="hint" style="padding: 20px;">Calculating source complexities…</div>`;
    }

    const sourceComplexities = await ensureSourceComplexities(db, activeType);

    const f = state.filters.sources;
    const q = (f.query || "").trim().toLowerCase();
    
    // Filter and resolve items
    const filteredItems = [];
    for (const itemIndex of activeType.items) {
        const it = db.items.get(itemIndex);
        if (!it) continue;

        // Text query
        if (q) {
            const hay = (it.name + " " + it.id + " " + (it.categoryName || "")).toLowerCase();
            if (!hay.includes(q)) continue;
        }

        // Complexity range
        const compVal = sourceComplexities.get(itemIndex) ?? it.complexity;
        if (f.minComplexity !== "" && compVal < parseFloat(f.minComplexity)) continue;
        if (f.maxComplexity !== "" && compVal > parseFloat(f.maxComplexity)) continue;

        // Mod ID filter
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

        filteredItems.push(it);
    }

    // Sorting
    const [field, dir] = f.sort.split("-");
    const sign = dir === "asc" ? 1 : -1;
    const getVal = {
        complexity: x => {
            const sc = sourceComplexities.get(x.index);
            return isFinite(sc) ? sc : Number.MAX_VALUE;
        },
        name: x => x.name,
        id: x => x.id,
    }[field] || (x => {
        const sc = sourceComplexities.get(x.index);
        return isFinite(sc) ? sc : Number.MAX_VALUE;
    });
    
    filteredItems.sort((a, b) => {
        const av = getVal(a), bv = getVal(b);
        if (typeof av === "number") return sign * (av - bv);
        return sign * String(av).localeCompare(String(bv));
    });

    const countEl = document.getElementById("sources-count");
    if (countEl) {
        countEl.textContent = `${fmtInt.format(filteredItems.length)} / ${fmtInt.format(activeType.items.length)} items`;
    }

    updateHeaderIndicators();

    if (!listContainer) return;

    if (filteredItems.length === 0) {
        listContainer.innerHTML = `
            <div class="virtual-viewport" style="display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; width: 100%; flex: 1;">
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">🔍</div>
                    <div class="message">No items match your filter.</div>
                </div>
            </div>`;
        return;
    }

    currentListInstance = mountVirtualList(listContainer, {
        itemCount: filteredItems.length,
        itemHeight: 28,
        renderRow: (absIndex) => {
            const it = filteredItems[absIndex];
            const sc = sourceComplexities.get(it.index) ?? it.complexity;
            const el = document.createElement("div");
            el.className = "row sources-grid";
            el.style.cursor = "pointer";
            el.innerHTML = `
                <span class="idx">${absIndex + 1}</span>
                <span class="id" title="${it.id}">${it.id}</span>
                <span title="${escapeHtml(it.name)}">${escapeHtml(it.name)}</span>
                <span class="num cat-${it.categoryName || "Uncalculable"}" title="${formatRawTooltip(sc)}">${formatComplexity(sc)}</span>
                <span class="flags">${getItemFlags(it, true)}</span>
            `;
            el.addEventListener("click", () => selectItem(it.index));
            return el;
        }
    });
}