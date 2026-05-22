import {state, setState, store, switchTab} from "./state.js";

export function initRouter() {
    const tabs = document.querySelectorAll("#main-tabs .tab");
    tabs.forEach(t => t.addEventListener("click", () => {
        switchTab(t.dataset.tab);
        renderTabs();
        updateUrl();
    }));
    window.addEventListener("hashchange", syncFromUrl);

    store.addEventListener("change", updateUrl);

    syncFromUrl();
}

function renderTabs() {
    const active = state.tab;
    document.querySelectorAll("#main-tabs .tab").forEach(t => t.classList.toggle("active", t.dataset.tab === active));

    const subContainer = document.getElementById("item-sub-tabs");
    const fluidSubContainer = document.getElementById("fluid-sub-tabs");

    if (subContainer) {
        const isItemTab = ["items", "item-recipes", "item-machine-recipes", "item-uses", "item-base-sources"].includes(active);
        if (isItemTab && state.selectedItem >= 0 && state.db) {
            const item = state.db.items.get(state.selectedItem);
            if (item) {
                const isMachine = state.db.machines.some(m => m.itemIndex === state.selectedItem);
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
        const isFluidTab = ["fluids", "fluid-recipes", "fluid-uses"].includes(active);
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
    if (location.hash !== hash) history.replaceState(null, "", hash);
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