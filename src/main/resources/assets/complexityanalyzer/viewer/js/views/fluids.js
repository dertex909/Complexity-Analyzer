import { state, setFilter } from "../core/state.js";
import { mountVirtualList } from "../components/virtual-list.js";

const fmtInt = new Intl.NumberFormat("en-US");

export function renderFluids(container) {
    const db = state.db;
    if (!db) return;

    loadColumnWidths();

    const f = state.filters.fluids;

    container.innerHTML = `
        <div class="controls" id="fluids-controls">
            <input type="search" id="fluids-query" placeholder="Filter fluids by name or ID…" value="${escapeHtml(f.query)}" autocomplete="off">
            <select id="fluids-sort">
                <option value="name-asc" ${f.sort === "name-asc" ? "selected" : ""}>Name A-Z</option>
                <option value="name-desc" ${f.sort === "name-desc" ? "selected" : ""}>Name Z-A</option>
                <option value="id-asc" ${f.sort === "id-asc" ? "selected" : ""}>ID A-Z</option>
                <option value="id-desc" ${f.sort === "id-desc" ? "selected" : ""}>ID Z-A</option>
            </select>
            <span class="flex-grow"></span>
            <span class="chip" id="fluids-count">0 fluids</span>
        </div>
        <div class="table-head fluids-grid" id="fluids-head">
            <div class="th-cell" data-index="1"><span class="th-text">№</span><div class="col-drag-handle"></div></div>
            <div class="th-cell" data-index="2"><span class="th-text">ID</span><div class="col-drag-handle"></div></div>
            <div class="th-cell" data-index="3"><span class="th-text">Name</span><div class="col-drag-handle"></div></div>
        </div>
        <div id="fluids-list"></div>
    `;

    wireFluidFilters();
    updateFluidsView();
}

function loadColumnWidths() {
    const defaults = {
        1: "6%",
        2: "44%",
        3: "50%"
    };
    for (let i = 1; i <= 3; i++) {
        const w = localStorage.getItem(`fl-col-width-pct-${i}`) || defaults[i];
        document.documentElement.style.setProperty(`--fl-col-${i}`, w);
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
        1: 45,
        2: 120,
        3: 120
    };
    if (!db) return mins;

    // Col 1 (№)
    const count = db.fluids.count;
    const text_1 = String(count);
    const w_1 = measureTextWidth(text_1, false) + 24;
    mins[1] = Math.max(45, Math.ceil(w_1));

    // Col 2 (ID)
    const w_2 = measureTextWidth("ID", false) + 32;
    mins[2] = Math.max(120, Math.ceil(w_2));

    // Col 3 (Name)
    const w_3 = measureTextWidth("Name", false) + 32;
    mins[3] = Math.max(120, Math.ceil(w_3));

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

            const cell = handle.closest(".th-cell");
            const index = parseInt(cell.dataset.index, 10);
            if (index >= 3) return; // Last column has no next column to resize against

            const startX = e.clientX;
            const rightPadding = parseFloat(window.getComputedStyle(head).paddingRight || 0);
            const totalWidth = head.getBoundingClientRect().width - rightPadding;

            const defaults = {
                1: 6,
                2: 44,
                3: 50
            };
            const startPct = {};
            for (let i = 1; i <= 3; i++) {
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

                localStorage.setItem(`fl-col-width-pct-${i}`, `${newPct_i}%`);
                localStorage.setItem(`fl-col-width-pct-${j}`, `${newPct_j}%`);

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

function wireFluidFilters() {
    const onInput = debounce((key, val) => {
        setFilter("fluids", { [key]: val });
        updateFluidsView();
    }, 120);
    
    $("fluids-query").addEventListener("input", e => onInput("query", e.target.value));
    $("fluids-sort").addEventListener("change", e => {
        setFilter("fluids", { sort: e.target.value });
        updateFluidsView();
    });

    initColumnResizers();
}

function updateFluidsView() {
    const db = state.db;
    const f = state.filters.fluids;
    const q = f.query.trim().toLowerCase();

    const list = [];
    for (let i = 0; i < db.fluids.count; i++) {
        const fl = db.fluids.get(i);

        if (q && !(fl.name + " " + fl.id).toLowerCase().includes(q)) continue;

        list.push(fl);
    }

    const [field, dir] = f.sort.split("-");
    const sign = dir === "asc" ? 1 : -1;
    list.sort((a, b) => {
        const valA = String(a[field] || "");
        const valB = String(b[field] || "");
        return sign * valA.localeCompare(valB);
    });

    $("fluids-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(db.fluids.count)} fluids`;

    if (list.length === 0) {
         $("fluids-list").innerHTML = `
            <div class="virtual-viewport" style="display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; width: 100%; flex: 1;">
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">🔍</div>
                    <div class="message">No fluids match your filter.</div>
                </div>
            </div>`;
        return;
    }

    mountVirtualList($("fluids-list"), {
        itemCount: list.length,
        itemHeight: 28,
        renderRow: (absIndex) => {
            const fl = list[absIndex];
            const el = document.createElement("div");
            el.className = "row fluids-grid";
            el.innerHTML = `
                <span class="idx">${absIndex + 1}</span>
                <span class="id">${fl.id}</span>
                <span>${escapeHtml(fl.name)}</span>
            `;
            return el;
        }
    });
}

function $(id) { return document.getElementById(id); }
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
function debounce(fn, ms) { let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); }; }
