import { state, setState, selectItem, switchTab } from "../core/state.js";
import { ITEM_FLAG } from "../core/cabin.js";
import { formatSourceTypeName } from "./sources.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

function itemFlags(it) {
    const out = [];
    const f = it.flags;
    if (f & ITEM_FLAG.HAS_CYCLE) out.push(`<span class="flag cycle" title="cycle">⟲</span>`);
    if (f & ITEM_FLAG.IS_INFINITE) out.push(`<span class="flag infinite" title="unobtainable">∞</span>`);
    if (!(f & ITEM_FLAG.HAS_RECIPE)) out.push(`<span class="flag no-recipe" title="no recipe">∅</span>`);
    if (f & ITEM_FLAG.IS_HARDCODED) out.push(`<span class="flag hardcoded" title="hardcoded">H</span>`);
    return out.join("");
}

function formatRawTooltip(val) {
    if (val === undefined || val === null || !isFinite(val)) return "";
    try {
        return new Intl.NumberFormat("de-DE", { maximumFractionDigits: 10 }).format(val);
    } catch (e) {
        return String(val);
    }
}

function formatComplexity(c) {
    if (c < 0) return "—";
    if (!isFinite(c)) return "∞";
    if (c === 0) return "0";
    if (c >= 1e6) return c.toExponential(2);
    return fmt.format(c);
}

function renderHeader(container, item, subTabName) {
    container.innerHTML = `
        <div class="sub-tab-panel-header">
            <button class="btn btn-back" id="back-to-items-btn">← Back to Items</button>
            <div class="header-details">
                <h2>${escapeHtml(item.name)}</h2>
                <span class="mono-code">${escapeHtml(item.id)}</span>
                <span class="category-pill cat-${item.categoryName || "Uncalculable"}">${item.categoryName}</span>
                <span class="sub-tab-label-badge">${subTabName}</span>
            </div>
        </div>
        <div class="sub-tab-content-body"></div>
    `;

    container.querySelector("#back-to-items-btn").addEventListener("click", () => {
        switchTab("items");
    });
}

export async function renderItemRecipesView(container) {
    const db = state.db;
    if (!db) return;
    const itemIndex = state.selectedItem;
    if (itemIndex < 0) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">No item selected. Go to Items tab and choose an item.</div></div>`;
        return;
    }
    const item = db.items.get(itemIndex);
    if (!item) return;

    renderHeader(container, item, "Recipes in Machines");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading recipes…</div>`;
    try {
        const recipes = await db.getItemRecipes(itemIndex);
        if (recipes.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">No crafting recipes found for this item.</div></div>`;
            return;
        }

        const machineSet = new Set();
        for (const r of recipes) {
            if (r.machineItemIndex >= 0) machineSet.add(r.machineItemIndex);
        }

        if (machineSet.size === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">This item is not crafted in any machine (it might be a base source).</div></div>`;
            return;
        }

        const html = [];
        for (const mi of machineSet) {
            const mItem = db.items.get(mi);
            const mRecipes = recipes.filter(r => r.machineItemIndex === mi);
            html.push(`
                <div class="detail-card" style="margin-bottom: 16px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; border-bottom: 1px solid var(--border); padding-bottom: 8px; margin-bottom: 8px;">
                        <span>
                            <strong style="color:var(--accent); cursor:pointer;" class="machine-link" data-index="${mi}">${escapeHtml(mItem ? mItem.name : "Unknown Machine")}</strong>
                            <span class="mono-code" style="font-size:11px;">${escapeHtml(mItem ? mItem.id : "")}</span>
                        </span>
                        <span class="chip">${fmtInt.format(mRecipes.length)} recipe(s)</span>
                    </div>
                    ${mRecipes.map(r => renderRecipeRow(r, db)).join("")}
                </div>
            `);
        }
        body.innerHTML = html.join("");

        body.querySelectorAll(".machine-link").forEach(el => {
            el.addEventListener("click", () => {
                selectItem(parseInt(el.dataset.index, 10));
            });
        });

        wireIngredientLinks(body);
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading recipes: ${escapeHtml(String(e))}</div>`;
    }
}

export async function renderItemMachineRecipesView(container) {
    const db = state.db;
    if (!db) return;
    const itemIndex = state.selectedItem;
    if (itemIndex < 0) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">No item selected.</div></div>`;
        return;
    }
    const item = db.items.get(itemIndex);
    if (!item) return;

    renderHeader(container, item, "Machine Production Output");
    const body = container.querySelector(".sub-tab-content-body");

    const isMachine = db.machines.some(m => m.itemIndex === itemIndex);
    if (!isMachine) {
        body.innerHTML = `<div class="empty-state"><div class="icon">⚙️</div><div class="message">This item (${escapeHtml(item.name)}) is not a machine and cannot craft items.</div></div>`;
        return;
    }

    body.innerHTML = `<div class="hint">Loading machine recipes…</div>`;
    try {
        const recipes = await db.getRecipesByMachine(itemIndex);
        if (recipes.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">No recipes are registered for this machine.</div></div>`;
            return;
        }

        // Group recipes by the items they produce
        const producedItems = new Map();
        for (const r of recipes) {
            const prodIdx = r.resultItemIndex;
            if (!producedItems.has(prodIdx)) producedItems.set(prodIdx, []);
            producedItems.get(prodIdx).push(r);
        }

        const html = [];
        for (const [prodIdx, rList] of producedItems.entries()) {
            const pItem = db.items.get(prodIdx);
            html.push(`
                <div class="detail-card" style="margin-bottom: 12px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; border-bottom: 1px dashed var(--border); padding-bottom: 6px; margin-bottom: 8px;">
                        <span>
                            <strong style="color:var(--accent); cursor:pointer;" class="product-link" data-index="${prodIdx}">${escapeHtml(pItem ? pItem.name : "Unknown Output")}</strong>
                            <span class="mono-code" style="font-size:11px;">${escapeHtml(pItem ? pItem.id : "")}</span>
                        </span>
                    </div>
                    ${rList.map(r => renderRecipeRow(r, db)).join("")}
                </div>
            `);
        }
        body.innerHTML = html.join("");

        body.querySelectorAll(".product-link").forEach(el => {
            el.addEventListener("click", () => {
                selectItem(parseInt(el.dataset.index, 10));
            });
        });

        wireIngredientLinks(body);
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading machine recipes: ${escapeHtml(String(e))}</div>`;
    }
}

export async function renderItemUsesView(container) {
    const db = state.db;
    if (!db) return;
    const itemIndex = state.selectedItem;
    if (itemIndex < 0) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">No item selected.</div></div>`;
        return;
    }
    const item = db.items.get(itemIndex);
    if (!item) return;

    renderHeader(container, item, "Item Uses in Recipes");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading item usage…</div>`;
    try {
        const usage = await db.getItemUsage(itemIndex);
        if (usage.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">🍃</div><div class="message">This item is not used as an ingredient in any crafting recipe.</div></div>`;
            return;
        }

        body.innerHTML = `
            <div style="padding: 12px; background: rgba(255,255,255,0.02); border: 1px solid var(--border); border-radius: var(--radius-md); margin-bottom: 16px;">
                <strong>Usage Overview:</strong> Used as a direct or alternative ingredient in <strong>${fmtInt.format(usage.length)}</strong> recipe(s).
            </div>
            <div style="display:grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 10px;">
                ${usage.map(idx => {
                    const it = db.items.get(idx);
                    if (!it) return "";
                    return `
                        <div class="source-category ingredient-link" data-index="${idx}" style="display:flex; justify-content:space-between; align-items:center;">
                            <div>
                                <div style="font-weight:600; color:var(--text);">${escapeHtml(it.name)}</div>
                                <div class="mono-code" style="font-size:10px; color:var(--text-muted);">${escapeHtml(it.id)}</div>
                            </div>
                            <span class="category-pill cat-${it.categoryName || "Uncalculable"}" style="font-size:9px;">${it.categoryName}</span>
                        </div>
                    `;
                }).join("")}
            </div>
        `;

        body.querySelectorAll(".ingredient-link").forEach(el => {
            el.addEventListener("click", () => {
                selectItem(parseInt(el.dataset.index, 10));
            });
        });
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading usage: ${escapeHtml(String(e))}</div>`;
    }
}

export async function renderItemBaseSourcesView(container) {
    const db = state.db;
    if (!db) return;
    const itemIndex = state.selectedItem;
    if (itemIndex < 0) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">No item selected.</div></div>`;
        return;
    }
    const item = db.items.get(itemIndex);
    if (!item) return;

    renderHeader(container, item, "Base Sources & Loot");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading sources…</div>`;
    try {
        const [base, sources] = await Promise.all([
            db.getItemBaseData(itemIndex),
            db.getItemSources(itemIndex),
        ]);

        if (!base && sources.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">❔</div><div class="message">No base sources found. This item might only be obtainable via recipes.</div></div>`;
            return;
        }

        let html = "";
        if (base) {
            html += `
                <div class="detail-card" style="margin-bottom: 16px; border-left: 3px solid var(--accent);">
                    <div style="font-size: 14px; font-weight:600; margin-bottom: 6px;">Baseline Source</div>
                    <div>Source Type: <strong>${escapeHtml(formatSourceTypeName(base.sourceType))}</strong></div>
                    ${base.details ? `<div style="margin-top: 6px; padding: 6px 10px; background: rgba(0,0,0,0.2); border-radius:4px; font-family:var(--mono);" class="mono-code">${escapeHtml(base.details)}</div>` : ""}
                </div>
            `;
        }

        if (sources.length > 0) {
            html += `<h3 style="font-size:14px; margin-bottom: 10px; color:var(--text-dim);">Additional Extraction / Drop Channels (${sources.length})</h3>`;
            for (const s of sources) {
                html += `
                    <div class="detail-card" style="margin-bottom:12px;">
                        <div style="display:flex; justify-content:space-between; margin-bottom: 6px;">
                            <strong>${escapeHtml(formatSourceTypeName(s.sourceType))}</strong>
                            <span class="mono-code" title="${formatRawTooltip(s.estimatedCost)}">cost complexity: ${isFinite(s.estimatedCost) ? fmt.format(s.estimatedCost) : "∞"}</span>
                        </div>
                        ${s.details ? `<div class="hint" style="margin-bottom: 6px;">${escapeHtml(s.details)}</div>` : ""}
                        ${s.ingredients.length > 0 ? `
                            <div style="margin-top: 6px;">
                                <div class="hint" style="font-size:10px; margin-bottom:3px;">Ingredients needed:</div>
                                <div class="ingredient-list">
                                    ${s.ingredients.map(ing => {
                                        const ingItem = db.items.get(ing.itemIndex);
                                        return `<span class="ingredient ingredient-link" data-index="${ing.itemIndex}">${escapeHtml(ingItem ? ingItem.name : "#" + ing.itemIndex)} × ${fmt.format(ing.amount)}</span>`;
                                    }).join("")}
                                </div>
                            </div>
                        ` : ""}
                    </div>
                `;
            }
        }

        body.innerHTML = html;
        wireIngredientLinks(body);
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading sources: ${escapeHtml(String(e))}</div>`;
    }
}

function renderRecipeRow(r, db) {
    return `
        <div class="recipe-card ${r.category === 0 ? "primary" : ""}" style="margin-top: 6px; padding: 8px 12px;">
            <div style="display:flex; justify-content:space-between;">
                <strong>${escapeHtml(r.recipeType)}</strong>
                <span>yields × ${r.resultCount} ${r.recipeMultiplier !== 1 ? `<span class="chip" style="font-size:10px; padding:1px 4px;">mult ${fmt.format(r.recipeMultiplier)}</span>` : ""}</span>
            </div>
            ${r.ingredients.length > 0 ? `
                <div class="ingredient-list" style="margin-top: 6px;">
                    ${r.ingredients.map(slot => slot.variants.map(v => `
                        <span class="ingredient ingredient-link" data-index="${v}">${escapeHtml(db.items.get(v)?.name || "#" + v)} × ${slot.count}</span>
                    `).join("")).join("")}
                </div>
            ` : `<div class="hint" style="font-size: 11px; margin-top: 4px;">No input ingredients required</div>`}
        </div>
    `;
}

function wireIngredientLinks(container) {
    container.querySelectorAll(".ingredient-link").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            selectItem(parseInt(el.dataset.index, 10));
        });
    });
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
