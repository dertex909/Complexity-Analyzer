import {
    escapeHtml,
    formatRawTooltip,
    fmt,
    fmtInt,
    renderSubTabHeader,
    renderMachineRecipes
} from "../../core/utils.js";
import {state, setState} from "../../core/state.js";
import {formatSourceTypeName} from "../sources.js";
import {renderRecipeRow} from "./recipe-shared.js";

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

    renderSubTabHeader(container, item, "Recipes in Machines", "Back to Items", "items");
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

        body.innerHTML = renderMachineRecipes(machineSet, recipes, db, renderRecipeRow);

        body.querySelectorAll(".machine-link").forEach(el => {
            el.addEventListener("click", () => {
                setState({tab: "item-machine-recipes", selectedItem: parseInt(el.dataset.index, 10)});
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

    renderSubTabHeader(container, item, "Machine Production Output", "Back to Items", "items");
    const body = container.querySelector(".sub-tab-content-body");

    const isMachine = db.machines.some(m => m.itemIndex === itemIndex);
    if (!isMachine) {
        body.innerHTML = `<div class="empty-state"><div class="icon">⚙️</div><div class="message">This item (${escapeHtml(item.name)}) is not a machine and cannot craft items.</div></div>`;
        return;
    }

    body.innerHTML = `<div class="hint">Loading machine recipes…</div>`;
    try {
        const recipes = (await db.getRecipesByMachine(itemIndex)).filter(r => r.machineItemIndex === itemIndex);
        if (recipes.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">No recipes are registered for this machine.</div></div>`;
            return;
        }

        body.innerHTML = `
            <div style="padding: 10px 14px; background: rgba(255,255,255,0.02); border: 1px solid var(--border); border-radius: 6px; margin-bottom: 16px;">
                <strong>Machine Overview:</strong> This machine has <strong>${fmtInt.format(recipes.length)}</strong> registered recipe(s).
            </div>
            <div class="flat-recipes-list" style="display: flex; flex-direction: column; gap: 8px;">
                ${recipes.map(r => renderRecipeRow(r, db, item)).join("")}
            </div>
        `;

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

    renderSubTabHeader(container, item, "Item Uses in Recipes", "Back to Items", "items");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading item usage…</div>`;
    try {
        const usage = await db.getItemUsage(itemIndex);
        if (usage.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">🍃</div><div class="message">This item is not used as an ingredient in any crafting recipe.</div></div>`;
            return;
        }

        const rawRecipes = [];

        for (const prodIdx of usage) {
            try {
                const itemRecipes = await db.getItemRecipes(prodIdx);
                for (const r of itemRecipes) {
                    let usesItem = false;
                    if (r.ingredients) for (const slot of r.ingredients) {
                        if (slot.variants && slot.variants.includes(itemIndex)) {
                            usesItem = true;
                            break;
                        }
                    }
                    if (usesItem) rawRecipes.push(r);
                }
            } catch (e) {
                console.error("Error loading usage recipes:", e);
            }
        }

        const recipes = db.deduplicateRecipes(rawRecipes);

        body.innerHTML = `
            <div style="padding: 10px 14px; background: rgba(255,255,255,0.02); border: 1px solid var(--border); border-radius: 6px; margin-bottom: 16px;">
                <strong>Usage Overview:</strong> Used as a direct or alternative ingredient in <strong>${fmtInt.format(recipes.length)}</strong> recipe(s).
            </div>
            <div class="flat-recipes-list" style="display: flex; flex-direction: column; gap: 8px;">
                ${recipes.map(r => renderRecipeRow(r, db)).join("")}
            </div>
        `;

        body.querySelectorAll(".ingredient-link, .item-link").forEach(el => {
            el.addEventListener("click", (e) => {
                e.stopPropagation();
                setState({tab: "item-recipes", selectedItem: parseInt(el.dataset.index, 10)});
            });
        });
        body.querySelectorAll(".fluid-link").forEach(el => {
            el.addEventListener("click", (e) => {
                e.stopPropagation();
                setState({tab: "fluid-recipes", selectedItem: parseInt(el.dataset.index, 10)});
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

    renderSubTabHeader(container, item, "Base Sources & Loot", "Back to Items", "items");
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
        if (base) html += `
                <div class="detail-card" style="margin-bottom: 16px; border-left: 3px solid var(--accent);">
                    <div style="font-size: 14px; font-weight:600; margin-bottom: 6px;">Baseline Source</div>
                    <div>Source Type: <strong>${escapeHtml(formatSourceTypeName(base.sourceType))}</strong></div>
                    ${base.details ? `<div style="margin-top: 6px; padding: 6px 10px; background: rgba(0,0,0,0.2); border-radius:4px; font-family:var(--mono), monospace;" class="mono-code">${escapeHtml(base.details)}</div>` : ""}
                </div>
            `;

        if (sources.length > 0) {
            html += `<h3 style="font-size:14px; margin-bottom: 10px; color:var(--text-dim);">Additional Extraction / Drop Channels (${sources.length})</h3>`;
            for (const s of sources) {
                html += `
                    <div class="detail-card" style="margin-bottom:12px;">
                        <div style="display:flex; justify-content:space-between; margin-bottom: 6px;">
                            <strong>${escapeHtml(formatSourceTypeName(s.sourceType))}</strong>
                            <span class="mono-code" title="${formatRawTooltip(s.estimatedCost)}">cost complexity: ${isFinite(s.estimatedCost) ? fmt.format(s.estimatedCost) : "-"}</span>
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

function wireIngredientLinks(container) {
    container.querySelectorAll(".ingredient-link, .item-link").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            setState({tab: "item-recipes", selectedItem: parseInt(el.dataset.index, 10)});
        });
    });
    container.querySelectorAll(".fluid-link").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            setState({tab: "fluid-recipes", selectedItem: parseInt(el.dataset.index, 10)});
        });
    });
}