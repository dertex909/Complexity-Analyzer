import { CabinDatabase } from "./core/db.js";
import { state, setState, store, selectItem, selectMob, switchTab } from "./core/state.js";
import { initRouter, renderTabs } from "./core/router.js";
import { renderOverview } from "./views/overview.js";
import { renderItems } from "./views/items.js";
import { renderFluids } from "./views/fluids.js";
import { renderMobs } from "./views/mobs.js";
import { renderSources } from "./views/sources-view.js";
import { renderGraph } from "./views/graph.js";

const fmtInt = new Intl.NumberFormat("en-US");

function setStatus(cls, text) {
    const dot = document.getElementById("status-dot");
    if (dot) { dot.className = "dot " + cls; }
    const st = document.getElementById("status-text");
    if (st) { st.textContent = text; }
}

const VIEW_RENDERERS = {
    overview: renderOverview,
    items: renderItems,
    fluids: renderFluids,
    mobs: renderMobs,
    sources: renderSources,
    graph: renderGraph,
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
    if (state.tab === "items") renderItems(document.getElementById("tab-items"));
});

store.addEventListener("selectMob", () => {
    if (state.tab === "mobs") renderMobs(document.getElementById("tab-mobs"));
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
        await db.open({ preferFullDownload: true });
        setState({ db });

        setStatus("ready", `${fmtInt.format(db.meta.itemCount)} items, ${fmtInt.format(db.meta.mobCount)} mobs`);
        const fl = document.getElementById("footer-left");
        if (fl) fl.textContent = `${db.meta.modId} ${db.meta.modVersion} · file 0x${db.file.fileHash.toString(16)}`;

        initRouter();
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
                await state.db.open({ preferFullDownload: true });
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
        if (e.target === overlay) { overlay.hidden = true; }
    });

    input.addEventListener("input", debounce(() => {
        const q = input.value.trim().toLowerCase();
        if (q.length < 2) { results.innerHTML = ""; return; }
        const matched = [];
        const db = state.db;
        for (let i = 0; i < db.items.count && matched.length < 100; i++) {
            const it = db.items.get(i);
            if (it && (it.name + " " + it.id).toLowerCase().includes(q)) {
                matched.push({ kind: "item", name: it.name, id: it.id, index: i });
            }
        }
        for (let i = 0; i < db.mobs.count && matched.length < 150; i++) {
            const m = db.mobs.get(i);
            if (m && (m.name + " " + m.id).toLowerCase().includes(q)) {
                matched.push({ kind: "mob", name: m.name, id: m.id, index: i });
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
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

main().catch(err => console.error("Bootstrap failed:", err));