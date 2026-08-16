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

import {escapeHtml, fmt, fmtInt, getMobFlags} from "../core/utils.js";
import {selectMob, state} from "../core/state.js";
import {MOB_FLAG} from "../core/cabin.js";
import {renderMobDetail} from "./details/mob-detail.js";
import {renderGenericTable} from "../components/generic-table.js";

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
    renderGenericTable(container, {
        id: "mobs",
        tableType: "mobs",
        cssVarPrefix: "--mob-col",
        gridClass: "mobs-grid",
        columns: MOBS_COLUMNS,
        flagEnum: MOB_FLAG,
        searchPlaceholder: "Filter mobs…",
        entityLabel: "mobs",
        renderRow: (m, absIndex) => {
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
            return el;
        },
        onRowClick: (m) => selectMob(m.index)
    });

    if (state.selectedMob >= 0) await renderMobDetail(null, state.selectedMob);
}