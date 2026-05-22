import {
    escapeHtml,
    debounce,
    formatComplexity,
    formatRawTooltip,
    getItemFlags,
    fmtInt
} from "../core/utils.js";
import {state, setFilter, selectItem, getDefaultFilters} from "../core/state.js";
import {ITEM_FLAG} from "../core/cabin.js";
import {mountVirtualList} from "../components/virtual-list.js";
import {
    closeActivePopover,
    setupResizableTable
} from "../components/resizable-table.js";
import {generateTableHeader} from "../components/table-columns.js";
import {
    createPopover,
    getModNamespaces,
    renderModCheckboxes,
    renderFlagCheckboxes,
    renderRangeInputs,
    wireModCheckboxes,
    wireFlagCheckboxes,
    wireRangeInputs
} from "../components/filter-popover.js";
import {
    passesModFilter,
    passesFlagsFilter,
    passesRangeFilter
} from "../components/item-filter.js";

const SOURCES_COLUMNS = [
    {index: 1, label: "№", filter: null, sortable: false},
    {index: 2, label: "ID", filter: "id", sortable: true},
    {index: 3, label: "Name", filter: null, sortable: true},
    {index: 4, label: "Source Complexity", filter: "complexity", sortable: true, numeric: true},
    {index: 5, label: "Flags", filter: "flags", sortable: false}
];

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

    let clean = name;
    const rawPrefix = /^complexityanalyzer\.source_type\./i;
    if (rawPrefix.test(clean)) clean = clean.replace(rawPrefix, "");

    const rawDisplayPrefix = /^complexityanalyzer\.source\s+type\./i;
    if (rawDisplayPrefix.test(clean)) clean = clean.replace(rawDisplayPrefix, "");

    let displayName = clean.split('_')
        .map(w => w.charAt(0).toUpperCase() + w.slice(1))
        .join(' ');

    const displayPrefix = /^complexityanalyzer\.source\s+type\./i;
    if (displayPrefix.test(displayName)) displayName = displayName.replace(displayPrefix, "");

    if (displayName.length > 0) displayName = displayName.charAt(0).toUpperCase() + displayName.slice(1);
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
                if (matched) name = matched.sourceType;
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

    types.sort((a, b) => b.items.length - a.items.length);
    resolvedSourceTypes = types;
    return resolvedSourceTypes;
}

let currentListInstance = null;

function updateHeaderIndicators() {
    const f = state.filters.sources;
    const head = document.getElementById("sources-head");
    if (!head) return;

    const itemsDef = [
        {key: "id", isFiltered: () => f.modsFilter && f.modsFilter.length > 0},
        {key: "complexity", isFiltered: () => f.minComplexity !== "" || f.maxComplexity !== ""},
        {key: "flags", isFiltered: () => f.flagsFilter && f.flagsFilter.length < 4},
    ];

    itemsDef.forEach(item => {
        const el = head.querySelector(`[data-filter="${item.key}"]`);
        if (el) el.classList.toggle("filtered", item.isFiltered());
    });
}

function openPopover(headerCell, filterType) {
    const pop = createPopover(headerCell, filterType);

    const f = state.filters.sources;
    const db = state.db;

    if (filterType === "complexity") {
        const minKey = "minComplexity";
        const maxKey = "maxComplexity";
        const minVal = f[minKey] ?? "";
        const maxVal = f[maxKey] ?? "";

        pop.innerHTML = renderRangeInputs(minKey, maxKey, minVal, maxVal);

        pop.querySelector("#filter-reset-btn").addEventListener("click", async () => {
            setFilter("sources", getDefaultFilters("sources"));
            await updateSourcesResults(db, resolvedSourceTypes);
            closeActivePopover();
        });

        wireRangeInputs(pop, minKey, maxKey, async (vals) => {
            setFilter("sources", vals);
            await updateSourcesResults(db, resolvedSourceTypes);
        }, debounce);

    } else if (filterType === "id") {
        const modsList = getModNamespaces(db, "items");

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderModCheckboxes(modsList, f.modsFilter || [])}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", async () => {
            setFilter("sources", getDefaultFilters("sources"));
            await updateSourcesResults(db, resolvedSourceTypes);
            closeActivePopover();
        });

        wireModCheckboxes(pop, modsList, async (checkedMods) => {
            setFilter("sources", {modsFilter: checkedMods});
            await updateSourcesResults(db, resolvedSourceTypes);
        });

    } else if (filterType === "flags") {
        const flagsList = [
            {key: "cycle", label: "⟲ Cycle"},
            {key: "infinite", label: "∞ Unobtainable"},
            {key: "recipe", label: "∅ No Recipe"},
            {key: "hardcoded", label: "H Hardcoded"}
        ];

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderFlagCheckboxes(flagsList, f.flagsFilter || [])}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", async () => {
            setFilter("sources", getDefaultFilters("sources"));
            await updateSourcesResults(db, resolvedSourceTypes);
            closeActivePopover();
        });

        wireFlagCheckboxes(pop, async (checkedFlags) => {
            setFilter("sources", {flagsFilter: checkedFlags});
            await updateSourcesResults(db, resolvedSourceTypes);
        });
    }
}

export async function renderSources(container) {
    const db = state.db;
    if (!db) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">Database not initialized.</div></div>`;
        return;
    }

    const tableConfig = setupResizableTable({
        tableId: "sources",
        cssVarPrefix: "--src-col",
        columnCount: 5,
        headingColumns: [
            {index: 4, label: "Complexity"}
        ],
        flagsColumn: {
            index: 5,
            flagChecks: [
                f => f & ITEM_FLAG.HAS_CYCLE,
                f => f & ITEM_FLAG.IS_INFINITE,
                f => !(f & ITEM_FLAG.HAS_RECIPE),
                f => f & ITEM_FLAG.IS_HARDCODED
            ]
        },
        db,
        tableType: "items"
    });

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
                <div class="sidebar-title" style="font-size: 12px; font-weight: 700; letter-spacing: 1px; color: var(--text-muted); text-transform: uppercase; font-family: var(--sans), sans-serif;">Sources</div>
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
            ${generateTableHeader(SOURCES_COLUMNS, "sources-grid", "sources-head")}
            <div id="sources-list" style="flex: 1; min-height: 0; overflow: hidden; position: relative;"></div>
        </div>
    `;

    try {
        const types = await ensureSourceTypes(db);
        if (types.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="icon">❔</div><div class="message">No base sources found in the database.</div></div>`;
            return;
        }

        if (state.filters.sources.sourceType === null || !types.some(t => t.typeEnum === state.filters.sources.sourceType)) {
            state.filters.sources.sourceType = types[0].typeEnum;
        }

        const catList = container.querySelector("#sources-categories-list");
        await renderCategoryButtons(catList, types);

        const queryInput = container.querySelector("#sources-query");
        queryInput.addEventListener("input", debounce(async (e) => {
            setFilter("sources", {query: e.target.value});
            await updateSourcesResults(db, types);
        }, 120));

        const sortSelect = container.querySelector("#sources-sort");
        sortSelect.addEventListener("change", async (e) => {
            setFilter("sources", {sort: e.target.value});
            await updateSourcesResults(db, types);
        });

        const head = container.querySelector("#sources-head");
        if (head) head.querySelectorAll(".clickable-header").forEach(hdr => {
            hdr.addEventListener("click", (e) => {
                if (e.target.classList.contains("col-drag-handle")) return;
                openPopover(hdr, hdr.dataset.filter);
            });
        });

        tableConfig.initResizers("sources-head");
        await updateSourcesResults(db, types);

    } catch (e) {
        container.innerHTML = `<div style="padding: 20px; color:var(--err)">Error loading base sources: ${escapeHtml(String(e))}</div>`;
    }
}

async function renderCategoryButtons(containerEl, types) {
    const activeEnum = state.filters.sources.sourceType;
    containerEl.innerHTML = types.map(t => `
        <button class="tab ${t.typeEnum === activeEnum ? "active" : ""}" data-type="${t.typeEnum}" style="display:flex; justify-content:space-between; align-items:center; width:100%; padding: 10px 14px; font-size:13px; font-weight: 500;">
            <span style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding-right:8px;" title="${escapeHtml(t.displayName)}">${escapeHtml(t.displayName)}</span>
            <span class="chip" style="font-size:10px; padding:1px 6px; background:rgba(255,255,255,0.06); font-family:var(--mono), monospace; color: var(--text-muted); border-radius: 10px;">${t.items.length}</span>
        </button>
    `).join("");

    containerEl.querySelectorAll("button").forEach(btn => {
        btn.addEventListener("click", async () => {
            state.filters.sources.sourceType = parseInt(btn.dataset.type, 10);
            containerEl.querySelectorAll("button").forEach(b => b.classList.toggle("active", b === btn));

            const types = resolvedSourceTypes;
            const db = state.db;
            if (db && types) await updateSourcesResults(db, types);
        });
    });
}

async function ensureSourceComplexities(db, activeType) {
    if (activeType.sourceComplexities) return activeType.sourceComplexities;

    if (activeType.items.length > 0) try {
        await db.getItemSources(activeType.items[0]);
    } catch (e) {
        console.error(e);
    }

    const complexities = new Map();
    const promises = activeType.items.map(async itemIdx => {
        const item = db.items.get(itemIdx);
        if (!item) return;

        let sc = item.complexity;
        try {
            const sources = await db.getItemSources(itemIdx);
            const matched = sources.find(s => s.sourceTypeEnum === activeType.typeEnum);
            if (matched && isFinite(matched.estimatedCost) && matched.estimatedCost >= 0) sc = matched.estimatedCost;
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

    const filteredItems = [];
    for (const itemIndex of activeType.items) {
        const it = db.items.get(itemIndex);
        if (!it) continue;

        if (q) {
            const hay = (it.name + " " + it.id + " " + (it.categoryName || "")).toLowerCase();
            if (!hay.includes(q)) continue;
        }

        const compVal = sourceComplexities.get(itemIndex) ?? it.complexity;
        if (!passesRangeFilter(compVal, f.minComplexity, f.maxComplexity)) continue;
        if (!passesModFilter(it, f.modsFilter)) continue;
        if (!passesFlagsFilter(it, f.flagsFilter, ITEM_FLAG)) continue;

        filteredItems.push(it);
    }

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