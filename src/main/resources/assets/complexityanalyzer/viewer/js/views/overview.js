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

import {setFilter, state, switchTab} from "../core/state.js";
import {escapeHtml, fmtInt} from "../core/utils.js";
import {extractModNamespace} from "../components/item-filter.js";
import {renderGenericTable} from "../components/generic-table.js";

const MOD_COLUMNS = [
    {index: 1, label: "№", field: null, sortable: false},
    {index: 2, label: "Mod ID", field: "id", filter: null, sortable: true},
    {index: 3, label: "Version", field: "modVersion", filter: null, sortable: true},
    {index: 4, label: "Items", field: "itemCount", filter: null, sortable: true, numeric: true},
    {index: 5, label: "Mobs", field: "mobCount", filter: null, sortable: true, numeric: true},
    {index: 6, label: "Fluids", field: "fluidCount", filter: null, sortable: true, numeric: true},
    {index: 7, label: "Machines", field: "machineCount", filter: null, sortable: true, numeric: true},
    {index: 8, label: "Recognized recipes", field: "recipeCount", filter: null, sortable: true, numeric: true}
];

function collectMods(db) {
    const mods = new Map();
    const getMod = (modId) => {
        if (!mods.has(modId)) mods.set(modId, {
            id: modId, name: modId, modId, modVersion: "", itemCount: 0, mobCount: 0,
            fluidCount: 0, machineCount: 0, recipeCount: 0
        });
        return mods.get(modId);
    };

    for (const entry of db.modSummary || []) {
        const mod = getMod(entry.modId);
        mod.modVersion = entry.modVersion || "";
        mod.recipeCount = entry.recipeCount || 0;
    }

    for (let i = 0; i < db.items.count; i++) {
        const item = db.items.get(i);
        const mod = getMod(extractModNamespace(item.id));
        mod.itemCount++;
        if (db.isMachine(item.index)) mod.machineCount++;
    }
    for (let i = 0; i < db.mobs.count; i++) {
        const mob = db.mobs.get(i);
        getMod(extractModNamespace(mob.id)).mobCount++;
    }
    for (let i = 0; i < db.fluids.count; i++) {
        const fluid = db.fluids.get(i);
        getMod(extractModNamespace(fluid.id)).fluidCount++;
    }
    return [...mods.values()];
}

function renderCountLink(label, count, modId, target) {
    return `<button class="mod-count-link" data-mod-target="${target}" data-mod-id="${escapeHtml(modId)}" ${count === 0 ? "disabled" : ""}>
        ${fmtInt.format(count)}
    </button>`;
}

function openModTable(target, modId) {
    if (target === "items" || target === "machines") {
        setFilter("items", {
            query: "", modsFilter: [modId], categoriesFilter: [],
            flagsFilter: target === "machines" ? ["machine"] : [],
            minComplexity: "", maxComplexity: "", minDepth: "", maxDepth: "",
            minTotalIngredients: "", maxTotalIngredients: "", minRecipeUsages: "", maxRecipeUsages: ""
        });
        switchTab("items");
    } else if (target === "mobs") {
        setFilter("mobs", {
            query: "", modsFilter: [modId], flagsFilter: [],
            minHealth: "", maxHealth: "", minDamage: "", maxDamage: "",
            minArmor: "", maxArmor: "", minCombatPower: "", maxCombatPower: "",
            minDropCount: "", maxDropCount: "", minRarity: "", maxRarity: ""
        });
        switchTab("mobs");
    } else if (target === "fluids") {
        setFilter("fluids", {
            query: "", modsFilter: [modId], categoriesFilter: [], flagsFilter: [],
            minComplexity: "", maxComplexity: "", minRecipeUsages: "", maxRecipeUsages: ""
        });
        switchTab("fluids");
    }
}

function renderModsTable(container, mods) {
    renderGenericTable(container, {
        id: "mods",
        tableType: "mods",
        cssVarPrefix: "--mod-col",
        gridClass: "mods-grid",
        columns: MOD_COLUMNS,
        searchPlaceholder: "Filter by mod ID or version…",
        entityLabel: "mods",
        getEntities: () => mods,
        renderRow: (mod, index) => {
            const row = document.createElement("div");
            row.className = "row mods-grid";
            row.innerHTML = `
                <span class="idx">${index + 1}</span>
                <span class="id notranslate" translate="no" title="${escapeHtml(mod.modId)}">${escapeHtml(mod.modId)}</span>
                <span class="notranslate" translate="no" title="${escapeHtml(mod.modVersion)}">${escapeHtml(mod.modVersion || "-")}</span>
                <span class="mod-count-cell">${renderCountLink("items", mod.itemCount, mod.modId, "items")}</span>
                <span class="mod-count-cell">${renderCountLink("mobs", mod.mobCount, mod.modId, "mobs")}</span>
                <span class="mod-count-cell">${renderCountLink("fluids", mod.fluidCount, mod.modId, "fluids")}</span>
                <span class="mod-count-cell">${renderCountLink("machines", mod.machineCount, mod.modId, "machines")}</span>
                <span class="num">${fmtInt.format(mod.recipeCount)}</span>
            `;
            row.querySelectorAll("[data-mod-target]").forEach(button => {
                button.addEventListener("click", event => {
                    event.stopPropagation();
                    openModTable(button.dataset.modTarget, button.dataset.modId);
                });
            });
            return row;
        }
    });
}

export function renderOverview(container) {
    const db = state.db;
    if (!db) return;
    const m = db.meta;
    const stats = [
        {label: "Items", value: m.itemCount},
        {label: "Fluids", value: m.fluidCount},
        {label: "Mobs", value: m.mobCount},
        {label: "Recipes", value: m.recipeCount},
        {label: "Valid items", value: m.validItems},
        {label: "Uncalculable", value: m.infiniteItems},
        {label: "Machines", value: m.machineCount},
        {label: "Mods", value: m.modCount},
    ];

    container.innerHTML = `
        <h2>Overview</h2>
        <div class="stat-grid">
            ${stats.map(s => `
                <div class="card">
                    <div class="value">${fmtInt.format(s.value)}</div>
                    <div class="label">${s.label}</div>
                </div>
            `).join("")}
        </div>
        <div class="card">
            <h4>File details</h4>
            <div class="detail-grid">
                <dt>Hash</dt><dd><code class="mono-code">0x${db.file.fileHash.toString(16)}</code></dd>
                <dt>Generated</dt><dd>${m.timestampMs ? new Date(m.timestampMs).toLocaleString() : escapeHtml(m.timestampStr)}</dd>
                <dt>Format version</dt><dd>0x${Number(db.file.version).toString(16)}</dd>
                <dt>Server</dt><dd>${escapeHtml(m.serverName)}</dd>
            </div>
        </div>
        <details class="card mod-list-card">
            <summary>Mods</summary>
            <div id="mods-table"></div>
        </details>
    `;

    const mods = collectMods(db);
    const modsCategory = container.querySelector(".mod-list-card");
    let tableRendered = false;
    modsCategory.addEventListener("toggle", () => {
        if (!modsCategory.open && tableRendered) return;
        if (modsCategory.open && !tableRendered) {
            renderModsTable(container.querySelector("#mods-table"), mods);
            tableRendered = true;
        }
    });
}
