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
import {selectItem, selectMob, setState, state, store, switchTab} from "./core/state.js";
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
import {renderCraftTreeView} from "./tree/craft-tree";

const fmtInt = new Intl.NumberFormat("en-US");

function setStatus(cls, text) {
    const dot = document.getElementById("status-dot");
    if (dot) dot.className = "dot " + cls;
    const st = document.getElementById("status-text");
    if (st) st.textContent = text;
}

const VIEW_RENDERERS = {
    overview: renderOverview,
    items: renderItems,
    fluids: renderFluids,
    mobs: renderMobs,
    sources: renderSources,
    graph: renderGraph,
    "craft-tree": renderCraftTreeView,
    "item-recipes": renderItemRecipesView,
    "item-machine-recipes": renderItemMachineRecipesView,
    "item-uses": renderItemUsesView,
    "item-base-sources": renderItemBaseSourcesView,
    "fluid-recipes": renderFluidRecipesView,
    "fluid-uses": renderFluidUsesView,
    "mob-drops": renderMobDropsView,
};

const SELECTION_DEPENDENT_TABS = new Set([
    "item-recipes",
    "item-machine-recipes",
    "item-uses",
    "item-base-sources",
    "fluid-recipes",
    "fluid-uses",
    "mob-drops"
]);

let lastRenderedTab = null;
let lastRenderedItem = null;
let lastRenderedMob = null;

function renderCurrentTab(force = false) {
    const tab = state.tab;
    const item = state.selectedItem;
    const mob = state.selectedMob;

    let needsRender = force || (tab !== lastRenderedTab);
    if (!needsRender && SELECTION_DEPENDENT_TABS.has(tab)) if (tab === "mob-drops") {
        if (mob !== lastRenderedMob) needsRender = true;
    } else {
        if (item !== lastRenderedItem) needsRender = true;
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
        if (state.tab === "items" && state.selectedItem >= 0 && state.db) renderItemDetail(null, state.selectedItem);
        else itemModal.hidden = true;
    }

    const fluidModal = document.getElementById("fluid-modal");
    if (fluidModal) {
        if (state.tab === "fluids" && state.selectedItem >= 0 && state.db) renderFluidDetail(null, state.selectedItem);
        else fluidModal.hidden = true;
    }

    const mobModal = document.getElementById("mob-modal");
    if (mobModal) {
        if (state.tab === "mobs" && state.selectedMob >= 0 && state.db) void renderMobDetail(null, state.selectedMob);
        else mobModal.hidden = true;
    }
}

store.addEventListener("selectItem", () => {
    if (state.selectedItem >= 0) if (["fluids", "fluid-recipes", "fluid-uses"].includes(state.tab)) {
        renderFluidDetail(null, state.selectedItem);
    } else {
        renderItemDetail(null, state.selectedItem);
    }
});

store.addEventListener("selectMob", async () => {
    if (state.tab === "mobs") await renderMobs(document.getElementById("tab-mobs"));
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

    initRouter();
    initSidebarResizer();
    initSidebarToggle();
    renderTabs();
    renderCurrentTab();
    setupGlobalSearch();
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
            void applyServerHash(String(ev.data));
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

function setupGlobalSearch() {
    const overlay = document.getElementById("search-overlay");
    const input = document.getElementById("global-search");
    const results = document.getElementById("search-results-overlay");
    if (!overlay || !input || !results) return;

    window.addEventListener("keydown", e => {
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
            e.preventDefault();
            overlay.hidden = false;
            input.focus();
            input.select();
        } else if (e.key === "Escape" && !overlay.hidden) {
            overlay.hidden = true;
            input.blur();
        }
    });

    overlay.addEventListener("click", e => {
        if (e.target === overlay) overlay.hidden = true;
    });

    input.addEventListener("input", debounce(() => {
        const q = input.value.trim().toLowerCase();
        if (q.length < 2) {
            results.innerHTML = "";
            return;
        }
        const matched = [];
        const db = state.db;
        for (let i = 0; i < db.items.count && matched.length < 100; i++) {
            const it = db.items.get(i);
            if (it && (it.name + " " + it.id).toLowerCase().includes(q)) {
                matched.push({kind: "item", name: it.name, id: it.id, index: i});
            }
        }
        for (let i = 0; i < db.fluids.count && matched.length < 150; i++) {
            const fl = db.fluids.get(i);
            if (fl && (fl.name + " " + fl.id).toLowerCase().includes(q)) {
                matched.push({kind: "fluid", name: fl.name, id: fl.id, index: i});
            }
        }
        for (let i = 0; i < db.mobs.count && matched.length < 200; i++) {
            const m = db.mobs.get(i);
            if (m && (m.name + " " + m.id).toLowerCase().includes(q)) {
                matched.push({kind: "mob", name: m.name, id: m.id, index: i});
            }
        }
        results.innerHTML = matched.map(m => `
            <div class="search-result" data-kind="${m.kind}" data-index="${m.index}">
                <span class="kind">${m.kind}</span>
                <span class="name">${escapeHtml(m.name)}</span>
                <span class="id">${escapeHtml(m.id)}</span>
            </div>
        `).join("");
    }, 120));

    results.addEventListener("click", e => {
        const item = e.target.closest(".search-result");
        if (!item) return;
        const index = parseInt(item.dataset.index, 10);
        overlay.hidden = true;
        if (item.dataset.kind === "item") {
            switchTab("items");
            selectItem(index);
        } else if (item.dataset.kind === "fluid") {
            switchTab("fluids");
            setState({selectedItem: index});
            renderFluidDetail(null, index);
        } else {
            switchTab("mobs");
            selectMob(index);
        }
    });
}

function debounce(fn, ms) {
    let t;
    return (...args) => {
        clearTimeout(t);
        t = setTimeout(() => fn(...args), ms);
    };
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[c]);
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