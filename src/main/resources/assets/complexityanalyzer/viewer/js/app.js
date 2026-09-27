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

import {CabinDatabase} from "./core/db.js";
import {selectItem, selectMob, setState, state, store} from "./core/state.js";
import {initRouter, renderTabs} from "./core/router.js";
import {renderOverview} from "./views/overview.js";
import {renderItems} from "./views/items.js";
import {renderFluids} from "./views/fluids.js";
import {renderMobs} from "./views/mobs.js";
import {renderGraph} from "./views/graph.js";
import {renderSources} from "./views/sources.js";
import {renderItemDetail} from "./views/details/item-detail.js";
import {renderFluidDetail} from "./views/details/fluid-detail.js";
import {renderMobDetail} from "./views/details/mob-detail.js";
import {renderMobDropsView} from "./views/sub/mob-sub-views.js";
import {
    renderItemBaseSourcesView,
    renderItemMachineRecipesView,
    renderItemRecipesView,
    renderItemUsesView
} from "./views/sub/item-sub-views.js";
import {renderFluidRecipesView, renderFluidUsesView} from "./views/sub/fluid-sub-views.js";
import {renderCraftTreeView} from "./tree/craft-tree.js";

const GLOBAL_KEY = {
    TABS: "__COMPLEXITY_TABS__",
    TOKEN: "__COMPLEXITY_TOKEN__"
};

const TAB_PROP = {
    ID: "id",
    TITLE: "title",
    SCRIPT_URL: "scriptUrl",
    STYLES: "styles",
    MODULE: "_module",
    NAVIGATE: "navigate",
    ON_OPEN: "onOpen",
    ON_CLOSE: "onClose"
};

const WS_EVENT = {
    RELOAD: "reload"
};

const TAB = {
    OVERVIEW: "overview",
    ITEMS: "items",
    FLUIDS: "fluids",
    MOBS: "mobs",
    SOURCES: "sources",
    GRAPH: "graph",
    CRAFT_TREE: "craft-tree",
    ITEM_RECIPES: "item-recipes",
    ITEM_MACHINE_RECIPES: "item-machine-recipes",
    ITEM_USES: "item-uses",
    ITEM_BASE_SOURCES: "item-base-sources",
    FLUID_RECIPES: "fluid-recipes",
    FLUID_USES: "fluid-uses",
    MOB_DROPS: "mob-drops"
};

const fmtInt = new Intl.NumberFormat("en-US");

function setStatus(cls, text) {
    const dot = document.getElementById("status-dot");
    if (dot) dot.className = "dot " + cls;
    const st = document.getElementById("status-text");
    if (st) st.textContent = text;
}

const VIEW_RENDERERS = {
    [TAB.OVERVIEW]: renderOverview,
    [TAB.ITEMS]: renderItems,
    [TAB.FLUIDS]: renderFluids,
    [TAB.MOBS]: renderMobs,
    [TAB.SOURCES]: renderSources,
    [TAB.GRAPH]: renderGraph,
    [TAB.CRAFT_TREE]: renderCraftTreeView,
    [TAB.ITEM_RECIPES]: renderItemRecipesView,
    [TAB.ITEM_MACHINE_RECIPES]: renderItemMachineRecipesView,
    [TAB.ITEM_USES]: renderItemUsesView,
    [TAB.ITEM_BASE_SOURCES]: renderItemBaseSourcesView,
    [TAB.FLUID_RECIPES]: renderFluidRecipesView,
    [TAB.FLUID_USES]: renderFluidUsesView,
    [TAB.MOB_DROPS]: renderMobDropsView,
};

const SELECTION_DEPENDENT_TABS = new Set([
    TAB.ITEM_RECIPES,
    TAB.ITEM_MACHINE_RECIPES,
    TAB.ITEM_USES,
    TAB.ITEM_BASE_SOURCES,
    TAB.FLUID_RECIPES,
    TAB.FLUID_USES,
    TAB.MOB_DROPS
]);

let lastRenderedTab = null;
let lastRenderedItem = null;
let lastRenderedMob = null;
let activeCustomPlugin = null;

const CUSTOM_TABS_MAP = new Map();

function initCustomTabs() {
    const tabs = window[GLOBAL_KEY.TABS];
    if (!Array.isArray(tabs) || tabs.length === 0) return;

    const nav = document.getElementById("main-tabs");
    const main = document.getElementById("main-content");
    if (!nav || !main) return;

    for (const tab of tabs) {
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

        const panel = document.createElement("section");
        panel.className = "panel custom-tab-panel";
        panel.hidden = true;
        panel.id = "tab-" + tabId;
        main.appendChild(panel);

        VIEW_RENDERERS[tabId] = (container) => renderCustomTab(tab, container);
    }
}

async function renderCustomTab(tab, container) {
    const tabId = tab[TAB_PROP.ID];
    const scriptUrl = tab[TAB_PROP.SCRIPT_URL];
    const styles = tab[TAB_PROP.STYLES];

    try {
        let shadow = container.shadowRoot;
        if (!shadow) {
            shadow = container.attachShadow({mode: "open"});

            if (Array.isArray(styles)) {
                for (const styleUrl of styles) {
                    const link = document.createElement("link");
                    link.rel = "stylesheet";
                    link.href = styleUrl;
                    shadow.appendChild(link);
                }
            }
        }

        if (!tab[TAB_PROP.MODULE]) {
            tab[TAB_PROP.MODULE] = await import(scriptUrl);
        }

        const mod = tab[TAB_PROP.MODULE];
        const plugin = (mod && mod.default) || mod;

        const context = {
            db: state.db,
            state: state,
            token: window[GLOBAL_KEY.TOKEN] || "",
            [TAB_PROP.NAVIGATE]: (id) => setState({tab: id})
        };

        const onOpenFn = plugin && plugin[TAB_PROP.ON_OPEN];
        if (typeof onOpenFn === "function") {
            onOpenFn.call(plugin, shadow, context);
            activeCustomPlugin = {plugin, tabId: tabId};
        }
    } catch (err) {
        console.error(`Failed to load tab [${tabId}]:`, err);
        container.innerHTML = `<div style="padding: 24px; color: var(--err, #ef4444)">Failed to load tab: ${err.message}</div>`;
    }
}

function renderCurrentTab(force = false) {
    const tab = state.tab;
    const item = state.selectedItem;
    const mob = state.selectedMob;

    if (activeCustomPlugin && activeCustomPlugin.tabId !== tab) {
        try {
            const onCloseFn = activeCustomPlugin.plugin && activeCustomPlugin.plugin[TAB_PROP.ON_CLOSE];
            if (typeof onCloseFn === "function") onCloseFn.call(activeCustomPlugin.plugin);
        } catch (e) {
            console.warn("Error during plugin onClose:", e);
        }
        activeCustomPlugin = null;
    }

    let needsRender = force || (tab !== lastRenderedTab);

    if (!needsRender && SELECTION_DEPENDENT_TABS.has(tab)) {
        needsRender = tab === TAB.MOB_DROPS ? mob !== lastRenderedMob : item !== lastRenderedItem;
    }

    if (!needsRender) return;

    const container = document.getElementById("tab-" + tab);
    if (!container) return;
    const renderer = VIEW_RENDERERS[tab];
    if (renderer) renderer(container);

    lastRenderedTab = tab;
    lastRenderedItem = item;
    lastRenderedMob = mob;
}

store.addEventListener("change", () => {
    renderTabs();
    renderCurrentTab();
    syncDetailModals();
});

function syncDetailModals() {
    const itemModal = document.getElementById("item-modal");
    if (itemModal) {
        if (state.tab === TAB.ITEMS && state.selectedItem >= 0 && state.db) renderItemDetail(null, state.selectedItem);
        else itemModal.hidden = true;
    }

    const fluidModal = document.getElementById("fluid-modal");
    if (fluidModal) {
        if (state.tab === TAB.FLUIDS && state.selectedItem >= 0 && state.db) renderFluidDetail(null, state.selectedItem);
        else fluidModal.hidden = true;
    }

    const mobModal = document.getElementById("mob-modal");
    if (mobModal) {
        if (state.tab === TAB.MOBS && state.selectedMob >= 0 && state.db) void renderMobDetail(null, state.selectedMob);
        else mobModal.hidden = true;
    }
}

store.addEventListener("selectItem", () => {
    if (state.selectedItem >= 0 && [TAB.FLUIDS, TAB.FLUID_RECIPES, TAB.FLUID_USES].includes(state.tab)) {
        renderFluidDetail(null, state.selectedItem);
    } else {
        renderItemDetail(null, state.selectedItem);
    }
});

store.addEventListener("selectMob", async () => {
    if (state.tab === TAB.MOBS) await renderMobs(document.getElementById("tab-mobs"));
});

async function main() {
    setStatus("loading", "connecting…");
    showLoadingOverlay("Connecting…", "Contacting the server…", true);

    const url = new URL(location.href);
    let token = url.searchParams.get("token");
    if (!token) {
        const parts = url.pathname.split("/").filter(Boolean);
        if (parts.length > 0) token = parts[0];
    }

    const db = await openWhenReady(token);

    setState({db});
    hideLoadingOverlay();

    setStatus("ready", `${fmtInt.format(db.meta.itemCount)} items, ${fmtInt.format(db.meta.mobCount)} mobs`);
    const fl = document.getElementById("footer-left");
    if (fl) fl.textContent = `${db.meta.modId} ${db.meta.modVersion} · file 0x${db.file.fileHash.toString(16)}`;

    initCustomTabs();
    initRouter();
    initSidebarResizer();
    initSidebarToggle();
    renderTabs();
    renderCurrentTab();
    startLiveUpdates(token);
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function openWhenReady(token) {
    const cabinUrl = `/${token}/api/cabin?token=${encodeURIComponent(token || "")}`;
    const metaUrl = `/${token}/api/meta?token=${encodeURIComponent(token || "")}`;
    let badTokenStreak = 0;

    for (; ;) {
        let resp = null;
        let networkError = false;
        try {
            resp = await fetch(metaUrl, {cache: "no-store"});
        } catch (e) {
            networkError = true;
        }

        if (networkError) {
            setStatus("error", "reconnecting…");
            showLoadingOverlay(
                "Waiting for the server…",
                "Can’t reach the server right now. This page is retrying automatically — just keep it open and it will connect when the server is back.",
                false
            );
            await sleep(4000);
            continue;
        }

        if (!resp || !resp.ok) {
            badTokenStreak++;
            setStatus("error", "no data");
            showLoadingOverlay(
                "Connecting…",
                badTokenStreak >= 3
                    ? "The server responded but this link may be outdated. Try running /complexity web url again to get a fresh link."
                    : "Reaching the server… retrying automatically.",
                false
            );
            await sleep(4000);
            continue;
        }

        badTokenStreak = 0;
        let meta = null;
        try {
            meta = await resp.json();
        } catch (e) {
        }

        if (meta && meta.hasCabin) {
            try {
                setStatus("loading", "opening cabin…");
                showLoadingOverlay("Loading data…", "Almost ready — opening the analysis file.", true);
                const db = new CabinDatabase(cabinUrl);
                await db.open({preferFullDownload: true});
                return db;
            } catch (e) {
                console.warn("cabin open failed, will retry:", e);
                showLoadingOverlay(
                    "Finishing up…",
                    "The data file is being written. This page will open it automatically in a moment.",
                    true
                );
                await sleep(2000);
                continue;
            }
        }

        setStatus("loading", "generating…");
        showLoadingOverlay(
            "Generating analysis data…",
            "The server is still building the complexity database. On large modpacks this can take a little while. " +
            "This page will load automatically as soon as it’s ready — no need to refresh.",
            true
        );
        await sleep(2500);
    }
}

function ensureOverlayStyle() {
    if (document.getElementById("ca-loading-style")) return;
    const s = document.createElement("style");
    s.id = "ca-loading-style";
    s.textContent = `
      #ca-loading-overlay{position:fixed;inset:0;z-index:9999;display:flex;align-items:center;justify-content:center;
        background:color-mix(in srgb, var(--bg, #0e0f13) 92%, transparent);backdrop-filter:blur(3px);}
      #ca-loading-overlay .ca-box{max-width:560px;text-align:center;padding:30px 34px;border-radius:14px;
        background:var(--panel, #181a20);border:1px solid var(--border, #2a2d36);box-shadow:0 10px 50px rgba(0,0,0,.45);}
      #ca-loading-overlay h3{margin:18px 0 8px;font-size:19px;color:var(--text,#e6e6e6);}
      #ca-loading-overlay .ca-sub{color:var(--text-dim,#9aa0ac);font-size:13px;line-height:1.55;}
      #ca-loading-overlay .ca-spinner{width:48px;height:48px;margin:0 auto;border-radius:50%;
        border:4px solid var(--border,#2a2d36);border-top-color:var(--accent,#4ea1ff);animation:ca-spin .9s linear infinite;}
      #ca-loading-overlay .ca-spinner.stopped{animation:none;border-top-color:var(--err,#ef4444);opacity:.75;}
      @keyframes ca-spin{to{transform:rotate(360deg)}}
    `;
    document.head.appendChild(s);
}

let overlayTitle = null;

function showLoadingOverlay(title, sub, spinning) {
    ensureOverlayStyle();
    let ov = document.getElementById("ca-loading-overlay");
    if (!ov) {
        ov = document.createElement("div");
        ov.id = "ca-loading-overlay";
        ov.innerHTML = `<div class="ca-box"><div class="ca-spinner"></div><h3></h3><div class="ca-sub"></div></div>`;
        document.body.appendChild(ov);
        overlayTitle = null;
    }
    const spin = ov.querySelector(".ca-spinner");
    if (spin) spin.classList.toggle("stopped", !spinning);
    if (overlayTitle !== title) {
        ov.querySelector("h3").textContent = title;
        overlayTitle = title;
    }
    ov.querySelector(".ca-sub").textContent = sub;
}

function hideLoadingOverlay() {
    const ov = document.getElementById("ca-loading-overlay");
    if (ov) ov.remove();
    overlayTitle = null;
}

function startLiveUpdates(token) {
    async function applyServerHash(serverHashHex) {
        const serverHash = (serverHashHex || "").trim().toLowerCase();
        const localHash = state.db?.file?.fileHash?.toString(16)?.toLowerCase();
        if (serverHash && localHash && serverHash !== localHash) {
            await state.db.open({preferFullDownload: true});
            setStatus("ready", `Updated! ${fmtInt.format(state.db.meta.itemCount)} items`);
            renderCurrentTab(true);
        }
    }

    function connect() {
        let ws;
        try {
            const proto = location.protocol === "https:" ? "wss" : "ws";
            ws = new WebSocket(`${proto}://${location.host}/${token}/ws`);
        } catch (e) {
            setStatus("error", "offline");
            setTimeout(connect, 5000);
            return;
        }

        ws.onopen = () => setStatus("ready", "ready");
        ws.onmessage = (ev) => {
            const msg = String(ev.data).trim();
            if (msg === WS_EVENT.RELOAD) {
                console.log("⚡ [Dev] Reloading page from WebSocket event...");
                location.reload();
                return;
            }
            void applyServerHash(msg);
        };
        ws.onclose = () => {
            setStatus("error", "offline");
            setTimeout(connect, 5000);
        };
        ws.onerror = () => {
            try {
                ws.close();
            } catch (e) {
            }
        };
    }

    connect();
}

function initSidebarResizer() {
    const resizer = document.getElementById("sidebar-resizer");
    const sidebar = document.querySelector(".sidebar-left");
    if (!resizer || !sidebar) return;

    const savedWidth = localStorage.getItem("sidebarWidth");
    if (savedWidth) document.documentElement.style.setProperty("--sidebar-width", savedWidth + "px");

    resizer.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        resizer.classList.add("dragging");
        resizer.setPointerCapture(e.pointerId);
        document.body.style.cursor = "col-resize";
        document.body.style.userSelect = "none";

        const onPointerMove = (moveEvent) => {
            let newWidth = moveEvent.clientX;
            if (newWidth < 185) newWidth = 185;
            if (newWidth > 500) newWidth = 500;
            document.documentElement.style.setProperty("--sidebar-width", newWidth + "px");
            localStorage.setItem("sidebarWidth", newWidth);
            window.dispatchEvent(new Event('resize'));
        };

        const onPointerUp = (upEvent) => {
            resizer.classList.remove("dragging");
            try {
                resizer.releasePointerCapture(upEvent.pointerId);
            } catch (err) {
            }
            document.body.style.cursor = "";
            document.body.style.userSelect = "";
            resizer.removeEventListener("pointermove", onPointerMove);
            resizer.removeEventListener("pointerup", onPointerUp);
        };

        resizer.addEventListener("pointermove", onPointerMove);
        resizer.addEventListener("pointerup", onPointerUp);
    });
}

function initSidebarToggle() {
    const hideBtn = document.getElementById("sidebar-hide-btn");
    const showBtn = document.getElementById("sidebar-show-btn");
    if (!hideBtn || !showBtn) return;

    const isCollapsed = localStorage.getItem("sidebarCollapsed") === "true";
    if (isCollapsed) document.body.classList.add("collapsed");

    const setCollapsed = (collapsed) => {
        if (collapsed) {
            document.body.classList.add("collapsed");
            localStorage.setItem("sidebarCollapsed", "true");
        } else {
            document.body.classList.remove("collapsed");
            localStorage.setItem("sidebarCollapsed", "false");
        }

        window.dispatchEvent(new Event('resize'));
        setTimeout(() => {
            window.dispatchEvent(new Event('resize'));
        }, 150);
        setTimeout(() => {
            window.dispatchEvent(new Event('resize'));
        }, 360);
    };

    hideBtn.addEventListener("click", () => {
        setCollapsed(true);
    });

    showBtn.addEventListener("click", () => {
        setCollapsed(false);
    });
}

main().catch(err => console.error("Bootstrap failed:", err));