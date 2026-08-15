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

import {setState, state, store, switchTab} from "./state.js";

let suppressHistory = false;

export function initRouter() {
    const tabs = document.querySelectorAll("#main-tabs .tab");
    tabs.forEach(t => t.addEventListener("click", () => {
        document.querySelectorAll(".modal-overlay").forEach(m => m.hidden = true);
        switchTab(t.dataset.tab);
        renderTabs();
        updateUrl();
    }));
    window.addEventListener("popstate", onHistoryNav);

    store.addEventListener("change", updateUrl);

    if (!history.state || typeof history.state.idx !== "number") history.replaceState({idx: 0}, "");

    suppressHistory = true;
    try {
        syncFromUrl();
    } finally {
        suppressHistory = false;
    }
}

function currentHistoryIdx() {
    return (history.state && typeof history.state.idx === "number") ? history.state.idx : 0;
}

export function appBack() {
    if (currentHistoryIdx() > 0) {
        history.back();
        return true;
    }
    return false;
}

function onHistoryNav() {
    suppressHistory = true;
    try {
        syncFromUrl();
    } finally {
        suppressHistory = false;
    }
}

function renderTabs() {
    const active = state.tab;
    document.querySelectorAll("#main-tabs .tab").forEach(t => {
        let isTabActive = t.dataset.tab === active;
        if (t.dataset.tab === "items" && ["item-recipes", "item-machine-recipes", "item-uses", "item-base-sources"].includes(active)) {
            isTabActive = true;
        }
        if (t.dataset.tab === "fluids" && ["fluid-recipes", "fluid-uses"].includes(active)) {
            isTabActive = true;
        }
        if (t.dataset.tab === "mobs" && ["mob-drops"].includes(active)) {
            isTabActive = true;
        }
        t.classList.toggle("active", isTabActive);
    });

    const subContainer = document.getElementById("item-sub-tabs");
    const fluidSubContainer = document.getElementById("fluid-sub-tabs");

    if (subContainer) {
        const isItemTab = ["item-recipes", "item-machine-recipes", "item-uses", "item-base-sources"].includes(active);
        if (isItemTab && state.selectedItem >= 0 && state.db) {
            const item = state.db.items.get(state.selectedItem);
            if (item) {
                const isMachine = state.db.isMachine(state.selectedItem);
                const hasRecipe = (item.flags & 0x01) !== 0;
                const hasUses = item.usageCount > 0;
                const hasBaseSources = (item.baseDataOffset !== 0xFFFFFFFF || item.sourcesOffset !== 0xFFFFFFFF || item.sourceCount > 0);

                const availableTabs = [];
                if (hasRecipe) availableTabs.push("item-recipes");
                if (isMachine) availableTabs.push("item-machine-recipes");
                if (hasUses) availableTabs.push("item-uses");
                if (hasBaseSources) availableTabs.push("item-base-sources");

                const isCurrentTabSubtab = ["item-recipes", "item-machine-recipes", "item-uses", "item-base-sources"].includes(active);

                if (isCurrentTabSubtab && !availableTabs.includes(active)) {
                    const nextTab = availableTabs.length > 0 ? availableTabs[0] : "items";
                    setTimeout(() => {
                        setState({tab: nextTab});
                    }, 0);
                    return;
                }

                let buttons = "";
                if (hasRecipe) {
                    buttons += `<button class="sub-tab ${active === "item-recipes" ? "active" : ""}" data-tab="item-recipes">↳ Recipes in Machines</button>`;
                }
                if (isMachine) {
                    buttons += `<button class="sub-tab ${active === "item-machine-recipes" ? "active" : ""}" data-tab="item-machine-recipes">↳ Machine Output</button>`;
                }
                if (hasUses) {
                    buttons += `<button class="sub-tab ${active === "item-uses" ? "active" : ""}" data-tab="item-uses">↳ Uses of Item</button>`;
                }
                if (hasBaseSources) {
                    buttons += `<button class="sub-tab ${active === "item-base-sources" ? "active" : ""}" data-tab="item-base-sources">↳ Base Sources</button>`;
                }

                if (buttons) {
                    subContainer.style.display = "flex";
                    subContainer.innerHTML = buttons;
                    subContainer.querySelectorAll(".sub-tab").forEach(st => {
                        st.addEventListener("click", () => {
                            setState({tab: st.dataset.tab});
                        });
                    });
                } else {
                    subContainer.style.display = "none";
                    subContainer.innerHTML = "";
                }
            } else {
                subContainer.style.display = "none";
                subContainer.innerHTML = "";
            }
        } else {
            subContainer.style.display = "none";
            subContainer.innerHTML = "";
        }
    }

    if (fluidSubContainer) {
        const isFluidTab = ["fluid-recipes", "fluid-uses"].includes(active);
        if (isFluidTab && state.selectedItem >= 0 && state.db) {
            const fluid = state.db.fluids.get(state.selectedItem);
            if (fluid) {
                const hasRecipe = (fluid.flags & 0x01) !== 0;
                const hasUses = fluid.usageCount > 0;

                const availableTabs = [];
                if (hasRecipe) availableTabs.push("fluid-recipes");
                if (hasUses) availableTabs.push("fluid-uses");

                const isCurrentTabSubtab = ["fluid-recipes", "fluid-uses"].includes(active);

                if (isCurrentTabSubtab && !availableTabs.includes(active)) {
                    const nextTab = availableTabs.length > 0 ? availableTabs[0] : "fluids";
                    setTimeout(() => {
                        setState({tab: nextTab});
                    }, 0);
                    return;
                }

                let buttons = "";
                if (hasRecipe) {
                    buttons += `<button class="sub-tab ${active === "fluid-recipes" ? "active" : ""}" data-tab="fluid-recipes">↳ Recipes in Machines</button>`;
                }
                if (hasUses) {
                    buttons += `<button class="sub-tab ${active === "fluid-uses" ? "active" : ""}" data-tab="fluid-uses">↳ Uses of Fluid</button>`;
                }

                if (buttons) {
                    fluidSubContainer.style.display = "flex";
                    fluidSubContainer.innerHTML = buttons;
                    fluidSubContainer.querySelectorAll(".sub-tab").forEach(st => {
                        st.addEventListener("click", () => {
                            setState({tab: st.dataset.tab});
                        });
                    });
                } else {
                    fluidSubContainer.style.display = "none";
                    fluidSubContainer.innerHTML = "";
                }
            } else {
                fluidSubContainer.style.display = "none";
                fluidSubContainer.innerHTML = "";
            }
        } else {
            fluidSubContainer.style.display = "none";
            fluidSubContainer.innerHTML = "";
        }
    }

    const mobSubContainer = document.getElementById("mob-sub-tabs");
    if (mobSubContainer) {
        const isMobTab = ["mob-drops"].includes(active);
        if (isMobTab && state.selectedMob >= 0 && state.db) {
            const mob = state.db.mobs.get(state.selectedMob);
            if (mob) {
                const hasDrops = mob.dropCount > 0;

                const availableTabs = [];
                if (hasDrops) availableTabs.push("mob-drops");

                const isCurrentTabSubtab = ["mob-drops"].includes(active);

                if (isCurrentTabSubtab && !availableTabs.includes(active)) {
                    const nextTab = "mobs";
                    setTimeout(() => {
                        setState({tab: nextTab});
                    }, 0);
                    return;
                }

                let buttons = "";
                if (hasDrops) {
                    buttons += `<button class="sub-tab ${active === "mob-drops" ? "active" : ""}" data-tab="mob-drops">↳ Drops</button>`;
                }

                if (buttons) {
                    mobSubContainer.style.display = "flex";
                    mobSubContainer.innerHTML = buttons;
                    mobSubContainer.querySelectorAll(".sub-tab").forEach(st => {
                        st.addEventListener("click", () => {
                            setState({tab: st.dataset.tab});
                        });
                    });
                } else {
                    mobSubContainer.style.display = "none";
                    mobSubContainer.innerHTML = "";
                }
            } else {
                mobSubContainer.style.display = "none";
                mobSubContainer.innerHTML = "";
            }
        } else {
            mobSubContainer.style.display = "none";
            mobSubContainer.innerHTML = "";
        }
    }

    document.querySelectorAll("#main-content > .panel").forEach(p => {
        p.hidden = p.id !== "tab-" + active;
        if (p.id === "tab-" + active) p.classList.add("active");
        else p.classList.remove("active");
    });
}

function updateUrl() {
    const params = new URLSearchParams();
    params.set("tab", state.tab);
    if (state.selectedItem >= 0) params.set("item", String(state.selectedItem));
    if (state.selectedMob >= 0) params.set("mob", String(state.selectedMob));
    if (state.subTab) params.set("sub", state.subTab);
    const hash = "#" + params.toString();
    if (location.hash === hash) return;
    const idx = currentHistoryIdx();
    if (suppressHistory) {
        history.replaceState({idx}, "", hash);
    } else {
        history.pushState({idx: idx + 1}, "", hash);
    }
}

function syncFromUrl() {
    const params = new URLSearchParams(location.hash.replace(/^#/, ""));
    const tab = params.get("tab") || "items";
    const item = parseInt(params.get("item") || "-1", 10);
    const mob = parseInt(params.get("mob") || "-1", 10);
    const sub = params.get("sub") || null;
    setState({tab, selectedItem: item, selectedMob: mob, subTab: sub});
    renderTabs();
}

export {renderTabs, updateUrl};