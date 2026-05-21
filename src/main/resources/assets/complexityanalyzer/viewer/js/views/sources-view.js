import { state, setFilter } from "../core/state.js";
import { mountVirtualList } from "../components/virtual-list.js";

const fmtInt = new Intl.NumberFormat("en-US");

const SOURCE_TYPE_NAMES = {
    0: "Override", 1: "Ore", 2: "Empirical Block", 3: "Block", 4: "Block Transformation",
    5: "Farming", 6: "Crafting", 7: "Renewable", 8: "Shearing", 9: "Fishing",
    10: "Mob Drop", 11: "Villager Trade", 12: "Piglin Bartering", 13: "Chest Loot",
    14: "Archaeology", 15: "Special Loot", 16: "Special Action", 17: "Generic Loot",
    18: "Unknown", 19: "Unobtainable"
};

export function renderSources(container) {
    const db = state.db;
    if (!db) return;
    const f = state.filters.sources;

    container.innerHTML = `
        <div class="controls">
            <input type="search" id="sources-query" placeholder="Filter source types or items…" value="${escapeHtml(f.query)}" autocomplete="off">
            <select id="sources-type">
                <option value="">All source types</option>
                ${db.sourceTypes.map(s => `<option value="${s.typeEnum}" ${f.sourceType === String(s.typeEnum) ? "selected" : ""}>${SOURCE_TYPE_NAMES[s.typeEnum] || "Type " + s.typeEnum} (${fmtInt.format(s.items.length)})</option>`).join("")}
            </select>
            <span class="flex-grow"></span>
            <span class="chip" id="sources-count">0 items</span>
        </div>
        <div id="sources-list"></div>
    `;

    $("sources-query").addEventListener("input", debounce(e => { setFilter("sources", { query: e.target.value }); updateSourcesView(); }, 120));
    $("sources-type").addEventListener("change", e => { setFilter("sources", { sourceType: e.target.value }); updateSourcesView(); });

    updateSourcesView();
}

function updateSourcesView() {
    const db = state.db;
    const f = state.filters.sources;
    const q = f.query.trim().toLowerCase();

    let items = [];
    if (f.sourceType !== "" && f.sourceType !== null) {
        const te = parseInt(f.sourceType, 10);
        const entry = db.sourceTypes.find(s => s.typeEnum === te);
        if (entry) items = entry.items.map(idx => db.items.get(idx)).filter(Boolean);
    } else {
        for (const entry of db.sourceTypes) {
            for (const idx of entry.items) {
                const it = db.items.get(idx);
                if (it) items.push(it);
            }
        }
        // deduplicate by index
        const seen = new Set();
        items = items.filter(it => { if (seen.has(it.index)) return false; seen.add(it.index); return true; });
    }

    if (q) items = items.filter(it => (it.name + " " + it.id).toLowerCase().includes(q));

    $("sources-count").textContent = `${fmtInt.format(items.length)} items`;

    mountVirtualList($("sources-list"), {
        itemCount: items.length,
        itemHeight: 28,
        renderRow: (absIndex) => {
            const it = items[absIndex];
            const el = document.createElement("div");
            el.className = "row items-grid";
            el.innerHTML = `
                <span class="idx">${absIndex + 1}</span>
                <span class="id">${it.id}</span>
                <span>${escapeHtml(it.name)}</span>
                <span class="num cat-${it.categoryName || "Uncalculable"}">${isFinite(it.complexity) ? fmtInt.format(it.complexity) : "∞"}</span>
                <span class="num">${fmtInt.format(it.depth)}</span>
                <span class="num">${fmtInt.format(it.usageCount)}</span>
                <span><span class="category-pill cat-${it.categoryName || "Uncalculable"}">${it.categoryName}</span></span>
                <span></span>
            `;
            return el;
        }
    });
}

function $(id) { return document.getElementById(id); }
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
function debounce(fn, ms) { let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); }; }
