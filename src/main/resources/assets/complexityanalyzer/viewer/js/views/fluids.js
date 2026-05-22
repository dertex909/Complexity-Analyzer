import { state, setState, setFilter } from "../core/state.js";
import { mountVirtualList } from "../components/virtual-list.js";
import { renderFluidDetail } from "./details/fluid-detail.js";
import { FLUID_FLAG } from "../core/cabin.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

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

export function renderFluids(container) {
    const db = state.db;
    if (!db) return;

    loadColumnWidths();

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
        <div class="table-head fluids-grid" id="fluids-head">
            <div class="th-cell" data-index="1"><span class="th-text">№</span><div class="col-drag-handle"></div></div>
            <div class="th-cell clickable-header" data-index="2" data-filter="id"><span class="th-text">ID <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell" data-index="3"><span class="th-text" style="padding-right: 12px;">Name</span><div class="col-drag-handle"></div></div>
            <div class="th-cell num clickable-header" data-index="4" data-filter="complexity"><span class="th-text">Complexity <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell num clickable-header" data-index="5" data-filter="usageCount"><span class="th-text">Usage <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell clickable-header" data-index="6" data-filter="category"><span class="th-text">Category <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
            <div class="th-cell clickable-header" data-index="7" data-filter="flags"><span class="th-text">Flags <span class="filter-indicator">▼</span></span><div class="col-drag-handle"></div></div>
        </div>
        <div id="fluids-list"></div>
    `;

    wireFluidFilters();
    updateFluidsView();
}

function loadColumnWidths() {
    // Remove old v1 keys so they don't interfere
    for (let i = 1; i <= 9; i++) localStorage.removeItem(`fl-col-width-pct-${i}`);

    const defaults = {
        1: "5%",
        2: "20%",
        3: "25%",
        4: "14%",
        5: "10%",
        6: "14%",
        7: "12%"
    };

    let sum = 0;
    for (let i = 1; i <= 7; i++) {
        const saved = localStorage.getItem(`fl-v2-col-width-pct-${i}`);
        const w = saved || defaults[i];
        sum += parseFloat(w);
        document.documentElement.style.setProperty(`--fl-col-${i}`, w);
    }
    // Reset if saved widths don't add up to ~100%
    if (sum < 95 || sum > 105) {
        for (let i = 1; i <= 7; i++) {
            localStorage.removeItem(`fl-v2-col-width-pct-${i}`);
            document.documentElement.style.setProperty(`--fl-col-${i}`, defaults[i]);
        }
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
        1: 40, 2: 100, 3: 120, 4: 90, 5: 70, 6: 100, 7: 80
    };
    if (!db) return mins;

    const count = db.fluids.count;
    const text_1 = String(count);
    const w_1 = measureTextWidth(text_1, false) + 24;
    mins[1] = Math.max(40, Math.ceil(w_1));

    const w_2 = measureTextWidth("ID", false) + 32;
    mins[2] = Math.max(100, Math.ceil(w_2));

    const w_3 = measureTextWidth("Name", false) + 32;
    mins[3] = Math.max(120, Math.ceil(w_3));

    let maxFlags = 1;
    for (let i = 0; i < count; i++) {
        const fl = db.fluids.get(i);
        if (!fl) continue;
        let fc = 0;
        const f = fl.flags;
        if (f & FLUID_FLAG.HAS_CYCLE) fc++;
        if (f & FLUID_FLAG.IS_INFINITE) fc++;
        if (!(f & FLUID_FLAG.HAS_RECIPE)) fc++;
        if (f & FLUID_FLAG.IS_PROTECTED) fc++;
        if (fc > maxFlags) maxFlags = fc;
    }

    const heading_7_width = measureTextWidth("Flags", false) + 28;
    const content_7_width = maxFlags * 16 + Math.max(0, maxFlags - 1) * 3 + 24;
    mins[7] = Math.max(heading_7_width, content_7_width);

    return mins;
}

function initColumnResizers() {
    const head = document.getElementById("fluids-head");
    if (!head) return;
    const handles = head.querySelectorAll(".col-drag-handle");
    handles.forEach(handle => {
        handle.addEventListener("pointerdown", (e) => {
            e.preventDefault();
            e.stopPropagation();
            closeActivePopover();

            const cell = handle.closest(".th-cell");
            const index = parseInt(cell.dataset.index, 10);
            if (index >= 7) return;

            const startX = e.clientX;
            const rightPadding = parseFloat(window.getComputedStyle(head).paddingRight || 0);
            const totalWidth = head.getBoundingClientRect().width - rightPadding;

            const defaults = {
                1: 4, 2: 16, 3: 20, 4: 12, 5: 8, 6: 12, 7: 10
            };
            const startPct = {};
            for (let i = 1; i <= 7; i++) {
                const val = document.documentElement.style.getPropertyValue(`--fl-col-${i}`);
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

                document.documentElement.style.setProperty(`--fl-col-${i}`, `${newPct_i}%`);
                document.documentElement.style.setProperty(`--fl-col-${j}`, `${newPct_j}%`);

                localStorage.setItem(`fl-v2-col-width-pct-${i}`, `${newPct_i}%`);
                localStorage.setItem(`fl-v2-col-width-pct-${j}`, `${newPct_j}%`);

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
    const f = state.filters.fluids;
    const head = document.getElementById("fluids-head");
    if (!head) return;

    head.querySelectorAll(".clickable-header").forEach(hdr => {
        const type = hdr.dataset.filter;
        let active = false;
        if (type === "complexity") active = f.minComplexity !== "" || f.maxComplexity !== "";
        else if (type === "usageCount") active = f.minRecipeUsages !== "" || f.maxRecipeUsages !== "";
        else if (type === "category") active = f.categoriesFilter.length > 0;
        else if (type === "flags") active = f.flagsFilter.length < 4;
        else if (type === "id") active = f.modsFilter.length > 0;

        hdr.classList.toggle("filter-active", active);
    });

    // Handle sort icons in head cells
    const [sortField, sortDir] = f.sort.split("-");
    head.querySelectorAll(".clickable-header, .th-cell[data-filter]").forEach(hdr => {
        const field = hdr.dataset.filter || hdr.dataset.sort;
        const textNode = hdr.querySelector(".th-text");
        if (textNode) {
            let arrow = "▼";
            if (field === sortField) arrow = sortDir === "asc" ? "▲" : "▼";
            // Keep filter indicator or search indicator
            if (hdr.classList.contains("clickable-header")) {
                const titleText = field.charAt(0).toUpperCase() + field.slice(1).replace(/Count|Usages|Filter/, "");
                textNode.innerHTML = `${titleText} <span class="filter-indicator">${arrow}</span>`;
            }
        }
    });
}

function wireFluidFilters() {
    const onInput = debounce((key, val) => {
        setFilter("fluids", { [key]: val });
        updateFluidsView();
    }, 120);
    
    document.getElementById("fluids-query").addEventListener("input", e => onInput("query", e.target.value));
    document.getElementById("fluids-sort").addEventListener("change", e => {
        setFilter("fluids", { sort: e.target.value });
        updateFluidsView();
    });

    const head = document.getElementById("fluids-head");
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
    else if (filterType === "flags") popWidth = 200;

    const rect = headerCell.getBoundingClientRect();
    let left = rect.left + window.scrollX;
    if (left + popWidth > window.innerWidth - 16) {
        left = window.innerWidth - popWidth - 16;
    }
    pop.style.width = `${popWidth}px`;
    pop.style.left = `${left}px`;
    pop.style.top = `${rect.bottom + window.scrollY + 4}px`;

    const f = state.filters.fluids;
    const db = state.db;

    if (["complexity", "usageCount"].includes(filterType)) {
        let label = filterType.charAt(0).toUpperCase() + filterType.slice(1);
        if (filterType === "usageCount") label = "Recipe Usages";

        const minKey = `min${filterType.charAt(0).toUpperCase() + filterType.slice(1)}`;
        const maxKey = `max${filterType.charAt(0).toUpperCase() + filterType.slice(1)}`;
        const minVal = f[minKey] ?? "";
        const maxVal = f[maxKey] ?? "";

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div style="font-size:11px; color:var(--text-dim); margin:2px 0 4px 0;">Define range:</div>
            <div class="range-inputs">
                <input type="number" id="filter-min-input" placeholder="From" value="${minVal}">
                <input type="number" id="filter-max-input" placeholder="To" value="${maxVal}">
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", { [minKey]: "", [maxKey]: "" });
            updateFluidsView();
            closeActivePopover();
        });

        const onRangeChange = () => {
            const minInput = pop.querySelector("#filter-min-input").value;
            const maxInput = pop.querySelector("#filter-max-input").value;
            setFilter("fluids", { [minKey]: minInput, [maxKey]: maxInput });
            updateFluidsView();
        };

        pop.querySelector("#filter-min-input").addEventListener("input", debounce(onRangeChange, 150));
        pop.querySelector("#filter-max-input").addEventListener("input", debounce(onRangeChange, 150));

    } else if (filterType === "category") {
        const categoriesSet = new Set();
        for (let i = 0; i < db.fluids.count; i++) {
            const fl = db.fluids.get(i);
            if (fl && fl.categoryName) categoriesSet.add(fl.categoryName);
        }
        const categoriesList = Array.from(categoriesSet).sort();

        const selectedCats = f.categoriesFilter;

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${categoriesList.map(cat => {
                    const checked = selectedCats.length === 0 || selectedCats.includes(cat);
                    return `
                        <label class="cb-label">
                            <input type="checkbox" class="cat-cb" value="${escapeHtml(cat)}" ${checked ? "checked" : ""}>
                            <span>${escapeHtml(cat)}</span>
                        </label>
                    `;
                }).join("")}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", { categoriesFilter: [] });
            updateFluidsView();
            closeActivePopover();
        });

        pop.querySelectorAll(".cat-cb").forEach(cb => {
            cb.addEventListener("change", () => {
                const checkedCbs = Array.from(pop.querySelectorAll(".cat-cb:checked")).map(c => c.value);
                // If all checked, reset to empty (select all)
                if (checkedCbs.length === categoriesList.length) {
                    setFilter("fluids", { categoriesFilter: [] });
                } else {
                    setFilter("fluids", { categoriesFilter: checkedCbs });
                }
                updateFluidsView();
            });
        });

    } else if (filterType === "id") {
        const allMods = new Set();
        for (let i = 0; i < db.fluids.count; i++) {
            const fl = db.fluids.get(i);
            if (fl && fl.id) {
                const parts = fl.id.split(":");
                if (parts.length > 1) allMods.add(parts[0]);
            }
        }
        const modsList = Array.from(allMods).sort();

        const selectedMods = f.modsFilter;

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${modsList.map(mod => {
                    const checked = selectedMods.length === 0 || selectedMods.includes(mod);
                    return `
                        <label class="cb-label">
                            <input type="checkbox" class="mod-cb" value="${escapeHtml(mod)}" ${checked ? "checked" : ""}>
                            <span>${escapeHtml(mod)}</span>
                        </label>
                    `;
                }).join("")}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", { modsFilter: [] });
            updateFluidsView();
            closeActivePopover();
        });

        pop.querySelectorAll(".mod-cb").forEach(cb => {
            cb.addEventListener("change", () => {
                const checkedMods = Array.from(pop.querySelectorAll(".mod-cb:checked")).map(c => c.value);
                if (checkedMods.length === modsList.length) {
                    setFilter("fluids", { modsFilter: [] });
                } else {
                    setFilter("fluids", { modsFilter: checkedMods });
                }
                updateFluidsView();
            });
        });

    } else if (filterType === "flags") {
        const selectedFlags = f.flagsFilter;
        const flagsList = [
            { key: "cycle", label: "Cycle ⟲" },
            { key: "infinite", label: "Unobtainable ∞" },
            { key: "recipe", label: "No recipe ∅" },
            { key: "protected", label: "Protected P" }
        ];

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${flagsList.map(flg => {
                    const checked = selectedFlags.includes(flg.key);
                    return `
                        <label class="cb-label">
                            <input type="checkbox" class="flag-cb" value="${flg.key}" ${checked ? "checked" : ""}>
                            <span>${flg.label}</span>
                        </label>
                    `;
                }).join("")}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("fluids", { flagsFilter: ["cycle", "infinite", "recipe", "protected"] });
            updateFluidsView();
            closeActivePopover();
        });

        pop.querySelectorAll(".flag-cb").forEach(cb => {
            cb.addEventListener("change", () => {
                const checkedFlags = Array.from(pop.querySelectorAll(".flag-cb:checked")).map(c => c.value);
                setFilter("fluids", { flagsFilter: checkedFlags });
                updateFluidsView();
            });
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

        // Mod namespace filter
        if (f.modsFilter.length > 0) {
            const parts = fl.id.split(":");
            const namespace = parts.length > 1 ? parts[0] : "minecraft";
            if (!f.modsFilter.includes(namespace)) continue;
        }

        // Category filter
        if (f.categoriesFilter.length > 0 && !f.categoriesFilter.includes(fl.categoryName)) continue;

        // Range filters
        if (f.minComplexity !== "" && fl.complexity < parseFloat(f.minComplexity)) continue;
        if (f.maxComplexity !== "" && fl.complexity > parseFloat(f.maxComplexity)) continue;

        if (f.minRecipeUsages !== "" && fl.usageCount < parseInt(f.minRecipeUsages, 10)) continue;
        if (f.maxRecipeUsages !== "" && fl.usageCount > parseInt(f.maxRecipeUsages, 10)) continue;

        // Flags filters
        const flags = fl.flags;
        const hasCycle = (flags & FLUID_FLAG.HAS_CYCLE) !== 0;
        const isInfinite = (flags & FLUID_FLAG.IS_INFINITE) !== 0;
        const noRecipe = (flags & FLUID_FLAG.HAS_RECIPE) === 0;
        const isProtected = (flags & FLUID_FLAG.IS_PROTECTED) !== 0;

        if (hasCycle && !f.flagsFilter.includes("cycle")) continue;
        if (isInfinite && !f.flagsFilter.includes("infinite")) continue;
        if (noRecipe && !f.flagsFilter.includes("recipe")) continue;
        if (isProtected && !f.flagsFilter.includes("protected")) continue;

        list.push(fl);
    }

    // Sorting
    const [field, dir] = f.sort.split("-");
    const sign = dir === "asc" ? 1 : -1;
    list.sort((a, b) => {
        let valA = a[field];
        let valB = b[field];

        if (field === "complexity") {
            // Infinite values sorted to the bottom
            if (!isFinite(valA)) valA = Number.MAX_VALUE;
            if (!isFinite(valB)) valB = Number.MAX_VALUE;
        }

        if (typeof valA === "number" && typeof valB === "number") {
            return sign * (valA - valB);
        }

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
                <span class="flags">${fluidFlags(fl)}</span>
            `;
            el.addEventListener("click", () => {
                setState({ selectedItem: fl.index }); // Reuse selectedItem for fluid modal in app.js
                renderFluidDetail(null, fl.index);
            });
            return el;
        }
    });
}

function fluidFlags(fl) {
    const out = [];
    const f = fl.flags;
    if (f & FLUID_FLAG.HAS_CYCLE) out.push(`<span class="flag cycle" title="cycle">⟲</span>`);
    if (f & FLUID_FLAG.IS_INFINITE) out.push(`<span class="flag infinite" title="unobtainable">∞</span>`);
    if (!(f & FLUID_FLAG.HAS_RECIPE)) out.push(`<span class="flag no-recipe" title="no recipe">∅</span>`);
    if (f & FLUID_FLAG.IS_PROTECTED) out.push(`<span class="flag hardcoded" title="protected">P</span>`);
    return out.join("");
}

function formatComplexity(c) {
    if (c < 0) return "—";
    if (!isFinite(c)) return "∞";
    if (c === 0) return "0";
    if (c >= 1e6) return c.toExponential(2);
    return fmt.format(c);
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

function debounce(fn, ms) {
    let t;
    return (...args) => {
        clearTimeout(t);
        t = setTimeout(() => fn(...args), ms);
    };
}

