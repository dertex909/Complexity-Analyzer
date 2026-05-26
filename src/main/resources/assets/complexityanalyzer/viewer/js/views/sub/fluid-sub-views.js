import {
    escapeHtml,
    fmtInt,
    renderSubTabHeader,
    renderMachineRecipes
} from "../../core/utils.js";
import {state, setState} from "../../core/state.js";
import {renderRecipeRow} from "./recipe-shared.js";

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

    renderSubTabHeader(container, fluid, "Recipes in Machines", "Back to Fluids", "fluids");
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
        for (const r of recipes) if (r.machineItemIndex >= 0) machineSet.add(r.machineItemIndex);

        if (machineSet.size === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">This fluid is not crafted in any machine (it might be a protected source).</div></div>`;
            return;
        }

        body.innerHTML = renderMachineRecipes(machineSet, recipes, db, renderRecipeRow);

        body.querySelectorAll(".machine-link").forEach(el => {
            el.addEventListener("click", () => {
                setState({tab: "item-machine-recipes", selectedItem: parseInt(el.dataset.index, 10)});
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

    renderSubTabHeader(container, fluid, "Fluid Uses in Recipes", "Back to Fluids", "fluids");
    const body = container.querySelector(".sub-tab-content-body");

    body.innerHTML = `<div class="hint">Loading usage…</div>`;
    try {
        const usage = await db.getFluidUsage(fluidIndex);
        if (usage.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">🍃</div><div class="message">This fluid is not used as an ingredient in any crafting recipe.</div></div>`;
            return;
        }

        const rawRecipes = [];

        for (const prodIdx of usage) {
            try {
                const itemRecipes = await db.getItemRecipes(prodIdx);
                for (const r of itemRecipes) {
                    let usesFluid = false;
                    if (r.fluidIngredients) for (const slot of r.fluidIngredients) {
                        if (slot.variants && slot.variants.includes(fluidIndex)) {
                            usesFluid = true;
                            break;
                        }
                    }
                    if (usesFluid) rawRecipes.push(r);
                }
            } catch (e) {
                console.error("Error loading fluid usage recipes:", e);
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

        body.querySelectorAll(".item-link").forEach(el => {
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

function wireLinks(container) {
    container.querySelectorAll(".item-link").forEach(el => {
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