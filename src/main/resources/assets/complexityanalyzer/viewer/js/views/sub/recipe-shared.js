import {escapeHtml, fmt} from "../../core/utils.js";
import {state} from "../../core/state.js";

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
                ${r.recipeMultiplier !== 1 ? `<span class="chip" style="font-size:10px; padding:1px 4px;">mult ${fmt.format(r.recipeMultiplier)}</span>` : ""}
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