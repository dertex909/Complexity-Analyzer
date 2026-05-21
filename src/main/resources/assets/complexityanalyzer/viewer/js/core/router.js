import { state, setState, switchTab } from "./state.js";

export function initRouter() {
    const tabs = document.querySelectorAll("#main-tabs .tab");
    tabs.forEach(t => t.addEventListener("click", () => { switchTab(t.dataset.tab); renderTabs(); updateUrl(); }));
    window.addEventListener("hashchange", syncFromUrl);
    syncFromUrl();
}

function renderTabs() {
    const active = state.tab;
    document.querySelectorAll("#main-tabs .tab").forEach(t => t.classList.toggle("active", t.dataset.tab === active));
    document.querySelectorAll("#main-content > .panel").forEach(p => {
        p.hidden = p.id !== "tab-" + active;
        if (p.id === "tab-" + active) p.classList.add("active");
        else p.classList.remove("active");
    });
}

function updateUrl() {
    const params = new URLSearchParams();
    params.set("tab", state.tab);
    if (state.selectedItem >= 0) params.set("item", state.selectedItem);
    if (state.selectedMob >= 0) params.set("mob", state.selectedMob);
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
    setState({ tab, selectedItem: item, selectedMob: mob, subTab: sub });
    renderTabs();
}

export { renderTabs, updateUrl };
