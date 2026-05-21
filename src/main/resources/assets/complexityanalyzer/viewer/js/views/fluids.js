import { state, setFilter } from "../core/state.js";
import { mountVirtualList } from "../components/virtual-list.js";

const fmtInt = new Intl.NumberFormat("en-US");

export function renderFluids(container) {
    const db = state.db;
    if (!db) return;
    const f = state.filters.fluids;

    container.innerHTML = `
        <div class="controls">
            <input type="search" id="fluids-query" placeholder="Filter fluids…" value="${escapeHtml(f.query)}" autocomplete="off">
            <select id="fluids-sort">
                <option value="name-asc" ${f.sort === "name-asc" ? "selected" : ""}>Name A-Z</option>
                <option value="name-desc" ${f.sort === "name-desc" ? "selected" : ""}>Name Z-A</option>
                <option value="id-asc" ${f.sort === "id-asc" ? "selected" : ""}>ID A-Z</option>
            </select>
            <span class="flex-grow"></span>
            <span class="chip" id="fluids-count">0 fluids</span>
        </div>
        <div class="table-head fluids-grid">
            <span>#</span><span>ID</span><span>Name</span><span>Category</span>
        </div>
        <div id="fluids-list"></div>
    `;

    $("fluids-query").addEventListener("input", debounce(e => { setFilter("fluids", { query: e.target.value }); updateFluidsView(); }, 120));
    $("fluids-sort").addEventListener("change", e => { setFilter("fluids", { sort: e.target.value }); updateFluidsView(); });

    updateFluidsView();
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
    list.sort((a, b) => sign * String(a[field]).localeCompare(String(b[field])));

    $("fluids-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(db.fluids.count)} fluids`;

    mountVirtualList($("fluids-list"), {
        itemCount: list.length,
        itemHeight: 28,
        renderRow: (absIndex) => {
            const fl = list[absIndex];
            const el = document.createElement("div");
            el.className = "row fluids-grid";
            el.innerHTML = `<span class="idx">${absIndex + 1}</span><span class="id">${fl.id}</span><span>${escapeHtml(fl.name)}</span><span>—</span>`;
            return el;
        }
    });
}

function $(id) { return document.getElementById(id); }
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
function debounce(fn, ms) { let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); }; }
