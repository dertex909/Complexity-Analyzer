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

import {debounce, escapeHtml} from "../core/utils.js";
import {closeActivePopover, setActivePopover} from "./resizable-table.js";

const RANGE_KEYS = {
    complexity: ["minComplexity", "maxComplexity"],
    depth: ["minDepth", "maxDepth"],
    totalIngredients: ["minTotalIngredients", "maxTotalIngredients"],
    usageCount: ["minRecipeUsages", "maxRecipeUsages"],
    health: ["minHealth", "maxHealth"],
    damage: ["minDamage", "maxDamage"],
    armor: ["minArmor", "maxArmor"],
    combatPower: ["minCombatPower", "maxCombatPower"],
    dropCount: ["minDropCount", "maxDropCount"],
    rarity: ["minRarity", "maxRarity"]
};

const VIEW_FLAGS = {
    items: [
        {key: "machine", label: "⚙ Machine"},
        {key: "uncalculable", label: "- Uncalculable"},
        {key: "recipe", label: "∅ No Recipe"},
        {key: "hardcoded", label: "H Hardcoded"}
    ],
    fluids: [
        {key: "uncalculable", label: "Uncalculable -"},
        {key: "recipe", label: "No recipe ∅"},
        {key: "protected", label: "Protected P"}
    ],
    mobs: [
        {key: "boss", label: "👑 Boss"},
        {key: "miniboss", label: "⚔️ Miniboss"}
    ],
    sources: [
        {key: "machine", label: "⚙ Machine"},
        {key: "uncalculable", label: "- Uncalculable"},
        {key: "recipe", label: "∅ No Recipe"},
        {key: "hardcoded", label: "H Hardcoded"}
    ]
};

export function openFilterPopover(headerCell, filterType, viewName, db, f, onFilterApplied) {
    const pop = createPopover(headerCell, filterType);

    const resetFilter = () => {
        if (RANGE_KEYS[filterType]) {
            const [minKey, maxKey] = RANGE_KEYS[filterType];
            onFilterApplied({[minKey]: "", [maxKey]: ""});
        } else if (filterType === "category") {
            onFilterApplied({categoriesFilter: []});
        } else if (filterType === "id") {
            onFilterApplied({modsFilter: []});
        } else if (filterType === "flags") {
            onFilterApplied({flagsFilter: []});
        }
        closeActivePopover();
    };

    if (RANGE_KEYS[filterType]) {
        const [minKey, maxKey] = RANGE_KEYS[filterType];
        const minVal = f[minKey] ?? "";
        const maxVal = f[maxKey] ?? "";

        pop.innerHTML = renderRangeInputs(minKey, maxKey, minVal, maxVal);

        pop.querySelector("#filter-reset-btn").addEventListener("click", resetFilter);

        wireRangeInputs(pop, minKey, maxKey, (vals) => {
            onFilterApplied(vals);
        }, debounce);

    } else if (filterType === "category") {
        const categoriesSet = new Set(db.categories.map(c => c.name));
        categoriesSet.add("Uncalculable");
        const categoriesList = Array.from(categoriesSet).sort();

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderCategoryCheckboxes(categoriesList, f.categoriesFilter || [])}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", resetFilter);

        wireCategoryCheckboxes(pop, categoriesList, (checkedCats) => {
            onFilterApplied({categoriesFilter: checkedCats});
        });

    } else if (filterType === "id") {
        const targetTable = viewName === "sources" ? "items" : viewName;
        const modsList = getModNamespaces(db, targetTable);

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Select All (Reset)</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderModCheckboxes(modsList, f.modsFilter || [])}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", resetFilter);

        wireModCheckboxes(pop, modsList, (checkedMods) => {
            onFilterApplied({modsFilter: checkedMods});
        });

    } else if (filterType === "flags") {
        const flagsList = VIEW_FLAGS[viewName] || [];

        pop.innerHTML = `
            <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
            <div class="checkbox-list" style="margin-top: 6px;">
                ${renderFlagCheckboxes(flagsList, f.flagsFilter || [])}
            </div>
        `;

        pop.querySelector("#filter-reset-btn").addEventListener("click", resetFilter);

        wireFlagCheckboxes(pop, (checkedFlags) => {
            onFilterApplied({flagsFilter: checkedFlags});
        });
    }
}

export function positionPopover(popover, headerCell, width) {
    popover.style.width = `${width}px`;

    const rect = headerCell.getBoundingClientRect();
    let leftPos = rect.left;
    const viewportWidth = document.documentElement.clientWidth;

    if (leftPos + width > viewportWidth) leftPos = viewportWidth - width - 16;

    popover.style.left = `${Math.max(10, leftPos)}px`;
    popover.style.top = `${rect.bottom + 4}px`;
}

export function createPopover(headerCell, filterType) {
    closeActivePopover();

    const pop = document.createElement("div");
    pop.className = "filter-popover";
    document.body.appendChild(pop);
    setActivePopover(pop);

    let popWidth = 240;
    if (filterType === "category") popWidth = 250;
    else if (filterType === "id") popWidth = 200;
    else if (filterType === "flags") popWidth = 200;

    positionPopover(pop, headerCell, popWidth);
    return pop;
}

export function extractModNamespace(id) {
    const colonIdx = id.indexOf(":");
    return colonIdx >= 0 ? id.substring(0, colonIdx) : "minecraft";
}

export function getModNamespaces(db, tableType) {
    const allMods = new Set();
    for (let i = 0; i < db[tableType].count; i++) {
        const item = db[tableType].get(i);
        if (item && item.id) allMods.add(extractModNamespace(item.id));
    }
    return Array.from(allMods).sort();
}

export function renderModCheckboxes(modsList, selectedMods) {
    return modsList.map(mod => {
        const checked = selectedMods.includes(mod);
        return `
            <label class="checkbox-item">
                <input type="checkbox" class="mod-cb" value="${escapeHtml(mod)}" ${checked ? "checked" : ""}>
                <span>${escapeHtml(mod)}</span>
            </label>
        `;
    }).join("");
}

export function renderFlagCheckboxes(flagsList, selectedFlags) {
    return flagsList.map(flg => {
        const checked = selectedFlags.includes(flg.key);
        return `
            <label class="checkbox-item">
                <input type="checkbox" class="flag-cb" value="${escapeHtml(flg.key)}" ${checked ? "checked" : ""}>
                <span>${escapeHtml(flg.label)}</span>
            </label>
        `;
    }).join("");
}

export function renderCategoryCheckboxes(categories, selectedCategories) {
    return categories.map(cat => {
        const checked = selectedCategories.includes(cat);
        return `
            <label class="checkbox-item">
                <input type="checkbox" class="cat-cb" value="${escapeHtml(cat)}" ${checked ? "checked" : ""}>
                <span>${escapeHtml(cat)}</span>
            </label>
        `;
    }).join("");
}

export function renderRangeInputs(minKey, maxKey, minVal, maxVal) {
    return `
        <button class="popover-reset" id="filter-reset-btn">Reset Filter</button>
        <div style="font-size:11px; color:var(--text-dim); margin:2px 0 4px 0;">Define range:</div>
        <div class="range-inputs">
            <input type="number" id="filter-min-input" placeholder="From" value="${minVal}">
            <span class="range-label">—</span>
            <input type="number" id="filter-max-input" placeholder="To" value="${maxVal}">
        </div>
    `;
}

export function wireModCheckboxes(popover, modsList, onFilterChange) {
    popover.querySelectorAll(".mod-cb").forEach(cb => {
        cb.addEventListener("change", () => {
            const checkedMods = Array.from(popover.querySelectorAll(".mod-cb:checked")).map(c => c.value);
            if (checkedMods.length === modsList.length) {
                onFilterChange([]);
            } else {
                onFilterChange(checkedMods);
            }
        });
    });
}

export function wireFlagCheckboxes(popover, onFilterChange) {
    popover.querySelectorAll(".flag-cb").forEach(cb => {
        cb.addEventListener("change", () => {
            const checkedFlags = Array.from(popover.querySelectorAll(".flag-cb:checked")).map(c => c.value);
            onFilterChange(checkedFlags);
        });
    });
}

export function wireCategoryCheckboxes(popover, categoriesList, onFilterChange) {
    popover.querySelectorAll(".cat-cb").forEach(cb => {
        cb.addEventListener("change", () => {
            const checkedCats = Array.from(popover.querySelectorAll(".cat-cb:checked")).map(c => c.value);
            if (checkedCats.length === categoriesList.length) {
                onFilterChange([]);
            } else {
                onFilterChange(checkedCats);
            }
        });
    });
}

export function wireRangeInputs(popover, minKey, maxKey, onFilterChange, debounceFn) {
    const onRangeChange = () => {
        const minV = popover.querySelector("#filter-min-input").value;
        const maxV = popover.querySelector("#filter-max-input").value;
        onFilterChange({[minKey]: minV, [maxKey]: maxV});
    };

    popover.querySelector("#filter-min-input").addEventListener("input", debounceFn(onRangeChange, 200));
    popover.querySelector("#filter-max-input").addEventListener("input", debounceFn(onRangeChange, 200));
}
