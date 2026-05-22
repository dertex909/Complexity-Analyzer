import {
    escapeHtml,
    debounce,
    formatComplexity,
    getFluidFlags,
    fmtInt
} from "../core/utils.js";
import {state, setState, setFilter, getDefaultFilters} from "../core/state.js";
import {mountVirtualList} from "../components/virtual-list.js";
import {renderFluidDetail} from "./details/fluid-detail.js";
import {FLUID_FLAG} from "../core/cabin.js";
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
    renderCategoryCheckboxes,
    renderRangeInputs,
    wireModCheckboxes,
    wireFlagCheckboxes,
    wireCategoryCheckboxes,
    wireRangeInputs
} from "../components/filter-popover.js";
import {
    passesModFilter,
    passesFlagsFilter,
    passesCategoryFilter,
    passesRangeFilter
} from "../components/item-filter.js";

const FLUIDS_COLUMNS = [
    {index: 1, label: "№", filter: null, sortable: false},
    {index: 2, label: "ID", filter: "id", sortable: true},
    {index: 3, label: "Name", filter: null, sortable: true},
    {index: 4, label: "Complexity", filter: "complexity", sortable: true, numeric: true},
    {index: 5, label: "Usage", filter: "usageCount", sortable: true, numeric: true},
    {index: 6, label: "Category", filter: "category", sortable: true},
    {index: 7, label: "Flags", filter: "flags", sortable: false}
];

export function renderFluids(container) {
    const db = state.db;
    if (!db) return;

    const tableConfig = setupResizableTable({
        tableId: "fluids",
        cssVarPrefix: "--fl-col",
        columnCount: 7,
        headingColumns: [
            {index: 4, label: "Complexity"},
            {index: 5, label: "Usage"},
            {index: 6, label: "Category"}
        ],
        flagsColumn: {
            index: 7,
            flagChecks: [
                f => f & FLUID_FLAG.HAS_CYCLE,
                f => f & FLUID_FLAG.IS_UNCALCULABLE,
                f => !(f & FLUID_FLAG.HAS_RECIPE),
                f => f & FLUID_FLAG.IS_PROTECTED
            ]
        },
        db,
        tableType: "fluids"
    });

    const f = state.filters.fluids;

    container.innerHTML = `
        <div class="controls" id="fluids-controls">
            <input type="search" id="fluids-query" placeholder="Filter fluids by name, id or category…" value="${escapeHtml(f.query)}" autocomplete="off">
            <select id="fluids-sort">
                <option value="complexity-desc" ${f.sort === "complexity-desc" ? "selected" : ""}>Complexity ▼</option>
                <option value="complexity-asc" ${f.sort === "complexity-asc" ? "selected" : ""}>Complexity ▲</option>
                <option value="name-asc" ${f.sort === "name-asc" ? "selected" : ""}>Name A-Z</option>
                <option value="id-asc" ${f.sort === "id-asc" ? "selected" : ""}>ID A-Z</option>
                <option value="usage-desc" ${f.sort === "usage-desc" ? "selected" : ""}>Usage ▼</option>
            </select>
            <span class="flex-grow"></span>
            <span class="chip" id="fluids-count">0 fluids</span>
        </div>
        ${generateTableHeader(FLUIDS_COLUMNS, "fluids-grid", "fluids-head")}
        <div id="fluids-list"></div>
    `;

    wireFluidFilters(tableConfig);
    updateHeaderIndicators();
    updateFluidsView();
}


function updateHeaderIndicators() {
    const f = state.filters.fluids;
    const head = document.getElementById("fluids-head");
    if (!head) return;

    head.querySelectorAll(".clickable-header").forEach(hdr => {
        const type = hdr.dataset.filter;
        let active = false;
        if (type === "complexity") active = f.minComplexity !== "" || f.maxComplexity !== "";
        else if (type === "usageCount") active = f.minRecipeUsages !== "" || f.maxRecipeUsages !== "";
        else if (type === "category") active = f.categoriesFilter.length > 0;
        else if (type === "flags") active = f.flagsFilter.length > 0;
        else if (type === "id") active = f.modsFilter.length > 0;

        hdr.classList.toggle("filtered", active);
    });

    const [sortField, sortDir] = f.sort.split("-");
    head.querySelectorAll(".clickable-header, .th-cell[data-filter]").forEach(hdr => {
        const field = hdr.dataset.filter || hdr.dataset.sort;
        const textNode = hdr.querySelector(".th-text");
        if (textNode) {
            let arrow = "▼";
            if (field === sortField) arrow = sortDir === "asc" ? "▲" : "▼";
            if (hdr.classList.contains("clickable-header")) {
                const titleText = field.charAt(0).toUpperCase() + field.slice(1).replace(/Count|Usages|Filter/, "");
                textNode.innerHTML = `${titleText} <span class="filter-indicator">${arrow}</span>`;
            }
        }
    });
}

function wireFluidFilters(tableConfig) {
    const onInput = debounce((key, val) => {
        setFilter("fluids", {[key]: val});
        updateFluidsView();
    }, 120);

    document.getElementById("fluids-query").addEventListener("input", e => onInput("query", e.target.value));
    document.getElementById("fluids-sort").addEventListener("change", e => {
        setFilter("fluids", {sort: e.target.value});
        updateFluidsView();
    });

    const head = document.getElementById("fluids-head");
    if (head) head.querySelectorAll(".clickable-header").forEach(hdr => {
        hdr.addEventListener("click", (e) => {
            if (e.target.classList.contains("col-drag-handle")) return;
            openPopover(hdr, hdr.dataset.filter);
        });
    });

    tableConfig.initResizers("fluids-head");
}

function openPopover(headerCell, filterType) {
    const pop = createPopover(headerCell, filterType);

    const f = state.filters.fluids;
    const db = state.db;

    if (["complexity", "usageCount"].includes(filterType)) {
        const minKey = `min${filterType.charAt(0).toUpperCase() + filterType.slice(1)}`;
        const maxKey = `max${filterType.charAt(0).toUpperCase() + filterType.slice(1)}`;
        const minVal = f[minKey] ?? "";
        const maxVal = f[maxKey] ?? "";

        pop.innerHTML = renderRangeInputs(minKey, maxKey, minVal, maxVal);

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", getDefaultFilters("fluids"));
            updateFluidsView();
            closeActivePopover();
        });

        wireRangeInputs(pop, minKey, maxKey, (vals) => {
            setFilter("fluids", vals);
            updateFluidsView();
        }, debounce);

    } else if (filterType === "category") {
        const categoriesSet = new Set();
        for (let i = 0; i < db.fluids.count; i++) {
            const fl = db.fluids.get(i);
            if (fl && fl.categoryName) categoriesSet.add(fl.categoryName);
        }
        const categoriesList = Array.from(categoriesSet).sort();

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderCategoryCheckboxes(categoriesList, f.categoriesFilter)}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", getDefaultFilters("fluids"));
            updateFluidsView();
            closeActivePopover();
        });

        wireCategoryCheckboxes(pop, categoriesList, (checkedCats) => {
            setFilter("fluids", {categoriesFilter: checkedCats});
            updateFluidsView();
        });

    } else if (filterType === "id") {
        const modsList = getModNamespaces(db, "fluids");

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderModCheckboxes(modsList, f.modsFilter)}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", getDefaultFilters("fluids"));
            updateFluidsView();
            closeActivePopover();
        });

        wireModCheckboxes(pop, modsList, (checkedMods) => {
            setFilter("fluids", {modsFilter: checkedMods});
            updateFluidsView();
        });

    } else if (filterType === "flags") {
        const flagsList = [
            {key: "cycle", label: "Cycle ⟲"},
            {key: "uncalculable", label: "Uncalculable -"},
            {key: "recipe", label: "No recipe ∅"},
            {key: "protected", label: "Protected P"}
        ];

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderFlagCheckboxes(flagsList, f.flagsFilter)}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", getDefaultFilters("fluids"));
            updateFluidsView();
            closeActivePopover();
        });

        wireFlagCheckboxes(pop, (checkedFlags) => {
            setFilter("fluids", {flagsFilter: checkedFlags});
            updateFluidsView();
        });
    }
}

function updateFluidsView() {
    const db = state.db;
    const f = state.filters.fluids;
    const q = f.query.trim().toLowerCase();

    updateHeaderIndicators();

    const list = [];
    for (let i = 0; i < db.fluids.count; i++) {
        const fl = db.fluids.get(i);
        if (!fl) continue;

        if (q && !(fl.name + " " + fl.id + " " + fl.categoryName).toLowerCase().includes(q)) continue;

        if (!passesModFilter(fl, f.modsFilter)) continue;
        if (!passesCategoryFilter(fl, f.categoriesFilter)) continue;
        if (!passesRangeFilter(fl.complexity, f.minComplexity, f.maxComplexity)) continue;
        if (!passesRangeFilter(fl.usageCount, f.minRecipeUsages, f.maxRecipeUsages)) continue;
        if (!passesFlagsFilter(fl, f.flagsFilter, FLUID_FLAG)) continue;

        list.push(fl);
    }

    const [field, dir] = f.sort.split("-");
    const sign = dir === "asc" ? 1 : -1;
    list.sort((a, b) => {
        let valA = a[field];
        let valB = b[field];

        if (field === "complexity") {
            if (!isFinite(valA)) valA = Number.MAX_VALUE;
            if (!isFinite(valB)) valB = Number.MAX_VALUE;
        }

        if (typeof valA === "number" && typeof valB === "number") return sign * (valA - valB);

        valA = String(valA || "").toLowerCase();
        valB = String(valB || "").toLowerCase();
        return sign * valA.localeCompare(valB);
    });

    document.getElementById("fluids-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(db.fluids.count)} fluids`;

    const listContainer = document.getElementById("fluids-list");
    if (list.length === 0) {
        listContainer.innerHTML = `
            <div class="virtual-viewport" style="display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; width: 100%; flex: 1;">
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">🔍</div>
                    <div class="message">No fluids match your filter.</div>
                </div>
            </div>`;
        return;
    }

    mountVirtualList(listContainer, {
        itemCount: list.length,
        itemHeight: 28,
        renderRow: (absIndex) => {
            const fl = list[absIndex];
            const el = document.createElement("div");
            el.className = "row fluids-grid";
            el.dataset.index = fl.index;
            el.innerHTML = `
                <span class="idx">${absIndex + 1}</span>
                <span class="id" title="${fl.id}">${fl.id}</span>
                <span class="name" title="${escapeHtml(fl.name)}">${escapeHtml(fl.name)}</span>
                <span class="num cat-${fl.categoryName || "Uncalculable"}" style="font-weight:600;">${formatComplexity(fl.complexity)}</span>
                <span class="num">${fmtInt.format(fl.usageCount)}</span>
                <span><span class="category-pill cat-${fl.categoryName || "Uncalculable"}">${fl.categoryName}</span></span>
                <span class="flags">${getFluidFlags(fl, true)}</span>
            `;
            el.addEventListener("click", () => {
                setState({selectedItem: fl.index});
                renderFluidDetail(null, fl.index);
            });
            return el;
        }
    });
}