import { state, setFilter, selectMob } from "../core/state.js";
import { MOB_FLAG } from "../core/cabin.js";
import { mountVirtualList } from "../components/virtual-list.js";
import { renderMobDetail } from "./mob-detail.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

export function renderMobs(container) {
    const db = state.db;
    if (!db) return;
    const f = state.filters.mobs;

    container.innerHTML = `
        <div class="controls">
            <input type="search" id="mobs-query" placeholder="Filter mobs…" value="${escapeHtml(f.query)}" autocomplete="off">
            <select id="mobs-sort">
                <option value="combatPower-desc" ${f.sort === "combatPower-desc" ? "selected" : ""}>Combat ▼</option>
                <option value="threat-desc" ${f.sort === "threat-desc" ? "selected" : ""}>Threat ▼</option>
                <option value="rarity-desc" ${f.sort === "rarity-desc" ? "selected" : ""}>Rarity ▼</option>
                <option value="health-desc" ${f.sort === "health-desc" ? "selected" : ""}>HP ▼</option>
                <option value="name-asc" ${f.sort === "name-asc" ? "selected" : ""}>Name A-Z</option>
            </select>
            <label class="checkbox"><input type="checkbox" id="mobs-boss" ${f.bossOnly ? "checked" : ""}> bosses</label>
            <label class="checkbox"><input type="checkbox" id="mobs-miniboss" ${f.minibossOnly ? "checked" : ""}> minibosses</label>
            <span class="flex-grow"></span>
            <span class="chip" id="mobs-count">0 mobs</span>
        </div>
        <div class="table-head mobs-grid">
            <span>#</span><span>Name</span><span>ID</span>
            <span class="num">HP</span><span class="num">Dmg</span>
            <span class="num">Armor</span><span class="num">Combat</span>
            <span class="num">Rarity</span><span>Flags</span>
        </div>
        <div id="mobs-list"></div>
    `;

    $("mobs-query").addEventListener("input", debounce(e => { setFilter("mobs", { query: e.target.value }); updateMobsView(); }, 120));
    $("mobs-sort").addEventListener("change", e => { setFilter("mobs", { sort: e.target.value }); updateMobsView(); });
    $("mobs-boss").addEventListener("change", e => { setFilter("mobs", { bossOnly: e.target.checked }); updateMobsView(); });
    $("mobs-miniboss").addEventListener("change", e => { setFilter("mobs", { minibossOnly: e.target.checked }); updateMobsView(); });

    updateMobsView();

    if (state.selectedMob >= 0) {
        renderMobDetail(container, state.selectedMob);
    }
}

function updateMobsView() {
    const db = state.db;
    const f = state.filters.mobs;
    const q = f.query.trim().toLowerCase();
    const list = [];
    for (let i = 0; i < db.mobs.count; i++) {
        const m = db.mobs.get(i);
        if (f.bossOnly && !(m.flags & MOB_FLAG.BOSS)) continue;
        if (f.minibossOnly && !(m.flags & MOB_FLAG.MINIBOSS)) continue;
        if (q && !(m.name + " " + m.id).toLowerCase().includes(q)) continue;
        list.push(m);
    }
    const [field, dir] = f.sort.split("-");
    const sign = dir === "asc" ? 1 : -1;
    const getVal = { combatPower: m => m.combatPower, threat: m => m.threat, rarity: m => m.rarity, health: m => m.health, name: m => m.name }[field] || (m => m.combatPower);
    list.sort((a, b) => {
        const av = getVal(a), bv = getVal(b);
        if (typeof av === "number") return sign * (av - bv);
        return sign * String(av).localeCompare(String(bv));
    });

    $("mobs-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(db.mobs.count)} mobs`;

    mountVirtualList($("mobs-list"), {
        itemCount: list.length,
        itemHeight: 28,
        renderRow: (absIndex) => {
            const m = list[absIndex];
            const el = document.createElement("div");
            el.className = "row mobs-grid";
            el.innerHTML = `
                <span class="idx">${absIndex + 1}</span>
                <span>${escapeHtml(m.name)}</span>
                <span class="id">${m.id}</span>
                <span class="num">${fmt.format(m.health)}</span>
                <span class="num">${fmt.format(m.damage)}</span>
                <span class="num">${fmt.format(m.armor)}</span>
                <span class="num">${fmt.format(m.combatPower)}</span>
                <span class="num">${fmt.format(m.rarity)}</span>
                <span class="flags">${mobFlags(m)}</span>
            `;
            el.addEventListener("click", () => selectMob(m.index));
            return el;
        }
    });
}

function mobFlags(m) {
    const out = [];
    if (m.flags & MOB_FLAG.BOSS) out.push(`<span class="flag boss">B</span>`);
    if (m.flags & MOB_FLAG.MINIBOSS) out.push(`<span class="flag miniboss">m</span>`);
    return out.join("");
}

function $(id) { return document.getElementById(id); }
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
function debounce(fn, ms) { let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); }; }
