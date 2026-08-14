/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import {debounce, escapeHtml, fmt, fmtInt, getMobFlags} from "../core/utils.js";
import {selectMob, setFilter, state} from "../core/state.js";
import {MOB_FLAG} from "../core/cabin.js";
import {mountVirtualList} from "../components/virtual-list.js";
import {renderMobDetail} from "./details/mob-detail.js";
import {setupResizableTable} from "../components/resizable-table.js";
import {
    generateSortControlsHtml,
    generateTableHeader,
    universalSort,
    wireSortControls
} from "../components/table-columns.js";
import {openFilterPopover} from "../components/filter-popover.js";
import {passesFlagsFilter, passesModFilter, passesRangeFilter} from "../components/item-filter.js";

const MOBS_COLUMNS = [
    {index: 1, label: "№", field: null, filter: null, sortable: false},
    {index: 2, label: "Name", field: "name", filter: null, sortable: true},
    {index: 3, label: "ID", field: "id", filter: "id", sortable: true},
    {index: 4, label: "HP", field: "health", filter: "health", sortable: true, numeric: true},
    {index: 5, label: "Dmg", field: "damage", filter: "damage", sortable: true, numeric: true},
    {index: 6, label: "Armor", field: "armor", filter: "armor", sortable: true, numeric: true},
    {index: 7, label: "Combat", field: "combatPower", filter: "combatPower", sortable: true, numeric: true},
    {index: 8, label: "Drops", field: "dropCount", filter: "dropCount", sortable: true, numeric: true},
    {index: 9, label: "Rarity", field: "rarity", filter: "rarity", sortable: true, numeric: true},
    {index: 10, label: "Flags", field: "flags", filter: "flags", sortable: true, numeric: true}
];

export async function renderMobs(container) {
    const db = state.db;
    if (!db) return;

    const tableConfig = setupResizableTable({
        tableId: "mobs",
        cssVarPrefix: "--mob-col",
        columnCount: MOBS_COLUMNS.length,
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
            ${generateSortControlsHtml(MOBS_COLUMNS, f.sort, "mobs")}
            <span class="flex-grow"></span>
            <span class="chip" id="mobs-count">0 mobs</span>
        </div>
        <div style="overflow: hidden; flex: 0 0 auto;">
            ${generateTableHeader(MOBS_COLUMNS, "mobs-grid", "mobs-head")}
        </div>
        <div id="mobs-list" style="flex: 1; min-height: 0; position: relative;"></div>
    `;

    wireMobFilters(tableConfig, container);
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

    universalSort(list, MOBS_COLUMNS, f.sort);

    $("mobs-count").textContent = `${fmtInt.format(list.length)} / ${fmtInt.format(db.mobs.count)} mobs`;

    updateHeaderIndicators();

    const mobsList = $("mobs-list");
    mountVirtualList(mobsList, {
        itemCount: list.length,
        itemHeight: 28,
        emptyMessage: "No mobs match your filter.",
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

function wireMobFilters(tableConfig, container) {
    const onInput = debounce((key, val) => {
        setFilter("mobs", {[key]: val});
        updateMobsView();
    }, 120);

    $("mobs-query").addEventListener("input", e => onInput("query", e.target.value));

    wireSortControls(container, "mobs", () => state.filters.mobs.sort, (newSort) => {
        setFilter("mobs", {sort: newSort});
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
    const db = state.db;
    const f = state.filters.mobs;
    openFilterPopover(headerCell, filterType, "mobs", db, f, (patch) => {
        setFilter("mobs", patch);
        updateMobsView();
    });
}

function $(id) {
    return document.getElementById(id);
}

function updateHeaderIndicators() {
    const f = state.filters.mobs;
    const head = document.getElementById("mobs-head");
    if (!head) return;

    MOBS_COLUMNS.forEach(col => {
        if (!col.filter) return;
        let isFiltered = false;
        if (col.filter === "id") isFiltered = f.modsFilter && f.modsFilter.length > 0;
        else if (col.filter === "health") isFiltered = f.minHealth !== "" || f.maxHealth !== "";
        else if (col.filter === "damage") isFiltered = f.minDamage !== "" || f.maxDamage !== "";
        else if (col.filter === "armor") isFiltered = f.minArmor !== "" || f.maxArmor !== "";
        else if (col.filter === "combatPower") isFiltered = f.minCombatPower !== "" || f.maxCombatPower !== "";
        else if (col.filter === "dropCount") isFiltered = f.minDropCount !== "" || f.maxDropCount !== "";
        else if (col.filter === "rarity") isFiltered = f.minRarity !== "" || f.maxRarity !== "";
        else if (col.filter === "flags") isFiltered = f.flagsFilter && f.flagsFilter.length > 0;

        const el = head.querySelector(`[data-filter="${col.filter}"]`);
        if (el) el.classList.toggle("filtered", isFiltered);
    });
}
