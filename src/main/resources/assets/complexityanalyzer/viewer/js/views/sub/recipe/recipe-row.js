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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import {escapeHtml, fmt, formatComplexity} from "../../../core/utils.js";
import {state} from "../../../core/state.js";
import {
    compareByComplexity,
    getRecipeIdentityKey,
    resolveActiveByproducts,
    resolveActiveVariant,
    resolveBestMachine
} from "./recipe-calc.js";

export function getComplexityInfo(item) {
    const isUncalc = (item.flags & 0x10) || item.categoryName === "Uncalculable";
    const comp = item.complexity;
    const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;
    const uncalcStyle = isUncalc ? 'color: #f87171; background: rgba(239, 68, 68, 0.08);' : '';
    return {isUncalc, tooltip, uncalcStyle};
}

export function getItemSpecificDetails(dataKey) {
    if (!dataKey) return "";

    const splitBracketAware = (str, char) => {
        const result = [];
        let current = "";
        let depth = 0;
        for (let i = 0; i < str.length; i++) {
            const c = str[i];
            if (c === "{" || c === "[" || c === "(") depth++;
            else if (c === "}" || c === "]" || c === ")") depth--;

            if (c === char && depth === 0) {
                result.push(current);
                current = "";
            } else {
                current += c;
            }
        }
        if (current) result.push(current);
        return result;
    };

    const compMatch = dataKey.match(/components=\{([^}]+)}/);
    if (compMatch) {
        const compStr = compMatch[1].trim();
        if (compStr && compStr !== "{}" && compStr !== "empty") {
            const pairs = splitBracketAware(compStr, ",");
            const details = [];
            for (const pair of pairs) {
                const kv = pair.split("=>");
                if (kv.length === 2) {
                    let key = kv[0].trim();
                    const val = kv[1].trim();
                    if (key.includes(":")) key = key.split(":")[1];
                    details.push(`${key}: ${val}`);
                } else if (pair.trim()) {
                    details.push(pair.trim());
                }
            }
            if (details.length > 0) return ` [${details.join(", ")}]`;
        }
    }
    return "";
}

export function getItemDisplayProperties(item, isCurrent, isFluid = false) {
    const {isUncalc, tooltip} = getComplexityInfo(item);
    let style = "";
    let classes = `ingredient ${isFluid ? 'fluid-link' : 'item-link'}`;

    if (isUncalc) {
        classes += " uncalculable-highlight";
    } else {
        if (isCurrent) {
            style = isFluid ? "font-weight: 600; border-width: 2px;" : "font-weight: 600; border-color: var(--accent); color: var(--accent);";
        }
        if (isFluid) {
            style += " color: #5ec7ff; border-color: rgba(94, 199, 255, 0.4); background: rgba(94, 199, 255, 0.08);";
        }
    }
    return {tooltip, style, classes};
}

function renderVariantGroupHtml({
                                    headLabel, headTooltip, headClass, headDataIndex, themeColor,
                                    isUncalc = false, headerTitle, itemsHtml, extraWrapperStyle = ""
                                }) {
    const hoverColor = isUncalc ? "#fca5a5" : (themeColor === "#5ec7ff" ? "#8dd5ff" : "#fbbf24");
    const bgColor = isUncalc ? "rgba(239, 68, 68, 0.08)" : (themeColor === "#5ec7ff" ? "rgba(94, 199, 255, 0.05)" : "rgba(245, 158, 11, 0.05)");
    const borderRightColor = isUncalc ? "rgba(239, 68, 68, 0.25)" : (themeColor === "#5ec7ff" ? "rgba(94, 199, 255, 0.2)" : "rgba(245, 158, 11, 0.2)");
    const hoverBg = isUncalc ? "rgba(239, 68, 68, 0.15)" : (themeColor === "#5ec7ff" ? "rgba(94, 199, 255, 0.1)" : "rgba(245, 158, 11, 0.1)");
    const glowShadowStyle = isUncalc ? "box-shadow: 0 0 5px rgba(239, 68, 68, 0.45);" : "";

    return `
        <div class="variant-group" style="${extraWrapperStyle}">
            <div style="display: inline-flex; align-items: center; border-radius: 4px; overflow: hidden; border: 1px solid ${themeColor}; background: ${bgColor}; font-family: var(--mono), monospace; font-size: 11px; ${glowShadowStyle}">
                <span class="${headClass}" data-index="${headDataIndex}" title="${headTooltip}" style="padding: 2px 6px 2px 8px; cursor: pointer; color: ${themeColor}; border-right: 1px solid ${borderRightColor}; font-size: 11px; font-weight: 600; line-height: 1.3; white-space: nowrap;" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                    ${headLabel}
                </span>
                <span class="variant-trigger cursor-pointer" style="padding: 2px 6px; cursor: pointer; display: flex; align-items: center; color: ${themeColor};" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                    <span class="arrow" style="color: ${themeColor};">▼</span>
                </span>
            </div>
            <div class="variant-dropdown" style="border-color: ${themeColor}; text-align: left;">
                <div class="variant-dropdown-header" style="${isUncalc ? 'color: #f87171;' : `color: ${themeColor};`}">
                    <span>${headerTitle}</span>
                    <span>(Lowest cost first)</span>
                </div>
                ${itemsHtml}
            </div>
        </div>
    `;
}

function renderItemIngredientsHtml(r, db, body, recipeKey) {
    const html = [];
    if (!r.ingredients || r.ingredients.length === 0) return html;

    for (let slotIdx = 0; slotIdx < r.ingredients.length; slotIdx++) {
        const slot = r.ingredients[slotIdx];
        const slotVariants = [...slot.variants].map((v, idx) => ({
            index: v,
            item: db.items.get(v),
            originalIdx: idx
        })).filter(x => x.item).sort(compareByComplexity);

        if (slotVariants.length === 0) continue;

        if (slotVariants.length === 1) {
            const v = slotVariants[0];
            const {isUncalc, tooltip} = getComplexityInfo(v.item);
            const glowClass = isUncalc ? "uncalculable-highlight" : "";
            const dataKey = (slot.variantDataKeys && slot.variantDataKeys[v.originalIdx]) || "";
            const specificDetail = getItemSpecificDetails(dataKey);
            const displayName = ((slot.variantNames && slot.variantNames[v.originalIdx]) || v.item.name || "#" + v.index) + specificDetail;
            html.push(`<span class="ingredient item-link ${glowClass}" data-index="${v.index}" title="${tooltip}">${escapeHtml(displayName)} × ${slot.count}</span>`);
        } else {
            const ingKey = `${recipeKey}_ing_${slotIdx}`;
            let activeVariantIdx = body?._customState?.selectedIngredients?.has(ingKey)
                ? body._customState.selectedIngredients.get(ingKey) : -1;

            activeVariantIdx = resolveActiveVariant(slotVariants, activeVariantIdx, state);
            const head = slotVariants.find(v => v.index === activeVariantIdx) || slotVariants[0];
            const {isUncalc: isHeadUncalc, tooltip: headTooltip} = getComplexityInfo(head.item);
            const themeColor = isHeadUncalc ? "#ef4444" : "#f59e0b";

            const variantItemsHtml = slotVariants.map(v => {
                const isHead = v.index === head.index;
                const {isUncalc, tooltip, uncalcStyle} = getComplexityInfo(v.item);
                const dataKey = (slot.variantDataKeys && slot.variantDataKeys[v.originalIdx]) || "";
                const specificDetail = getItemSpecificDetails(dataKey);
                const displayName = ((slot.variantNames && slot.variantNames[v.originalIdx]) || v.item.name || "#" + v.index) + specificDetail;
                return `
                    <div class="variant-item variant-item-substitute" data-recipe-key="${recipeKey}" data-slot-index="${slotIdx}" data-variant-index="${v.index}" title="${tooltip}" style="${uncalcStyle}">
                        <span class="name" style="${isHead ? (isHeadUncalc ? 'font-weight: 600; color: #fca5a5;' : 'font-weight: 600; color: #fbbf24;') : (isUncalc ? 'color: #f87171;' : '')}">${escapeHtml(displayName)}</span>
                        <span class="count" style="color: ${isUncalc ? '#f87171' : ''};">× ${slot.count}</span>
                    </div>
                `;
            }).join("");

            const headDataKey = (slot.variantDataKeys && slot.variantDataKeys[head.originalIdx]) || "";
            const headSpecificDetail = getItemSpecificDetails(headDataKey);
            const headDisplayName = ((slot.variantNames && slot.variantNames[head.originalIdx]) || head.item.name || "#" + head.index) + headSpecificDetail;

            html.push(renderVariantGroupHtml({
                headLabel: `${escapeHtml(headDisplayName)} × ${slot.count}`,
                headTooltip,
                headClass: "item-link",
                headDataIndex: head.index,
                themeColor,
                isUncalc: isHeadUncalc,
                headerTitle: "ALTERNATIVE VARIANTS",
                itemsHtml: variantItemsHtml
            }));
        }
    }
    return html;
}

function renderFluidIngredientsHtml(r, db, body, recipeKey) {
    const html = [];
    if (!r.fluidIngredients || r.fluidIngredients.length === 0) return html;

    for (let slotIdx = 0; slotIdx < r.fluidIngredients.length; slotIdx++) {
        const slot = r.fluidIngredients[slotIdx];
        const slotVariants = [...slot.variants].map(v => ({index: v, item: db.fluids.get(v)}))
            .filter(x => x.item)
            .sort(compareByComplexity);

        if (slotVariants.length === 0) continue;

        if (slotVariants.length === 1) {
            const v = slotVariants[0];
            const {isUncalc, tooltip} = getComplexityInfo(v.item);
            if (isUncalc) {
                html.push(`<span class="ingredient fluid-link uncalculable-highlight" data-index="${v.index}" title="${tooltip}">${escapeHtml(v.item.name || "#" + v.index)} × ${slot.amount} mB</span>`);
            } else {
                html.push(`<span class="ingredient fluid-link" data-index="${v.index}" title="${tooltip}" style="color: #5ec7ff; border-color: rgba(94, 199, 255, 0.4); background: rgba(94, 199, 255, 0.08);">${escapeHtml(v.item.name || "#" + v.index)} × ${slot.amount} mB</span>`);
            }
        } else {
            const fluidKey = `${recipeKey}_fluid_${slotIdx}`;
            let activeVariantIdx = body?._customState?.selectedIngredients?.has(fluidKey)
                ? body._customState.selectedIngredients.get(fluidKey) : -1;

            activeVariantIdx = resolveActiveVariant(slotVariants, activeVariantIdx, state);
            const head = slotVariants.find(v => v.index === activeVariantIdx) || slotVariants[0];
            const {isUncalc: isHeadUncalc, tooltip: headTooltip} = getComplexityInfo(head.item);
            const themeColor = isHeadUncalc ? "#ef4444" : "#5ec7ff";

            const variantItemsHtml = slotVariants.map(v => {
                const isHead = v.index === head.index;
                const {isUncalc, tooltip, uncalcStyle} = getComplexityInfo(v.item);
                return `
                    <div class="variant-item variant-fluid-substitute" data-recipe-key="${recipeKey}" data-slot-index="${slotIdx}" data-variant-index="${v.index}" title="${tooltip}" style="border-left: 2px solid ${isUncalc ? '#ef4444' : 'rgba(94, 199, 255, 0.4)'}; ${uncalcStyle}">
                        <span class="name" style="${isHead ? (isHeadUncalc ? 'font-weight: 600; color: #fca5a5;' : 'font-weight: 600; color: #5ec7ff;') : (isUncalc ? 'color: #f87171;' : '')}">${escapeHtml(v.item.name || "#" + v.index)}</span>
                        <span class="count" style="color: ${isUncalc ? '#f87171' : '#5ec7ff'};">× ${slot.amount} mB</span>
                    </div>
                `;
            }).join("");

            html.push(renderVariantGroupHtml({
                headLabel: `${escapeHtml(head.item.name || "#" + head.index)} × ${slot.amount} mB`,
                headTooltip,
                headClass: "fluid-link",
                headDataIndex: head.index,
                themeColor,
                isUncalc: isHeadUncalc,
                headerTitle: "ALTERNATIVE FLUIDS",
                itemsHtml: variantItemsHtml
            }));
        }
    }
    return html;
}

function renderOutputsHtml(r, db, recipeKey, activeByproducts, possibleByproducts) {
    const outputsHtml = [];

    if (r.itemOutputs && r.itemOutputs.length > 0) {
        for (const out of r.itemOutputs) {
            const outItem = db.items.get(out.itemIndex);
            if (!outItem) continue;

            const isPossibleByproduct = possibleByproducts.has(out.itemIndex);
            if (isPossibleByproduct && !activeByproducts.has(out.itemIndex)) continue;

            const isCurrent = out.itemIndex === state.selectedItem && state.tab.startsWith("item");
            const props = getItemDisplayProperties(outItem, isCurrent, false);
            const specificDetail = getItemSpecificDetails(out.dataKey);
            const displayName = (out.hoverName || outItem.name) + specificDetail;

            let extraStyle = "";
            let extraClass = "";
            if (isPossibleByproduct) {
                extraStyle = "border-color: #10b981 !important; color: #10b981 !important; background: rgba(16, 185, 129, 0.08) !important;";
                extraClass = " byproduct-highlight";
            }

            outputsHtml.push(`<span class="${props.classes}${extraClass}" data-index="${out.itemIndex}" title="${props.tooltip}" style="${props.style} ${extraStyle}">${escapeHtml(displayName)} × ${out.count}</span>`);
        }
    }

    if (r.fluidOutputs && r.fluidOutputs.length > 0) {
        for (const out of r.fluidOutputs) {
            const outFluid = db.fluids.get(out.fluidIndex);
            if (outFluid) {
                const isCurrent = out.fluidIndex === state.selectedItem && state.tab.startsWith("fluid");
                const props = getItemDisplayProperties(outFluid, isCurrent, true);
                outputsHtml.push(`<span class="${props.classes}" data-index="${out.fluidIndex}" title="${props.tooltip}" style="${props.style}">${escapeHtml(outFluid.name)} × ${out.amount} mB</span>`);
            }
        }
    }

    if (outputsHtml.length === 0 && r.outputItemIndex >= 0) {
        const outItem = db.items.get(r.outputItemIndex);
        if (outItem) {
            const isCurrent = r.outputItemIndex === state.selectedItem && state.tab.startsWith("item");
            const props = getItemDisplayProperties(outItem, isCurrent, false);
            outputsHtml.push(`<span class="${props.classes}" data-index="${r.outputItemIndex}" title="${props.tooltip}" style="${props.style}">${escapeHtml(outItem.name)} × ${r.resultCount}</span>`);
        }
    }

    return outputsHtml;
}

function renderMachineColumnHtml(r, db, body, machineOverride, recipeKey) {
    let allMachinesIdxs = r.allMachineIndexes || [];
    if (allMachinesIdxs.length === 0 && r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
        allMachinesIdxs = [r.machineItemIndex];
    }

    const machineKey = r.recipeType || "minecraft:custom";
    const activeMachineIdx = machineOverride ? machineOverride.index : resolveBestMachine(r, db, body);
    const machineItem = db.items.get(activeMachineIdx);
    const machineName = machineItem ? machineItem.name : r.recipeType;

    let amortizationHtml = "";
    const taxVal = db.meta?.machineTaxMultiplier !== undefined ? db.meta.machineTaxMultiplier : 0.05;
    const fallbackVal = db.meta?.machineFallbackComplexity !== undefined ? db.meta.machineFallbackComplexity : 100.0;

    if (machineItem && machineItem.complexity > 0) {
        const amortization = machineItem.complexity * taxVal;
        amortizationHtml = `<span style="font-size: 10px; color: var(--text-dim); margin-top: -2px; margin-bottom: 4px;" title="Amortization (machine complexity tax): ${fmt.format(machineItem.complexity)} * ${taxVal * 100}%">amort: +${fmt.format(amortization)}</span>`;
    } else if (!machineItem && r.recipeType && !r.recipeType.includes("crafting_table") && r.recipeType !== "minecraft:crafting") {
        const amortization = fallbackVal * taxVal;
        amortizationHtml = `<span style="font-size: 10px; color: var(--warn); margin-top: -2px; margin-bottom: 4px;" title="Fallback Amortization (unknown machine config: ${fallbackVal} * ${taxVal * 100}%): +${fmt.format(amortization)}">amort (fallback): +${fmt.format(amortization)}</span>`;
    }

    let machineBadgeHtml;
    if (allMachinesIdxs.length > 1) {
        const sortedMachines = [...allMachinesIdxs].map(mi => ({
            index: mi,
            item: db.items.get(mi)
        })).filter(x => x.item).sort(compareByComplexity);

        const machineItemsHtml = sortedMachines.map(mOpt => {
            const isHead = mOpt.index === activeMachineIdx;
            const {isUncalc, tooltip} = getComplexityInfo(mOpt.item);
            return `
                <div class="variant-item variant-machine-substitute" data-recipe-key="${recipeKey}" data-machine-key="${machineKey}" data-machine-index="${mOpt.index}" title="${tooltip}">
                    <span class="name" style="${isHead ? (isUncalc ? 'font-weight: 600; color: #fca5a5;' : 'font-weight: 600; color: #fbbf24;') : (isUncalc ? 'color: #f87171;' : '')}">${escapeHtml(mOpt.item.name)}</span>
                </div>
            `;
        }).join("");

        const {
            isUncalc: isActiveMUncalc,
            tooltip: activeMTooltip
        } = machineItem ? getComplexityInfo(machineItem) : {isUncalc: false, tooltip: ""};
        const themeColor = isActiveMUncalc ? "#ef4444" : "#f59e0b";

        machineBadgeHtml = renderVariantGroupHtml({
            headLabel: escapeHtml(machineName),
            headTooltip: activeMTooltip,
            headClass: "machine-link",
            headDataIndex: activeMachineIdx,
            themeColor,
            isUncalc: isActiveMUncalc,
            headerTitle: "COMPATIBLE MACHINES",
            itemsHtml: machineItemsHtml,
            extraWrapperStyle: "margin-bottom: 4px; white-space: nowrap;"
        });
    } else {
        const {
            isUncalc: mIsUncalc,
            tooltip: mTooltip
        } = machineItem ? getComplexityInfo(machineItem) : {isUncalc: false, tooltip: ""};
        machineBadgeHtml = machineItem
            ? `<strong style="cursor:pointer; color: ${mIsUncalc ? '#fca5a5' : 'var(--accent)'}; font-size: 11px; font-weight: 600; line-height: 1.3; white-space: nowrap;" class="machine-link" data-index="${activeMachineIdx}" title="${mTooltip}">${escapeHtml(machineName)}</strong>`
            : `<strong style="font-size: 11px; font-weight: 600; color: var(--accent); line-height: 1.3; white-space: nowrap;">${escapeHtml(machineName)}</strong>`;
    }

    return `
        <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; flex-shrink: 0; min-width: 120px; max-width: 450px; text-align: center; padding: 0 16px; border-left: 1px dashed rgba(255,255,255,0.08); border-right: 1px dashed rgba(255,255,255,0.08);">
            ${machineBadgeHtml}
            <span style="font-size: 20px; line-height: 1; color: var(--accent); margin: 6px 0; font-family: monospace; display: flex; align-items: center; justify-content: center;">
              ➜
            </span>
            ${amortizationHtml}
            ${r.recipeMultiplier !== 1 ? `<span class="chip" style="font-size:10px; padding:1px 4px; margin-top: 2px;">mult ${fmt.format(r.recipeMultiplier)}</span>` : ""}
        </div>
    `;
}

export function renderRecipeRow(r, db, body = null, machineOverride = null) {
    const recipeKey = getRecipeIdentityKey(r);
    const {possibleByproducts, activeByproducts} = resolveActiveByproducts(r, db, body, recipeKey);

    const inputs = renderItemIngredientsHtml(r, db, body, recipeKey);
    const fluidInputs = renderFluidIngredientsHtml(r, db, body, recipeKey);
    const allInputs = [...inputs, ...fluidInputs];

    const outputs = renderOutputsHtml(r, db, recipeKey, activeByproducts, possibleByproducts);
    const machineColumn = renderMachineColumnHtml(r, db, body, machineOverride, recipeKey);

    return `
        <div class="recipe-card primary" style="padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; gap: 20px;">
            <div style="flex: 1; display: flex; flex-direction: column; gap: 4px;">
                <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; letter-spacing: 1px; font-weight: 500;">Ingredients</span>
                <div class="ingredient-list" style="display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px;">
                    ${allInputs.length > 0 ? allInputs.join("") : `<span class="hint" style="font-size: 11px;">No input ingredients required</span>`}
                </div>
            </div>
            
            ${machineColumn}

            <div style="flex: 1; display: flex; flex-direction: column; gap: 4px; align-items: flex-end;">
                <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; letter-spacing: 1px; font-weight: 500; text-align: right;">Produces</span>
                <div class="ingredient-list" style="display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; margin-top: 4px;">
                    ${outputs.join("")}
                </div>
            </div>
        </div>
    `;
}