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

export const store = new EventTarget();

const DEFAULT_FILTERS = {
    items: {
        query: "",
        sort: "complexity-desc",
        minComplexity: "", maxComplexity: "",
        minDepth: "", maxDepth: "",
        minTotalIngredients: "", maxTotalIngredients: "",
        minRecipeUsages: "", maxRecipeUsages: "",
        categoriesFilter: [],
        flagsFilter: [],
        modsFilter: []
    },
    fluids: {
        query: "",
        sort: "complexity-desc",
        minComplexity: "", maxComplexity: "",
        minRecipeUsages: "", maxRecipeUsages: "",
        categoriesFilter: [],
        flagsFilter: [],
        modsFilter: []
    },
    mobs: {
        query: "",
        sort: "combatPower-desc",
        modsFilter: [],
        flagsFilter: [],
        minHealth: "", maxHealth: "",
        minDamage: "", maxDamage: "",
        minArmor: "", maxArmor: "",
        minCombatPower: "", maxCombatPower: "",
        minDropCount: "", maxDropCount: "",
        minRarity: "", maxRarity: ""
    },
    sources: {
        query: "",
        sourceType: null,
        sort: "complexity-desc",
        minComplexity: "", maxComplexity: "",
        flagsFilter: [],
        modsFilter: []
    },
};

export const state = {
    db: null,
    tab: "items",
    subTab: null,
    selectedItem: -1,
    selectedMob: -1,
    filters: {
        items: {...DEFAULT_FILTERS.items},
        fluids: {...DEFAULT_FILTERS.fluids},
        mobs: {...DEFAULT_FILTERS.mobs},
        sources: {...DEFAULT_FILTERS.sources},
    },
    graphZoom: 1,
    graphPan: {x: 0, y: 0}
};

export function getDefaultFilters(view) {
    return {...DEFAULT_FILTERS[view]};
}

export function setState(patch) {
    Object.assign(state, patch);
    store.dispatchEvent(new CustomEvent("change", {detail: patch}));
}

export function setFilter(view, patch) {
    Object.assign(state.filters[view], patch);
    store.dispatchEvent(new CustomEvent("filter", {detail: {view, patch}}));
}

export function selectItem(index) {
    setState({selectedItem: index, subTab: "info"});
    store.dispatchEvent(new CustomEvent("selectItem", {detail: index}));
}

export function selectMob(index) {
    setState({selectedMob: index, subTab: "info"});
    store.dispatchEvent(new CustomEvent("selectMob", {detail: index}));
}

export function switchTab(tab) {
    setState({tab, subTab: null, selectedItem: -1, selectedMob: -1});
}