export const store = new EventTarget();

export const state = {
    db: null,
    tab: "items",
    subTab: null,
    selectedItem: -1,
    selectedMob: -1,
    filters: {
        items: { query: "", sort: "complexity-desc", category: "", finiteOnly: false, hasRecipe: false, hasCycle: false, isHardcoded: false, minDepth: "", maxDepth: "", minComplexity: "", maxComplexity: "" },
        fluids: { query: "", sort: "name-asc", category: "" },
        mobs: { query: "", sort: "combatPower-desc", bossOnly: false, minibossOnly: false },
        sources: { query: "", sourceType: null },
    },
    graphZoom: 1,
    graphPan: { x: 0, y: 0 },
};

export function setState(patch) {
    Object.assign(state, patch);
    store.dispatchEvent(new CustomEvent("change", { detail: patch }));
}

export function setFilter(view, patch) {
    Object.assign(state.filters[view], patch);
    store.dispatchEvent(new CustomEvent("filter", { detail: { view, patch } }));
}

export function selectItem(index) {
    state.selectedItem = index;
    state.subTab = "info";
    store.dispatchEvent(new CustomEvent("selectItem", { detail: index }));
}

export function selectMob(index) {
    state.selectedMob = index;
    state.subTab = "info";
    store.dispatchEvent(new CustomEvent("selectMob", { detail: index }));
}

export function setSubTab(tab) {
    state.subTab = tab;
    store.dispatchEvent(new CustomEvent("subTab", { detail: tab }));
}

export function switchTab(tab) {
    setState({ tab, subTab: null, selectedItem: -1, selectedMob: -1 });
}
