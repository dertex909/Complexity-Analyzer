import {CabinDatabase} from "./core/db.js";
import {state, setState, store, selectItem, selectMob, switchTab} from "./core/state.js";
import {initRouter, renderTabs} from "./core/router.js";
import {renderOverview} from "./views/overview.js";
import {renderItems} from "./views/items.js";
import {renderFluids} from "./views/fluids.js";
import {renderMobs} from "./views/mobs.js";
import {renderGraph} from "./views/graph.js";
import {renderSources} from "./views/sources.js";
import {renderItemDetail} from "./views/details/item-detail.js";
import {renderFluidDetail} from "./views/details/fluid-detail.js";
import {
    renderItemRecipesView,
    renderItemMachineRecipesView,
    renderItemUsesView,
    renderItemBaseSourcesView
} from "./views/sub/item-sub-views.js";
import {
    renderFluidRecipesView,
    renderFluidUsesView
} from "./views/sub/fluid-sub-views.js";

const fmtInt = new Intl.NumberFormat("en-US");

function setStatus(cls, text) {
    const dot = document.getElementById("status-dot");
    if (dot) {
        dot.className = "dot " + cls;
    }
    const st = document.getElementById("status-text");
    if (st) {
        st.textContent = text;
    }
}

const VIEW_RENDERERS = {
    overview: renderOverview,
    items: renderItems,
    fluids: renderFluids,
    mobs: renderMobs,
    sources: renderSources,
    graph: renderGraph,
    "item-recipes": renderItemRecipesView,
    "item-machine-recipes": renderItemMachineRecipesView,
    "item-uses": renderItemUsesView,
    "item-base-sources": renderItemBaseSourcesView,
    "fluid-recipes": renderFluidRecipesView,
    "fluid-uses": renderFluidUsesView,
};

function renderCurrentTab() {
    const tab = state.tab;
    const container = document.getElementById("tab-" + tab);
    if (!container) return;
    const renderer = VIEW_RENDERERS[tab];
    if (renderer) renderer(container);
}

store.addEventListener("change", () => {
    renderTabs();
    renderCurrentTab();
});

store.addEventListener("selectItem", () => {
    if (state.selectedItem >= 0) {
        if (["fluids", "fluid-recipes", "fluid-uses"].includes(state.tab)) {
            renderFluidDetail(null, state.selectedItem);
        } else {
            renderItemDetail(null, state.selectedItem);
        }
    }
});

store.addEventListener("selectMob", async () => {
    if (state.tab === "mobs") await renderMobs(document.getElementById("tab-mobs"));
});

async function main() {
    setStatus("loading", "opening cabin…");
    const url = new URL(location.href);
    let token = url.searchParams.get("token");
    if (!token) {
        const parts = url.pathname.split("/").filter(Boolean);
        if (parts.length > 0) token = parts[0];
    }
    const cabinUrl = `/${token}/api/cabin?token=${encodeURIComponent(token || "")}`;

    try {
        const db = new CabinDatabase(cabinUrl);
        await db.open({preferFullDownload: true});
        setState({db});

        setStatus("ready", `${fmtInt.format(db.meta.itemCount)} items, ${fmtInt.format(db.meta.mobCount)} mobs`);
        const fl = document.getElementById("footer-left");
        if (fl) fl.textContent = `${db.meta.modId} ${db.meta.modVersion} · file 0x${db.file.fileHash.toString(16)}`;

        initRouter();
        initSidebarResizer();
        initSidebarToggle();
        renderTabs();
        renderCurrentTab();

        setupGlobalSearch();

        startPolling(token);
    } catch (e) {
        console.error(e);
        setStatus("error", "failed to load cabin");
        const mainEl = document.querySelector("main");
        if (mainEl) {
            mainEl.innerHTML = `<section class="panel active" style="justify-content:center;align-items:center">
                <div class="card" style="color:var(--err);max-width:600px">
                    <h3 style="color:var(--err)">Failed to load cabin</h3>
                    <div>${escapeHtml(String(e))}</div>
                    <div class="hint" style="margin-top:10px">Make sure you launched the viewer via <code>/cabin open</code> and have a valid token.</div>
                </div>
            </section>`;
        }
    }
}

function startPolling(token) {
    let countdown = 5;
    let failCount = 0;
    setInterval(async () => {
        countdown--;
        if (countdown > 0) return;
        countdown = 5;
        try {
            const metaUrl = `/${token}/api/meta?token=${encodeURIComponent(token || "")}`;
            const resp = await fetch(metaUrl);
            if (!resp.ok) {
                failCount++;
                if (failCount >= 2) setStatus("error", "Offline");
                return;
            }
            failCount = 0;
            const meta = await resp.json();
            const serverHash = meta.hash.toLowerCase();
            const localHash = state.db?.file?.fileHash?.toString(16)?.toLowerCase();
            if (serverHash && localHash && serverHash !== localHash) {
                await state.db.open({preferFullDownload: true});
                setStatus("ready", `Updated! ${fmtInt.format(state.db.meta.itemCount)} items`);
                renderCurrentTab();
                countdown = 3;
            } else {
                setStatus("ready", "ready");
            }
        } catch (e) {
            failCount++;
            if (failCount >= 2) setStatus("error", "offline");
        }
    }, 1000);
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
        if (e.target === overlay) {
            overlay.hidden = true;
        }
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

    // Load initial width from localStorage
    const savedWidth = localStorage.getItem("sidebarWidth");
    if (savedWidth) {
        document.documentElement.style.setProperty("--sidebar-width", savedWidth + "px");
    }

    resizer.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        resizer.classList.add("dragging");
        resizer.setPointerCapture(e.pointerId);
        document.body.style.cursor = "col-resize";
        document.body.style.userSelect = "none";

        const onPointerMove = (moveEvent) => {
            let newWidth = moveEvent.clientX;
            // Bound the width of the sidebar
            if (newWidth < 185) newWidth = 185;
            if (newWidth > 500) newWidth = 500;
            document.documentElement.style.setProperty("--sidebar-width", newWidth + "px");
            localStorage.setItem("sidebarWidth", newWidth);

            // Dispatch a window resize event to trigger layout/canvas adjustments safely
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

    // Load initial state
    const isCollapsed = localStorage.getItem("sidebarCollapsed") === "true";
    if (isCollapsed) {
        document.body.classList.add("collapsed");
    }

    const setCollapsed = (collapsed) => {
        if (collapsed) {
            document.body.classList.add("collapsed");
            localStorage.setItem("sidebarCollapsed", "true");
        } else {
            document.body.classList.remove("collapsed");
            localStorage.setItem("sidebarCollapsed", "false");
        }

        // Dispatch window resize events to keep charts & UI aligned:
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