import { state, setState, switchTab } from "../../core/state.js";

const fmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
const fmtInt = new Intl.NumberFormat("en-US");

function renderHeader(container, fluid, subTabName) {
    container.innerHTML = `
        <div class="sub-tab-panel-header">
            <button class="btn btn-back" id="back-to-fluids-btn">← Back to Fluids</button>
            <div class="header-details">
                <h2>${escapeHtml(fluid.name)}</h2>
                <span class="mono-code">${escapeHtml(fluid.id)}</span>
                <span class="category-pill cat-${fluid.categoryName || "Uncalculable"}">${fluid.categoryName}</span>
                <span class="sub-tab-label-badge">${subTabName}</span>
            </div>
        </div>
        <div class="sub-tab-content-body"></div>
    `;

    container.querySelector("#back-to-fluids-btn").addEventListener("click", () => {
        switchTab("fluids");
    });
}

export async function renderFluidRecipesView(container) {
    const db = state.db;
    if (!db) return;
    const fluidIndex = state.selectedItem;
    if (fluidIndex < 0) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">No fluid selected. Go to Fluids tab and choose a fluid.</div></div>`;
        return;
    }
    const fluid = db.fluids.get(fluidIndex);
    if (!fluid) return;

    renderHeader(container, fluid, "Recipes in Machines");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading recipes…</div>`;
    try {
        const recipes = await db.getFluidRecipes(fluidIndex);
        console.log("Fluid recipes:", recipes);
        if (recipes.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">No crafting recipes found for this fluid.</div></div>`;
            return;
        }

        const machineSet = new Set();
        for (const r of recipes) {
            if (r.machineItemIndex >= 0) machineSet.add(r.machineItemIndex);
        }

        if (machineSet.size === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">This fluid is not crafted in any machine (it might be a protected source).</div></div>`;
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
                setState({ tab: "item-machine-recipes", selectedItem: parseInt(el.dataset.index, 10) });
            });
        });

        wireLinks(body);
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading recipes: ${escapeHtml(String(e))}</div>`;
    }
}

export async function renderFluidUsesView(container) {
    const db = state.db;
    if (!db) return;
    const fluidIndex = state.selectedItem;
    if (fluidIndex < 0) {
        container.innerHTML = `<div class="empty-state"><div class="icon">🔍</div><div class="message">No fluid selected.</div></div>`;
        return;
    }
    const fluid = db.fluids.get(fluidIndex);
    if (!fluid) return;

    renderHeader(container, fluid, "Fluid Uses in Recipes");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading usage…</div>`;
    try {
        const usage = await db.getFluidUsage(fluidIndex);
        if (usage.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">🍃</div><div class="message">This fluid is not used as an ingredient in any crafting recipe.</div></div>`;
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
                        <div class="source-category item-link" data-index="${idx}" style="display:flex; justify-content:space-between; align-items:center; cursor:pointer;">
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

        body.querySelectorAll(".item-link").forEach(el => {
            el.addEventListener("click", () => {
                setState({ tab: "item-recipes", selectedItem: parseInt(el.dataset.index, 10) });
            });
        });
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading usage: ${escapeHtml(String(e))}</div>`;
    }
}

function renderRecipeRow(r, db) {
    console.log("renderRecipeRow", r);
    let yieldsStr = "fluid output";
    if (r.resultCount > 0 && r.outputItemIndex >= 0) {
        const outItem = db.items.get(r.outputItemIndex);
        if (outItem) {
            yieldsStr = `yields × ${r.resultCount} ${escapeHtml(outItem.name)}`;
        }
    }
    return `
        <div class="recipe-card ${r.category === 0 ? "primary" : ""}" style="margin-top: 6px; padding: 8px 12px;">
            <div style="display:flex; justify-content:space-between;">
                <strong>${escapeHtml(r.recipeType)}</strong>
                <span>${yieldsStr} ${r.recipeMultiplier !== 1 ? `<span class="chip" style="font-size:10px; padding:1px 4px;">mult ${fmt.format(r.recipeMultiplier)}</span>` : ""}</span>
            </div>
            ${r.ingredients.length > 0 || r.fluidIngredients.length > 0 ? `
                <div class="ingredient-list" style="margin-top: 6px;">
                    ${r.ingredients.map(slot => slot.variants.map(v => `
                        <span class="ingredient item-link" data-index="${v}">${escapeHtml(db.items.get(v)?.name || "#" + v)} × ${slot.count}</span>
                    `).join("")).join("")}
                    ${r.fluidIngredients.map(slot => slot.variants.map(v => `
                        <span class="ingredient fluid-link" data-index="${v}" style="border-color:var(--accent-dim); color:var(--accent-dim);">${escapeHtml(db.fluids.get(v)?.name || "#" + v)} × ${slot.amount} mB</span>
                    `).join("")).join("")}
                </div>
            ` : `<div class="hint" style="font-size: 11px; margin-top: 4px;">No input ingredients required</div>`}
        </div>
    `;
}

function wireLinks(container) {
    container.querySelectorAll(".item-link").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            setState({ tab: "item-recipes", selectedItem: parseInt(el.dataset.index, 10) });
        });
    });
    container.querySelectorAll(".fluid-link").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            setState({ tab: "fluid-recipes", selectedItem: parseInt(el.dataset.index, 10) });
        });
    });
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
