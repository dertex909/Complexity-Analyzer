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

import {escapeHtml, formatComplexity, formatRawTooltip, getItemFlags} from "../core/utils.js";
import {selectItem, state} from "../core/state.js";
import {ITEM_FLAG} from "../core/cabin.js";
import {renderGenericTable} from "../components/generic-table.js";

const SOURCES_COLUMNS = [
    {index: 1, label: "№", field: null, filter: null, sortable: false},
    {index: 2, label: "ID", field: "id", filter: "id", sortable: true},
    {index: 3, label: "Name", field: "name", filter: null, sortable: true},
    {index: 4, label: "Source Complexity", field: "complexity", filter: "complexity", sortable: true, numeric: true},
    {index: 5, label: "Flags", field: "flags", filter: "flags", sortable: true, numeric: true}
];

const SOURCE_TYPE_TRANSLATIONS = {
    "loot_table": "Loot Tables",
    "world_gen": "World Gen",
    "piglin_barter": "Piglin Barter",
    "ore": "Ore Veins",
    "slaying": "Slaying",
    "crop_drop": "Crop Drops",
    "bee_drop": "Bee Drops",
    "quest": "Quests",
    "trading": "Trading",
    "fishing": "Fishing",
    "alchemy": "Alchemy",
    "archaeology": "Archaeology"
};

export function formatSourceTypeName(name) {
    if (!name) return "";
    if (SOURCE_TYPE_TRANSLATIONS[name]) return SOURCE_TYPE_TRANSLATIONS[name];

    let clean = name;
    const rawPrefix = /^complexityanalyzer\.source_type\./i;
    if (rawPrefix.test(clean)) clean = clean.replace(rawPrefix, "");

    const rawDisplayPrefix = /^complexityanalyzer\.source\s+type\./i;
    if (rawDisplayPrefix.test(clean)) clean = clean.replace(rawDisplayPrefix, "");

    let displayName = clean.split('_')
        .map(w => w.charAt(0).toUpperCase() + w.slice(1))
        .join(' ');

    const displayPrefix = /^complexityanalyzer\.source\s+type\./i;
    if (displayPrefix.test(displayName)) displayName = displayName.replace(displayPrefix, "");

    if (displayName.length > 0) displayName = displayName.charAt(0).toUpperCase() + displayName.slice(1);
    return displayName;
}

let resolvedSourceTypes = null;

async function ensureSourceTypes(db) {
    if (resolvedSourceTypes) return resolvedSourceTypes;

    const types = [];
    for (const entry of db.sourceTypes) {
        let name = "";
        if (entry.items.length > 0) {
            const itIdx = entry.items[0];
            const base = await db.getItemBaseData(itIdx);
            if (base && base.sourceTypeEnum === entry.typeEnum) {
                name = base.sourceType;
            } else {
                const sources = await db.getItemSources(itIdx);
                const matched = sources.find(s => s.sourceTypeEnum === entry.typeEnum);
                if (matched) name = matched.sourceType;
            }
        }
        if (!name) name = `type_${entry.typeEnum}`;
        types.push({
            typeEnum: entry.typeEnum,
            items: entry.items,
            rawName: name,
            displayName: formatSourceTypeName(name)
        });
    }

    types.sort((a, b) => b.items.length - a.items.length);
    resolvedSourceTypes = types;
    return resolvedSourceTypes;
}

async function ensureSourceComplexities(db, activeType) {
    if (activeType.sourceComplexities) return activeType.sourceComplexities;

    if (activeType.items.length > 0) try {
        await db.getItemSources(activeType.items[0]);
    } catch (e) {
        console.error(e);
    }

    const complexities = new Map();
    const promises = activeType.items.map(async itemIdx => {
        const item = db.items.get(itemIdx);
        if (!item) return;

        let sc = item.complexity;
        try {
            const sources = await db.getItemSources(itemIdx);
            const matched = sources.find(s => s.sourceTypeEnum === activeType.typeEnum);
            if (matched && isFinite(matched.estimatedCost) && matched.estimatedCost >= 0) sc = matched.estimatedCost;
        } catch (e) {
            console.error("Error loading source complexity for", itemIdx, e);
        }
        complexities.set(itemIdx, sc);
    });

    await Promise.all(promises);
    activeType.sourceComplexities = complexities;
    return complexities;
}

export async function renderSources(container) {
    const db = state.db;
    if (!db) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">Database not initialized.</div></div>`;
        return;
    }

    container.style.padding = "0";
    container.style.overflow = "hidden";
    container.style.display = "flex";
    container.style.flexDirection = "row";
    container.style.gap = "0";
    container.style.alignItems = "stretch";

    container.innerHTML = `
        <style>
            @media (max-width: 768px) {
                .sources-sidebar {
                    position: absolute;
                    top: 0;
                    bottom: 0;
                    left: 0;
                    z-index: 1000;
                    transform: translateX(-100%);
                    transition: transform 0.2s ease;
                    box-shadow: 5px 0 20px rgba(0,0,0,0.5);
                }
                .sources-sidebar.open {
                    transform: translateX(0);
                }
                .sources-burger {
                    display: inline-flex !important;
                }
                .sources-close-mobile {
                    display: inline-block !important;
                }
                .sources-overlay {
                    display: none;
                    position: absolute;
                    inset: 0;
                    background: rgba(0,0,0,0.5);
                    z-index: 999;
                }
                .sources-overlay.open {
                    display: block;
                }
            }
        </style>
        <div class="sources-overlay" id="sources-overlay"></div>
        <div class="sources-sidebar" id="sources-sidebar" style="width: 250px; flex: 0 0 250px; border-right: 1px solid var(--border); overflow-y: auto; padding: 18px; display: flex; flex-direction: column; gap: 16px; background: linear-gradient(180deg, rgba(17, 20, 24, 0.98), rgba(24, 29, 36, 0.98));">
            <div class="sidebar-header" style="display: flex; flex-direction: column; gap: 8px; padding-bottom: 14px; border-bottom: 1px solid var(--border); flex: 0 0 auto;">
                <div class="sidebar-title" style="display: flex; justify-content: space-between; align-items: center; font-size: 12px; font-weight: 700; letter-spacing: 1px; color: var(--text-muted); text-transform: uppercase; font-family: var(--sans), sans-serif;">
                    <span>Sources</span>
                    <button class="sources-close-mobile" style="display: none; background: none; border: none; color: var(--text-muted); font-size: 16px; cursor: pointer;">✕</button>
                </div>
            </div>
            <div id="sources-categories-list" style="display: flex; flex-direction: column; gap: 4px;">
                <div class="hint">Loading sources…</div>
            </div>
        </div>
        <div class="sources-main" id="sources-main-table-container" style="flex: 1; display: flex; flex-direction: column; overflow: hidden; position: relative;"></div>
    `;

    try {
        const types = await ensureSourceTypes(db);
        if (types.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="icon">❔</div><div class="message">No base sources found in the database.</div></div>`;
            return;
        }

        if (state.filters.sources.sourceType === null || !types.some(t => t.typeEnum === state.filters.sources.sourceType)) {
            state.filters.sources.sourceType = types[0].typeEnum;
        }

        const catList = container.querySelector("#sources-categories-list");
        const tableMain = container.querySelector("#sources-main-table-container");

        let currentTableCtrl = null;

        const loadAndMountTable = async () => {
            const activeEnum = state.filters.sources.sourceType;
            const activeType = types.find(t => t.typeEnum === activeEnum);
            if (!activeType) return;

            const sourceComplexities = await ensureSourceComplexities(db, activeType);

            currentTableCtrl = renderGenericTable(tableMain, {
                id: "sources",
                tableType: "items",
                cssVarPrefix: "--src-col",
                gridClass: "sources-grid",
                columns: SOURCES_COLUMNS,
                flagEnum: ITEM_FLAG,
                searchPlaceholder: "Filter items by name, ID...",
                entityLabel: "items",
                controlsPrefixHtml: `<button class="sources-burger btn" style="display: none; padding: 6px 10px; font-size: 16px; align-items: center; justify-content: center; height: 32px;" title="Categories">☰</button>`,
                getEntities: () => activeType.items.map(idx => db.items.get(idx)).filter(Boolean),
                customFilter: (it, currentFilters) => {
                    const compVal = sourceComplexities.get(it.index) ?? it.complexity;
                    const min = currentFilters.minComplexity !== "" ? parseFloat(currentFilters.minComplexity) : -Infinity;
                    const max = currentFilters.maxComplexity !== "" ? parseFloat(currentFilters.maxComplexity) : Infinity;
                    return compVal >= min && compVal <= max;
                },
                customSortGetter: (it, field) => {
                    if (field === "complexity") {
                        const sc = sourceComplexities.get(it.index);
                        return isFinite(sc) ? sc : it.complexity;
                    }
                    return it[field];
                },
                renderRow: (it, absIndex) => {
                    const sc = sourceComplexities.get(it.index) ?? it.complexity;
                    const el = document.createElement("div");
                    el.className = "row sources-grid";
                    el.style.cursor = "pointer";
                    el.innerHTML = `
                        <span class="idx">${absIndex + 1}</span>
                        <span class="id" title="${it.id}">${it.id}</span>
                        <span title="${escapeHtml(it.name)}">${escapeHtml(it.name)}</span>
                        <span class="num cat-${it.categoryName || "Uncalculable"}" title="${formatRawTooltip(sc)}">${formatComplexity(sc)}</span>
                        <span class="flags">${getItemFlags(it, true)}</span>
                    `;
                    return el;
                },
                onRowClick: (it) => selectItem(it.index)
            });

            const burgerBtn = tableMain.querySelector(".sources-burger");
            if (burgerBtn) burgerBtn.addEventListener("click", toggleSidebar);
        };

        const toggleSidebar = () => {
            const sidebarEl = container.querySelector("#sources-sidebar");
            const overlayEl = container.querySelector("#sources-overlay");
            if (sidebarEl && overlayEl) {
                sidebarEl.classList.toggle("open");
                overlayEl.classList.toggle("open");
            }
        };

        const overlayEl = container.querySelector("#sources-overlay");
        const mobileCloseBtn = container.querySelector(".sources-close-mobile");
        if (overlayEl) overlayEl.addEventListener("click", toggleSidebar);
        if (mobileCloseBtn) mobileCloseBtn.addEventListener("click", toggleSidebar);

        catList.innerHTML = types.map(t => `
            <button class="tab ${t.typeEnum === state.filters.sources.sourceType ? "active" : ""}" data-type="${t.typeEnum}" style="display:flex; justify-content:space-between; align-items:center; width:100%; padding: 10px 14px; font-size:13px; font-weight: 500;">
                <span style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding-right:8px;" title="${escapeHtml(t.displayName)}">${escapeHtml(t.displayName)}</span>
                <span class="chip" style="font-size:10px; padding:1px 6px; background:rgba(255,255,255,0.06); font-family:var(--mono), monospace; color: var(--text-muted); border-radius: 10px;">${t.items.length}</span>
            </button>
        `).join("");

        catList.querySelectorAll("button").forEach(btn => {
            btn.addEventListener("click", async () => {
                state.filters.sources.sourceType = parseInt(btn.dataset.type, 10);
                catList.querySelectorAll("button").forEach(b => b.classList.toggle("active", b === btn));
                await loadAndMountTable();
                if (window.innerWidth <= 768) toggleSidebar();
            });
        });

        await loadAndMountTable();
    } catch (e) {
        container.innerHTML = `<div style="padding: 20px; color:var(--err)">Error loading base sources: ${escapeHtml(String(e))}</div>`;
    }
}