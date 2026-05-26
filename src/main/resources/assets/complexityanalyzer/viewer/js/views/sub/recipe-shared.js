import {escapeHtml, fmt, renderMachineRecipes} from "../../core/utils.js";
import {state, setState} from "../../core/state.js";

export function getRecipeCost(r, db) {
    let cost = 0;
    const machineItem = db.items.get(r.machineItemIndex);
    if (machineItem && machineItem.complexity > 0) {
        const taxVal = (db.meta && db.meta.machineTaxMultiplier !== undefined) ? db.meta.machineTaxMultiplier : 0.05;
        cost += machineItem.complexity * taxVal;
    }
    if (r.ingredients) for (const slot of r.ingredients) {
        if (slot.variants && slot.variants.length > 0) {
            const firstVar = db.items.get(slot.variants[0]);
            if (firstVar && isFinite(firstVar.complexity) && firstVar.complexity > 0) {
                cost += slot.count * firstVar.complexity;
            }
        }
    }
    if (r.fluidIngredients) for (const slot of r.fluidIngredients) {
        if (slot.variants && slot.variants.length > 0) {
            const firstVar = db.fluids.get(slot.variants[0]);
            if (firstVar && isFinite(firstVar.complexity) && firstVar.complexity > 0) {
                cost += (slot.amount / 1000) * firstVar.complexity;
            }
        }
    }
    return cost;
}

export function isRecipeCalculable(r, db) {
    if (r.ingredients) for (const slot of r.ingredients) {
        if (slot.variants && slot.variants.length > 0) {
            const firstVar = db.items.get(slot.variants[0]);
            if (firstVar && (firstVar.complexity === -1 || (firstVar.flags & 0x10))) return false;
        }
    }
    if (r.fluidIngredients) for (const slot of r.fluidIngredients) {
        if (slot.variants && slot.variants.length > 0) {
            const firstVar = db.fluids.get(slot.variants[0]);
            if (firstVar && (firstVar.complexity === -1 || (firstVar.flags & 0x10))) return false;
        }
    }
    return true;
}

export function sortRecipes(recipes, db, sortType) {
    const sorted = [...recipes];
    sorted.sort((a, b) => {
        const calcA = isRecipeCalculable(a, db);
        const calcB = isRecipeCalculable(b, db);

        if (calcA !== calcB) return calcA ? -1 : 1;

        const costA = getRecipeCost(a, db);
        const costB = getRecipeCost(b, db);

        if (sortType === "cheapest") {
            if (Math.abs(costA - costB) > 0.001) return costA - costB;
            if (a.category !== b.category) return a.category - b.category;
            return b.priority - a.priority;
        }

        const isPrimaryA = a.category === 0;
        const isPrimaryB = b.category === 0;
        if (isPrimaryA !== isPrimaryB) return isPrimaryA ? -1 : 1;
        if (Math.abs(costA - costB) > 0.001) return costA - costB;
        return b.priority - a.priority;
    });
    return sorted;
}

export function renderRecipeControlsHtml(activeSort, recipeCount) {
    return `
        <div class="controls" style="margin-bottom: 12px; display: flex; gap: 8px; align-items: center; background: var(--bg-panel); padding: 8px 12px; border: 1px solid var(--border); border-radius: 4px;">
            <span style="font-size: 11px; color: var(--text-dim); text-transform: uppercase; font-weight: 500; letter-spacing: 1px;">Sort recipes by:</span>
            <select id="recipes-sort" style="font-size: 12px; padding: 4px 8px; border-radius: 4px; background: var(--bg-raised); color: var(--text); border: 1px solid var(--border);">
                <option value="optimal" ${activeSort === "optimal" ? "selected" : ""}>Optimal (Primary first, sorted by cost)</option>
                <option value="cheapest" ${activeSort === "cheapest" ? "selected" : ""}>Cheapest first (Absolute Cost)</option>
            </select>
            <span class="flex-grow"></span>
            <span class="chip" style="font-size: 11px; padding: 2px 8px;">${recipeCount} recipe(s)</span>
        </div>
    `;
}

export function wireRecipeSortListener(container, callback) {
    const select = container.querySelector("#recipes-sort");
    if (select) select.addEventListener("change", (e) => {
        localStorage.setItem("recipes-sort", e.target.value);
        callback();
    });
}

export function wireRecipeLinks(container) {
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

export function renderAndWireGroupedRecipes(body, sorted, db, onSortChange, emptyMessage) {
    const machineSet = new Set();
    for (const r of sorted) if (r.machineItemIndex >= 0) machineSet.add(r.machineItemIndex);

    if (machineSet.size === 0) {
        body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">${escapeHtml(emptyMessage)}</div></div>`;
        return;
    }

    const activeSort = localStorage.getItem("recipes-sort") || "optimal";

    body.innerHTML = renderRecipeControlsHtml(activeSort, sorted.length) + `
        <div class="recipes-grouped-list">
            ${renderMachineRecipes(machineSet, sorted, db, renderRecipeRow)}
        </div>
    `;

    body.querySelectorAll(".machine-link").forEach(el => {
        el.addEventListener("click", () => {
            setState({tab: "item-machine-recipes", selectedItem: parseInt(el.dataset.index, 10)});
        });
    });

    wireRecipeLinks(body);
    wireRecipeSortListener(body, onSortChange);
}

export function renderAndWireFlatRecipes(body, recipes, db, onSortChange, machineOverride = null) {
    const activeSort = localStorage.getItem("recipes-sort") || "optimal";
    const sorted = sortRecipes(recipes, db, activeSort);

    body.innerHTML = renderRecipeControlsHtml(activeSort, recipes.length) + `
        <div class="flat-recipes-list" style="display: flex; flex-direction: column; gap: 8px;">
            ${sorted.map(r => renderRecipeRow(r, db, machineOverride)).join("")}
        </div>
    `;

    wireRecipeLinks(body);
    wireRecipeSortListener(body, onSortChange);
}

export function renderRecipeRow(r, db, machineOverride = null) {
    const inputsHtml = [];
    if (r.ingredients && r.ingredients.length > 0) for (const slot of r.ingredients) {
        for (const v of slot.variants) {
            const item = db.items.get(v);
            if (item) {
                inputsHtml.push(`<span class="ingredient item-link" data-index="${v}">${escapeHtml(item.name || "#" + v)} × ${slot.count}</span>`);
            }
        }
    }
    if (r.fluidIngredients && r.fluidIngredients.length > 0) for (const slot of r.fluidIngredients) {
        for (const v of slot.variants) {
            const fl = db.fluids.get(v);
            if (fl) {
                inputsHtml.push(`<span class="ingredient fluid-link" data-index="${v}" style="color: #5ec7ff; border-color: rgba(94, 199, 255, 0.4); background: rgba(94, 199, 255, 0.08);">${escapeHtml(fl.name || "#" + v)} × ${slot.amount} mB</span>`);
            }
        }
    }

    const outputsHtml = [];
    if (r.itemOutputs && r.itemOutputs.length > 0) for (const out of r.itemOutputs) {
        const outItem = db.items.get(out.itemIndex);
        if (outItem) {
            const isCurrent = out.itemIndex === state.selectedItem && state.tab.startsWith("item");
            const style = isCurrent ? "font-weight: 600; border-color: var(--accent); color: var(--accent);" : "";
            outputsHtml.push(`<span class="ingredient item-link" data-index="${out.itemIndex}" style="${style}">${escapeHtml(outItem.name)} × ${out.count}</span>`);
        }
    }
    if (r.fluidOutputs && r.fluidOutputs.length > 0) for (const out of r.fluidOutputs) {
        const outFluid = db.fluids.get(out.fluidIndex);
        if (outFluid) {
            const isCurrent = out.fluidIndex === state.selectedItem && state.tab.startsWith("fluid");
            const boldStyle = isCurrent ? "font-weight: 600; border-width: 2px;" : "";
            outputsHtml.push(`<span class="ingredient fluid-link" data-index="${out.fluidIndex}" style="color: #5ec7ff; border-color: rgba(94, 199, 255, 0.4); background: rgba(94, 199, 255, 0.08); ${boldStyle}">${escapeHtml(outFluid.name)} × ${out.amount} mB</span>`);
        }
    }
    if (outputsHtml.length === 0) if (r.outputItemIndex >= 0) {
        const outItem = db.items.get(r.outputItemIndex);
        if (outItem) {
            const isCurrent = r.outputItemIndex === state.selectedItem && state.tab.startsWith("item");
            const style = isCurrent ? "font-weight: 600; border-color: var(--accent); color: var(--accent);" : "";
            outputsHtml.push(`<span class="ingredient item-link" data-index="${r.outputItemIndex}" style="${style}">${escapeHtml(outItem.name)} × ${r.resultCount}</span>`);
        }
    }

    const machineItem = machineOverride || db.items.get(r.machineItemIndex);
    const machineName = machineItem ? machineItem.name : r.recipeType;

    let amortizationHtml = "";
    if (machineItem && machineItem.complexity > 0) {
        const taxVal = (db.meta && db.meta.machineTaxMultiplier !== undefined) ? db.meta.machineTaxMultiplier : 0.05;
        const amortization = machineItem.complexity * taxVal;
        amortizationHtml = `<span style="font-size: 10px; color: var(--text-dim); margin-top: -2px; margin-bottom: 4px;" title="Amortization (machine complexity tax): ${fmt.format(machineItem.complexity)} * ${taxVal * 100}%">amort: +${fmt.format(amortization)}</span>`;
    }

    return `
        <div class="recipe-card ${r.category === 0 ? "primary" : ""}" style="margin-top: 6px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; gap: 20px;">
            <div style="flex: 1; display: flex; flex-direction: column; gap: 4px;">
                <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; letter-spacing: 1px; font-weight: 500;">Ingredients</span>
                <div class="ingredient-list" style="display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px;">
                    ${inputsHtml.length > 0 ? inputsHtml.join("") : `<span class="hint" style="font-size: 11px;">No input ingredients required</span>`}
                </div>
            </div>
            
            <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; flex-shrink: 0; min-width: 120px; max-width: 260px; text-align: center; padding: 0 16px; border-left: 1px dashed rgba(255,255,255,0.08); border-right: 1px dashed rgba(255,255,255,0.08);">
                <strong style="font-size: 11px; font-weight: 600; color: var(--accent); line-height: 1.3; overflow-wrap: break-word; word-break: break-word;" title="${escapeHtml(r.recipeType)}">${escapeHtml(machineName)}</strong>
                <span style="font-size: 20px; line-height: 1; color: var(--accent); margin: 6px 0; font-family: monospace; display: flex; align-items: center; justify-content: center;">
                  ➜
                </span>
                ${amortizationHtml}
                ${r.recipeMultiplier !== 1 ? `<span class="chip" style="font-size:10px; padding:1px 4px; margin-top: 2px;">mult ${fmt.format(r.recipeMultiplier)}</span>` : ""}
            </div>

            <div style="flex: 1; display: flex; flex-direction: column; gap: 4px; align-items: flex-end;">
                <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; letter-spacing: 1px; font-weight: 500; text-align: right;">Produces</span>
                <div class="ingredient-list" style="display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; margin-top: 4px;">
                    ${outputsHtml.join("")}
                </div>
            </div>
        </div>
    `;
}