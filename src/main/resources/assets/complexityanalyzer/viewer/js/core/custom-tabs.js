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

import {selectItem, selectMob, setState, state, store, switchTab} from "./state.js";
import {renderErrorOverlay} from "../components/error-overlay.js";

const GLOBAL_KEY = {
    TABS: "__COMPLEXITY_TABS__",
    TOKEN: "__COMPLEXITY_TOKEN__"
};

const TAB_PROP = {
    ID: "id",
    TITLE: "title",
    SCRIPT_URL: "scriptUrl",
    STYLES: "styles",
    SUBTABS: "subtabs",
    MODULE: "_module",
    MOUNT: "mount",
    UNMOUNT: "unmount",
    ON_OPEN: "onOpen",
    ON_CLOSE: "onClose",
    ON_SUBTAB_CHANGE: "onSubTabChange"
};

const CUSTOM_TABS_MAP = new Map();
const DYNAMIC_SUBTABS_MAP = new Map();
let activeCustomPlugin = null;
let lastNotifiedSubTab = null;

export function initCustomTabs(viewRenderers) {
    const tabs = window[GLOBAL_KEY.TABS];
    if (!Array.isArray(tabs) || tabs.length === 0) return;

    const nav = document.getElementById("main-tabs");
    const main = document.getElementById("main-content");
    if (!nav || !main) return;

    for (let tab of tabs) {
        if (!tab || !tab[TAB_PROP.ID] || !tab[TAB_PROP.SCRIPT_URL]) continue;
        const tabId = tab[TAB_PROP.ID];
        const tabTitle = tab[TAB_PROP.TITLE] || tabId;
        CUSTOM_TABS_MAP.set(tabId, tab);

        const btn = document.createElement("button");
        btn.className = "tab custom-tab";
        btn.dataset.tab = tabId;
        btn.setAttribute("role", "tab");
        btn.textContent = tabTitle;
        nav.appendChild(btn);

        const subContainer = document.createElement("div");
        subContainer.className = "sub-tabs-container custom-sub-tabs";
        subContainer.id = "custom-sub-tabs-" + tabId;
        subContainer.style.display = "none";
        nav.appendChild(subContainer);

        const panel = document.createElement("section");
        panel.className = "panel custom-tab-panel";
        panel.hidden = true;
        panel.id = "tab-" + tabId;
        main.appendChild(panel);

        if (viewRenderers) viewRenderers[tabId] = (container) => renderCustomTab(tab, container);
    }

    store.addEventListener("change", () => {
        if (activeCustomPlugin && state.tab === activeCustomPlugin.tabId && state.subTab !== lastNotifiedSubTab) {
            lastNotifiedSubTab = state.subTab;
            const {plugin, root, context} = activeCustomPlugin;
            if (typeof plugin[TAB_PROP.ON_SUBTAB_CHANGE] === "function") {
                plugin[TAB_PROP.ON_SUBTAB_CHANGE](state.subTab, root, context);
            }
        }
    });
}

export function setCustomSubTabs(tabId, subtabsList) {
    if (!Array.isArray(subtabsList) || subtabsList.length === 0) {
        DYNAMIC_SUBTABS_MAP.delete(tabId);
    } else {
        DYNAMIC_SUBTABS_MAP.set(tabId, subtabsList);
    }
    renderCustomSubTabsUI();
}

export function renderCustomSubTabsUI() {
    for (const [tabId, tab] of CUSTOM_TABS_MAP.entries()) {
        const subContainer = document.getElementById("custom-sub-tabs-" + tabId);
        if (!subContainer) continue;

        const isParentActive = state.tab === tabId;
        const subtabs = DYNAMIC_SUBTABS_MAP.get(tabId) || tab[TAB_PROP.SUBTABS] || [];

        if (isParentActive && subtabs.length > 0) {
            const firstSubId = typeof subtabs[0] === "string" ? subtabs[0] : (subtabs[0]?.id || "");
            const activeSub = state.subTab || firstSubId;
            if (!state.subTab && firstSubId) setTimeout(() => setState({subTab: firstSubId}), 0);

            subContainer.style.display = "flex";
            subContainer.innerHTML = subtabs.map(st => {
                const sId = typeof st === "string" ? st : (st.id || st.title);
                const sTitle = typeof st === "string" ? st : (st.title || st.id);
                const isActive = activeSub === sId;
                return `<button class="sub-tab ${isActive ? "active" : ""}" data-sub="${sId}">↳ ${sTitle}</button>`;
            }).join("");

            subContainer.querySelectorAll(".sub-tab").forEach(btn => {
                btn.addEventListener("click", () => {
                    setState({subTab: btn.dataset.sub});
                });
            });
        } else {
            subContainer.style.display = "none";
            subContainer.innerHTML = "";
        }
    }
}

export async function renderCustomTab(tab, container) {
    const tabId = tab[TAB_PROP.ID];
    const scriptUrl = tab[TAB_PROP.SCRIPT_URL];
    const styles = tab[TAB_PROP.STYLES];

    let shadow = container.shadowRoot;
    if (!shadow) shadow = container.attachShadow({mode: "open"});

    try {
        let root = shadow.querySelector(".custom-tab-content");

        if (!root) {
            shadow.innerHTML = "";

            const baseStyle = document.createElement("style");
            baseStyle.textContent = `
                :host {
                    display: block;
                    width: 100%;
                    height: 100%;
                    box-sizing: border-box;
                    color: var(--text, #e6e6e6);
                    font-family: inherit;
                }
                *, *::before, *::after {
                    box-sizing: inherit;
                }
                .custom-tab-content {
                    width: 100%;
                    height: 100%;
                }
            `;
            shadow.appendChild(baseStyle);

            if (Array.isArray(styles)) for (let styleUrl of styles) {
                const link = document.createElement("link");
                link.rel = "stylesheet";
                link.href = styleUrl;
                shadow.appendChild(link);
            }

            root = document.createElement("div");
            root.className = "custom-tab-content";
            root.getElementById = (id) => root.querySelector("#" + CSS.escape(id));
            shadow.appendChild(root);
        }

        if (!tab[TAB_PROP.MODULE]) tab[TAB_PROP.MODULE] = await import(scriptUrl);
        const mod = tab[TAB_PROP.MODULE];
        const plugin = (mod && mod.default) || mod;

        const context = {
            db: state.db,
            state: state,
            token: window[GLOBAL_KEY.TOKEN] || "",
            store: store,
            get subTab() {
                return state.subTab;
            },
            setSubTabs: (subtabs) => setCustomSubTabs(tabId, subtabs),
            navigate: (id, sub = null) => switchTab(id, sub),
            navigateSub: (subId) => setState({subTab: subId}),
            selectItem: (index) => selectItem(index),
            selectMob: (index) => selectMob(index)
        };

        lastNotifiedSubTab = state.subTab;

        if (typeof plugin[TAB_PROP.MOUNT] === "function") {
            plugin[TAB_PROP.MOUNT](root, context);
            activeCustomPlugin = {plugin, tabId, root, context};
        } else if (typeof plugin[TAB_PROP.ON_OPEN] === "function") {
            plugin[TAB_PROP.ON_OPEN](root, context);
            activeCustomPlugin = {plugin, tabId, root, context};
        }
    } catch (err) {
        tab[TAB_PROP.MODULE] = null;
        console.error(`[WebPacks] Failed to render tab [${tabId}]:`, err);
        renderErrorOverlay(shadow, tabId, scriptUrl, err);
    }
}

export function unmountActivePlugin() {
    if (!activeCustomPlugin) return;
    try {
        const {plugin, root, context} = activeCustomPlugin;
        if (typeof plugin[TAB_PROP.UNMOUNT] === "function") {
            plugin[TAB_PROP.UNMOUNT](root, context);
        } else if (typeof plugin[TAB_PROP.ON_CLOSE] === "function") {
            plugin[TAB_PROP.ON_CLOSE](root, context);
        }
    } catch (e) {
        console.warn("[WebPacks] Error during plugin unmount:", e);
    }
    activeCustomPlugin = null;
    lastNotifiedSubTab = null;
}

export function isCustomTabActive(activeTab) {
    return activeCustomPlugin && activeCustomPlugin.tabId !== activeTab;
}