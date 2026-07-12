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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import {escapeHtml, renderSubTabHeader} from "../../core/utils.js";
import {state} from "../../core/state.js";
import {renderAndWireFlatRecipes, renderAndWireGroupedRecipes} from "./recipe-shared.js";

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
        if (recipes.length === 0) {
            body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">No crafting recipes found for this fluid.</div></div>`;
            return;
        }

        const runRender = () => {
            renderAndWireGroupedRecipes(
                body,
                recipes,
                db,
                runRender,
                "This fluid is not crafted in any machine (it might be a protected source)."
            );
        };
        runRender();
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
        const runRender = () => {
            renderAndWireFlatRecipes(body, recipes, db, runRender);
        };
        runRender();
    } catch (e) {
        body.innerHTML = `<div style="color:var(--err)">Error loading usage: ${escapeHtml(String(e))}</div>`;
    }
}