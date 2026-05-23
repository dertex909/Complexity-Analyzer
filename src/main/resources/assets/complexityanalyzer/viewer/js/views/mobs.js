import {
    escapeHtml,
    debounce,
    getMobFlags,
    fmt,
    fmtInt
} from "../core/utils.js";
import {state, setFilter, selectMob, getDefaultFilters} from "../core/state.js";
import {MOB_FLAG} from "../core/cabin.js";
import {mountVirtualList} from "../components/virtual-list.js";
import {renderMobDetail} from "./details/mob-detail.js";
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

const MOBS_COLUMNS = [
    {index: 1, label: "№", filter: null, sortable: false},
    {index: 2, label: "Name", filter: null, sortable: true},
    {index: 3, label: "ID", filter: "id", sortable: true},
    {index: 4, label: "HP", filter: "health", sortable: true, numeric: true},
    {index: 5, label: "Dmg", filter: "damage", sortable: true, numeric: true},
    {index: 6, label: "Armor", filter: "armor", sortable: true, numeric: true},
    {index: 7, label: "Combat", filter: "combatPower", sortable: true, numeric: true},
    {index: 8, label: "Drops", filter: "dropCount", sortable: true, numeric: true},
    {index: 9, label: "Rarity", filter: "rarity", sortable: true, numeric: true},
    {index: 10, label: "Flags", filter: "flags", sortable: false}
];

export async function renderMobs(container) {
    const db = state.db;
    if (!db) return;

    const tableConfig = setupResizableTable({
        tableId: "mobs",
        cssVarPrefix: "--mob-col",
        columnCount: 10,
        headingColumns: [
            {index: 4, label: "HP"},
            {index: 5, label: "Dmg"},
            {index: 6, label: "Armor"},
            {index: 7, label: "Combat"},
            {index: 8, label: "Drops"},
            {index: 9, label: "Rarity"}
        ],
        flagsColumn: {
            index: 10,
            flagChecks: [
                f => f & MOB_FLAG.BOSS,
                f => f & MOB_FLAG.MINIBOSS
            ]
        },
        db,
        tableType: "mobs"
    });

    const f = state.filters.mobs;

    container.innerHTML = `
        <div class="controls">
            <input type="search" id="mobs-query" placeholder="Filter mobs…" value="${escapeHtml(f.query)}" autocomplete="off">
            <select id="mobs-sort">
                <option value="combatPower-desc" ${f.sort === "combatPower-desc" ? "selected" : ""}>Combat ▼</option>
                <option value="threat-desc" ${f.sort === "threat-desc" ? "selected" : ""}>Threat ▼</option>
                <option value="dropCount-desc" ${f.sort === "dropCount-desc" ? "selected" : ""}>Drops ▼</option>
                <option value="rarity-desc" ${f.sort === "rarity-desc" ? "selected" : ""}>Rarity ▼</option>
                <option value="health-desc" ${f.sort === "health-desc" ? "selected" : ""}>HP ▼</option>
                <option value="name-asc" ${f.sort === "name-asc" ? "selected" : ""}>Name A-Z</option>
            </select>
            <span class="flex-grow"></span>
            <span class="chip" id="mobs-count">0 mobs</span>
        </div>
        ${generateTableHeader(MOBS_COLUMNS, "mobs-grid", "mobs-head")}
        <div id="mobs-list"></div>
    `;

    wireMobFilters(tableConfig);
    updateHeaderIndicators();
    updateMobsView();

    if (state.selectedMob >= 0) await renderMobDetail(null, state.selectedMob);
}

function updateMobsView() {
    const db = state.db;
    const f = state.filters.mobs;
    const q = f.query.trim().toLowerCase();
    const list = [];
    for (let i = 0; i < db.mobs.count; i++) {
        const m = db.mobs.get(i);

        if (q && !(m.name + " " + m.id).toLowerCase().includes(q)) continue;

        if (!passesModFilter(m, f.modsFilter)) continue;

        if (!passesFlagsFilter(m, f.flagsFilter, MOB_FLAG)) continue;

        if (!passesRangeFilter(m.health, f.minHealth, f.maxHealth)) continue;
        if (!passesRangeFilter(m.damage, f.minDamage, f.maxDamage)) continue;
        if (!passesRangeFilter(m.armor, f.minArmor, f.maxArmor)) continue;
        if (!passesRangeFilter(m.combatPower, f.minCombatPower, f.maxCombatPower)) continue;
        if (!passesRangeFilter(m.dropCount, f.minDropCount, f.maxDropCount)) continue;
        if (!passesRangeFilter(m.rarity, f.minRarity, f.maxRarity)) continue;

        list.push(m);
    }
    const [field, dir] = f.sort.split("-");
    const sign = dir === "asc" ? 1 : -1;
    const getVal = {
        combatPower: m => m.combatPower,
        threat: m => m.threat,
        rarity: m => m.rarity,
        health: m => m.health,
        dropCount: m => m.dropCount,
        name: m => m.name
    }[field] || (m => m.combatPower);
    list.sort((a, b) => {
        const av = getVal(a), bv = getVal(b);
        if (typeof av === "number") return sign * (av - bv);
        return sign * String(av).localeCompare(String(bv));
    });

    $("mobs-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(db.mobs.count)} mobs`;

    updateHeaderIndicators();

    const mobsList = $("mobs-list");
    if (list.length === 0) {
        mobsList.innerHTML = `
            <div class="virtual-viewport" style="display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; width: 100%; flex: 1;">
                <div class="empty-state" style="padding: 40px 0;">
                    <div class="icon">🔍</div>
                    <div class="message">No mobs match your filter.</div>
                </div>
            </div>`;
        return;
    }
    mountVirtualList(mobsList, {
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
                <span class="num" style="font-weight: 500; color: var(--accent);">${fmtInt.format(m.dropCount)}</span>
                <span class="num">${fmt.format(m.rarity)}</span>
                <span class="flags">${getMobFlags(m)}</span>
            `;
            el.addEventListener("click", () => {
                selectMob(m.index);
            });
            return el;
        }
    });
}

function wireMobFilters(tableConfig) {
    const onInput = debounce((key, val) => {
        setFilter("mobs", {[key]: val});
        updateMobsView();
    }, 120);
    $("mobs-query").addEventListener("input", e => onInput("query", e.target.value));
    $("mobs-sort").addEventListener("change", e => {
        setFilter("mobs", {sort: e.target.value});
        updateMobsView();
    });

    const head = document.getElementById("mobs-head");
    if (head) head.querySelectorAll(".clickable-header").forEach(hdr => {
        hdr.addEventListener("click", (e) => {
            if (e.target.classList.contains("col-drag-handle")) return;
            openPopover(hdr, hdr.dataset.filter);
        });
    });

    tableConfig.initResizers("mobs-head");
}

function openPopover(headerCell, filterType) {
    const pop = createPopover(headerCell, filterType);

    const f = state.filters.mobs;
    const db = state.db;

    if (filterType === "health" || filterType === "damage" || filterType === "armor" || filterType === "combatPower" || filterType === "dropCount" || filterType === "rarity") {
        let minKey, maxKey;
        if (filterType === "health") {
            minKey = "minHealth";
            maxKey = "maxHealth";
        } else if (filterType === "damage") {
            minKey = "minDamage";
            maxKey = "maxDamage";
        } else if (filterType === "armor") {
            minKey = "minArmor";
            maxKey = "maxArmor";
        } else if (filterType === "combatPower") {
            minKey = "minCombatPower";
            maxKey = "maxCombatPower";
        } else if (filterType === "dropCount") {
            minKey = "minDropCount";
            maxKey = "maxDropCount";
        } else {
            minKey = "minRarity";
            maxKey = "maxRarity";
        }

        const minVal = f[minKey] ?? "";
        const maxVal = f[maxKey] ?? "";

        pop.innerHTML = renderRangeInputs(minKey, maxKey, minVal, maxVal);

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("mobs", getDefaultFilters("mobs"));
            updateMobsView();
            closeActivePopover();
        });

        wireRangeInputs(pop, minKey, maxKey, (vals) => {
            setFilter("mobs", vals);
            updateMobsView();
        }, debounce);

    } else if (filterType === "id") {
        const modsList = getModNamespaces(db, "mobs");

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderModCheckboxes(modsList, f.modsFilter || [])}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("mobs", getDefaultFilters("mobs"));
            updateMobsView();
            closeActivePopover();
        });

        wireModCheckboxes(pop, modsList, (checkedMods) => {
            setFilter("mobs", {modsFilter: checkedMods});
            updateMobsView();
        });

    } else if (filterType === "flags") {
        const flagsList = [
            {key: "boss", label: "👑 Boss"},
            {key: "miniboss", label: "⚔️ Miniboss"}
        ];

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderFlagCheckboxes(flagsList, f.flagsFilter || [])}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", () => {
            setFilter("mobs", getDefaultFilters("mobs"));
            updateMobsView();
            closeActivePopover();
        });

        wireFlagCheckboxes(pop, (checkedFlags) => {
            setFilter("mobs", {flagsFilter: checkedFlags});
            updateMobsView();
        });
    }
}

function $(id) {
    return document.getElementById(id);
}

function updateHeaderIndicators() {
    const f = state.filters.mobs;
    const head = document.getElementById("mobs-head");
    if (!head) return;

    const itemsDef = [
        {key: "id", isFiltered: () => f.modsFilter && f.modsFilter.length > 0},
        {key: "health", isFiltered: () => f.minHealth !== "" || f.maxHealth !== ""},
        {key: "damage", isFiltered: () => f.minDamage !== "" || f.maxDamage !== ""},
        {key: "armor", isFiltered: () => f.minArmor !== "" || f.maxArmor !== ""},
        {key: "combatPower", isFiltered: () => f.minCombatPower !== "" || f.maxCombatPower !== ""},
        {key: "dropCount", isFiltered: () => f.minDropCount !== "" || f.maxDropCount !== ""},
        {key: "rarity", isFiltered: () => f.minRarity !== "" || f.maxRarity !== ""},
        {key: "flags", isFiltered: () => f.flagsFilter && f.flagsFilter.length > 0},
    ];

    itemsDef.forEach(item => {
        const el = head.querySelector(`[data-filter="${item.key}"]`);
        if (el) el.classList.toggle("filtered", item.isFiltered());
    });
}