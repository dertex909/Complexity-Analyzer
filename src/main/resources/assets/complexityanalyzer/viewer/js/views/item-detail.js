import { state, setSubTab } from "../core/state.js";
import { ITEM_FLAG } from "../core/cabin.js";
import { renderCraftTree } from "../tree/canvas-tree.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

const SUB_TABS = [
    { key: "info", label: "Info" },
    { key: "machines", label: "Machine Recipes" },
    { key: "tree", label: "Craft Tree" },
    { key: "usage", label: "Usage" },
    { key: "sources", label: "All Sources" },
];

export function renderItemDetail(container, itemIndex) {
    const db = state.db;
    const item = db.items.get(itemIndex);
    if (!item) return;

    const panel = container.querySelector("#item-detail-panel") || document.createElement("div");
    panel.id = "item-detail-panel";
    panel.className = "card";
    panel.style.marginTop = "12px";
    panel.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
            <h3 style="margin:0">${escapeHtml(item.name)} <code class="mono-code" style="font-size:11px">${escapeHtml(item.id)}</code></h3>
            <button class="btn" id="item-detail-close" style="font-size:11px;padding:4px 8px">Close</button>
        </div>
        <div class="action-bar" id="item-action-bar">
            ${SUB_TABS.map(t => `<button class="action-btn ${state.subTab === t.key ? "active" : ""}" data-sub="${t.key}">${t.label}</button>`).join("")}
        </div>
        <div id="item-detail-body">Loading…</div>
    `;
    if (!container.contains(panel)) container.appendChild(panel);

    panel.querySelector("#item-detail-close").addEventListener("click", () => {
        panel.remove();
        state.selectedItem = -1;
        state.subTab = null;
    });
    panel.querySelectorAll(".action-btn").forEach(btn => {
        btn.addEventListener("click", () => { setSubTab(btn.dataset.sub); renderItemDetail(container, itemIndex); });
    });

    const body = panel.querySelector("#item-detail-body");
    const sub = state.subTab || "info";
    switch (sub) {
        case "info": renderInfo(body, item, db); break;
        case "machines": renderMachines(body, itemIndex, db); break;
        case "tree": renderTreePlaceholder(body); break;
        case "usage": renderUsage(body, itemIndex, db); break;
        case "sources": renderAllSources(body, itemIndex, db); break;
    }
}

function renderInfo(body, item, db) {
    body.innerHTML = `
        <dl class="detail-grid">
            <dt>Complexity</dt><dd class="cat-${item.categoryName || "Uncalculable"}" style="font-weight:600">${formatComplexity(item.complexity)}</dd>
            <dt>Category</dt><dd><span class="category-pill cat-${item.categoryName || "Uncalculable"}">${item.categoryName}</span></dd>
            <dt>Depth</dt><dd>${fmtInt.format(item.depth)}</dd>
            <dt>Total ingredients</dt><dd>${fmtInt.format(item.totalIngredients)}</dd>
            <dt>Used in</dt><dd>${fmtInt.format(item.usageCount)} recipes</dd>
            <dt>Flags</dt><dd class="flags">${itemFlags(item)}</dd>
            ${item.errorMessage ? `<dt>Error</dt><dd style="color:var(--err)">${escapeHtml(item.errorMessage)}</dd>` : ""}
        </dl>
    `;
}

async function renderMachines(body, itemIndex, db) {
    body.innerHTML = `<div class="hint">Loading machines…</div>`;
    try {
        const recipes = await db.getItemRecipes(itemIndex);
        const machineSet = new Set();
        for (const r of recipes) {
            if (r.machineItemIndex >= 0) machineSet.add(r.machineItemIndex);
        }
        if (machineSet.size === 0) {
            body.innerHTML = `<div class="hint">No machines for this item.</div>`;
            return;
        }
        const html = [];
        for (const mi of machineSet) {
            const mItem = db.items.get(mi);
            const mRecipes = recipes.filter(r => r.machineItemIndex === mi);
            html.push(`
                <div class="detail-card">
                    <div><strong>${escapeHtml(mItem ? mItem.name : "Unknown")}</strong> <span class="mono-code">${escapeHtml(mItem ? mItem.id : "")}</span></div>
                    <div class="hint">${fmtInt.format(mRecipes.length)} recipe(s)</div>
                    ${mRecipes.map(r => renderRecipeCard(r, db)).join("")}
                </div>
            `);
        }
        body.innerHTML = html.join("");
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error: ${escapeHtml(String(e))}</div>`;
    }
}

async function renderUsage(body, itemIndex, db) {
    body.innerHTML = `<div class="hint">Loading usage…</div>`;
    try {
        const usage = await db.getItemUsage(itemIndex);
        if (usage.length === 0) {
            body.innerHTML = `<div class="hint">Not used as ingredient.</div>`;
            return;
        }
        body.innerHTML = `
            <div class="hint">Used in ${fmtInt.format(usage.length)} recipe(s)</div>
            <div class="ingredient-list" style="margin-top:8px">
                ${usage.slice(0, 100).map(i => {
                    const it = db.items.get(i);
                    return `<span class="ingredient">${escapeHtml(it ? it.name : "#" + i)}</span>`;
                }).join("")}
                ${usage.length > 100 ? `<span class="hint">…and ${usage.length - 100} more</span>` : ""}
            </div>
        `;
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error: ${escapeHtml(String(e))}</div>`;
    }
}

async function renderAllSources(body, itemIndex, db) {
    body.innerHTML = `<div class="hint">Loading sources…</div>`;
    try {
        const [base, sources] = await Promise.all([
            db.getItemBaseData(itemIndex),
            db.getItemSources(itemIndex),
        ]);
        let html = "";
        if (base) {
            html += `<div class="detail-card"><strong>Base source:</strong> ${escapeHtml(base.sourceType)}<br><span class="hint">${escapeHtml(base.details || "")}</span></div>`;
        }
        for (const s of sources) {
            html += `<div class="detail-card">
                <strong>${escapeHtml(s.sourceType)}</strong> — base: ${fmt.format(s.baseFactor)}, est: ${isFinite(s.estimatedCost) ? fmt.format(s.estimatedCost) : "∞"}
                ${s.details ? `<div class="hint">${escapeHtml(s.details)}</div>` : ""}
                ${s.ingredients.length > 0 ? `<div class="ingredient-list">${s.ingredients.map(ing => `<span class="ingredient">${escapeHtml(db.items.get(ing.itemIndex)?.name || "#" + ing.itemIndex)} × ${fmt.format(ing.amount)}</span>`).join("")}</div>` : ""}
            </div>`;
        }
        body.innerHTML = html || `<div class="hint">No sources found.</div>`;
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error: ${escapeHtml(String(e))}</div>`;
    }
}

async function renderTreePlaceholder(body) {
    body.innerHTML = `<div class="tree-canvas-container" id="tree-canvas-container"></div>`;
    await renderCraftTree(body.querySelector("#tree-canvas-container"), state.db, state.selectedItem);
}

function renderRecipeCard(r, db) {
    return `
        <div class="recipe-card ${r.category === 0 ? "primary" : ""}">
            <div><strong>${escapeHtml(r.recipeType)}</strong> × ${r.resultCount}${r.recipeMultiplier !== 1 ? ` (mult ${fmt.format(r.recipeMultiplier)})` : ""}</div>
            ${r.ingredients.length > 0 ? `<div class="ingredient-list">${r.ingredients.map(slot => slot.variants.map(v => `<span class="ingredient">${escapeHtml(db.items.get(v)?.name || "#" + v)} × ${slot.count}</span>`).join("")).join("")}</div>` : ""}
        </div>
    `;
}

function itemFlags(it) {
    const out = [];
    const f = it.flags;
    if (f & ITEM_FLAG.HAS_CYCLE) out.push(`<span class="flag cycle">⟲</span>`);
    if (f & ITEM_FLAG.IS_INFINITE) out.push(`<span class="flag infinite">∞</span>`);
    if (!(f & ITEM_FLAG.HAS_RECIPE)) out.push(`<span class="flag no-recipe">∅</span>`);
    if (f & ITEM_FLAG.IS_HARDCODED) out.push(`<span class="flag hardcoded">H</span>`);
    return out.join("");
}

function formatComplexity(c) {
    if (c < 0) return "—";
    if (!isFinite(c)) return "∞";
    if (c === 0) return "0";
    if (c >= 1e6) return c.toExponential(2);
    return fmt.format(c);
}

function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
