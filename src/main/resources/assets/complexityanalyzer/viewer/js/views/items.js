import { state, setFilter, selectItem, setSubTab } from "../core/state.js";
import { ITEM_FLAG } from "../core/cabin.js";
import { mountVirtualList } from "../components/virtual-list.js";
import { renderItemDetail } from "./item-detail.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

export function renderItems(container) {
    const db = state.db;
    if (!db) return;

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
            <select id="items-category">
                <option value="">All categories</option>
                ${db.categories.map(c => `<option value="${c.name}" ${f.category === c.name ? "selected" : ""}>${c.name} (${fmtInt.format(c.items.length)})</option>`).join("")}
            </select>
            <label class="checkbox"><input type="checkbox" id="items-finite" ${f.finiteOnly ? "checked" : ""}> finite only</label>
            <label class="checkbox"><input type="checkbox" id="items-recipe" ${f.hasRecipe ? "checked" : ""}> has recipe</label>
            <label class="checkbox"><input type="checkbox" id="items-cycle" ${f.hasCycle ? "checked" : ""}> no cycle</label>
            <label class="checkbox"><input type="checkbox" id="items-hardcoded" ${f.isHardcoded ? "checked" : ""}> hardcoded</label>
            <span class="flex-grow"></span>
            <span class="chip" id="items-count">0 items</span>
        </div>
        <div class="table-head items-grid" id="items-head">
            <span>#</span><span>ID</span><span>Name</span>
            <span class="num">Complexity</span><span class="num">Depth</span>
            <span class="num">Usage</span><span>Category</span><span>Flags</span>
        </div>
        <div id="items-list"></div>
    `;

    wireItemFilters();
    updateItemsView();

    if (state.selectedItem >= 0) {
        renderItemDetail(container, state.selectedItem);
    }
}

function wireItemFilters() {
    const onInput = debounce((key, val) => setFilter("items", { [key]: val }), 120);
    $("items-query").addEventListener("input", e => onInput("query", e.target.value));
    $("items-sort").addEventListener("change", e => { setFilter("items", { sort: e.target.value }); updateItemsView(); });
    $("items-category").addEventListener("change", e => { setFilter("items", { category: e.target.value }); updateItemsView(); });
    $("items-finite").addEventListener("change", e => { setFilter("items", { finiteOnly: e.target.checked }); updateItemsView(); });
    $("items-recipe").addEventListener("change", e => { setFilter("items", { hasRecipe: e.target.checked }); updateItemsView(); });
    $("items-cycle").addEventListener("change", e => { setFilter("items", { hasCycle: e.target.checked }); updateItemsView(); });
    $("items-hardcoded").addEventListener("change", e => { setFilter("items", { isHardcoded: e.target.checked }); updateItemsView(); });
}

function updateItemsView() {
    const db = state.db;
    const f = state.filters.items;
    const q = f.query.trim().toLowerCase();
    const cat = f.category;
    const list = [];
    const n = db.items.count;
    for (let i = 0; i < n; i++) {
        const it = db.items.get(i);
        if (cat && it.categoryName !== cat) continue;
        if (f.finiteOnly && !isFinite(it.complexity)) continue;
        if (f.hasRecipe && !(it.flags & ITEM_FLAG.HAS_RECIPE)) continue;
        if (f.hasCycle && (it.flags & ITEM_FLAG.HAS_CYCLE)) continue;
        if (f.isHardcoded && !(it.flags & ITEM_FLAG.IS_HARDCODED)) continue;
        if (q) {
            const hay = (it.name + " " + it.id + " " + it.categoryName).toLowerCase();
            if (!hay.includes(q)) continue;
        }
        list.push(it);
    }
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
                <span>${escapeHtml(it.name)}</span>
                <span class="num cat-${it.categoryName || "Uncalculable"}">${formatComplexity(it.complexity)}</span>
                <span class="num">${fmtInt.format(it.depth)}</span>
                <span class="num">${fmtInt.format(it.usageCount)}</span>
                <span><span class="category-pill cat-${it.categoryName || "Uncalculable"}">${it.categoryName}</span></span>
                <span class="flags">${itemFlags(it)}</span>
            `;
            el.addEventListener("click", () => { selectItem(it.index); });
            return el;
        }
    });
}

function itemFlags(it) {
    const out = [];
    const f = it.flags;
    if (f & ITEM_FLAG.HAS_CYCLE) out.push(`<span class="flag cycle" title="cycle">⟲</span>`);
    if (f & ITEM_FLAG.IS_INFINITE) out.push(`<span class="flag infinite" title="unobtainable">∞</span>`);
    if (!(f & ITEM_FLAG.HAS_RECIPE)) out.push(`<span class="flag no-recipe" title="no recipe">∅</span>`);
    if (f & ITEM_FLAG.IS_HARDCODED) out.push(`<span class="flag hardcoded" title="hardcoded">H</span>`);
    return out.join("");
}

function formatComplexity(c) {
    if (c < 0) return "—";
    if (!isFinite(c)) return "∞";
    if (c === 0) return "0";
    if (c >= 1e6) return c.toExponential(2);
    return fmt.format(c);
}

function $(id) { return document.getElementById(id); }
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
function debounce(fn, ms) { let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); }; }
