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

import {state} from "../../../core/state.js";

export function isEntityCalculable(entity) {
    return Boolean(entity && isFinite(entity.complexity) && entity.complexity > 0 && !(entity.flags & 0x10));
}

export function getRecipeIdentityKey(r) {
    if (r._idKey !== undefined) return r._idKey;

    const ingPart = r.ingredients ? r.ingredients.map(ing => {
        const vars = ing.variants ? [...ing.variants].sort().join(",") : "";
        return `${vars}:${ing.count}`;
    }).join(";") : "";

    const fluidIngPart = r.fluidIngredients ? r.fluidIngredients.map(f => {
        const vars = f.variants ? [...f.variants].sort().join(",") : "";
        return `${vars}:${f.amount}`;
    }).join(";") : "";

    const itemOutPart = r.itemOutputs ? [...r.itemOutputs].sort((a, b) => a.itemIndex - b.itemIndex).map(out => `${out.itemIndex}:${out.count}`).join(",") : "";
    const fluidOutPart = r.fluidOutputs ? [...r.fluidOutputs].sort((a, b) => a.fluidIndex - b.fluidIndex).map(out => `${out.fluidIndex}:${out.amount}`).join(",") : "";

    r._idKey = `${r.recipeType || "minecraft:custom"}_${ingPart}_${fluidIngPart}_${itemOutPart}_${fluidOutPart}`;
    return r._idKey;
}

export function mergeDuplicateRecipes(recipes) {
    const unique = [];
    const keyToRecipe = new Map();
    for (let i = 0; i < recipes.length; i++) {
        const r = recipes[i];
        const idKey = getRecipeIdentityKey(r);
        if (!keyToRecipe.has(idKey)) {
            const rCopy = {
                ...r,
                allMachineIndexes: r.allMachineIndexes ? [...r.allMachineIndexes] : []
            };
            if (r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
                if (!rCopy.allMachineIndexes.includes(r.machineItemIndex)) {
                    rCopy.allMachineIndexes.push(r.machineItemIndex);
                }
            }
            keyToRecipe.set(idKey, rCopy);
            unique.push(rCopy);
        } else {
            const existing = keyToRecipe.get(idKey);
            if (r.allMachineIndexes) {
                for (let k = 0; k < r.allMachineIndexes.length; k++) {
                    const mi = r.allMachineIndexes[k];
                    if (mi !== undefined && mi >= 0 && !existing.allMachineIndexes.includes(mi)) {
                        existing.allMachineIndexes.push(mi);
                    }
                }
            }
            if (r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
                if (!existing.allMachineIndexes.includes(r.machineItemIndex)) {
                    existing.allMachineIndexes.push(r.machineItemIndex);
                }
            }
        }
    }
    return unique;
}

export function getMachineAmortizationCost(machineItem, db) {
    if (!machineItem) return 0;
    const isCraftable = !!((machineItem.flags & 0x01) && machineItem.complexity > 0 && !(machineItem.flags & 0x10));
    if (!isCraftable) return 10000000;
    const taxVal = db?.meta?.machineTaxMultiplier !== undefined ? db.meta.machineTaxMultiplier : 0.05;
    return machineItem.complexity * taxVal;
}

export function resolveBestMachine(r, db, body = null) {
    const machineKey = r.recipeType || "minecraft:custom";
    if (body?._customState?.selectedMachines?.has(machineKey)) {
        return body._customState.selectedMachines.get(machineKey);
    }

    let allMs = r.allMachineIndexes || [];
    if (allMs.length === 0 && r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
        allMs = [r.machineItemIndex];
    }

    if (allMs.length > 0) {
        let minMachineCost = Infinity;
        let bestMachineIdx = allMs[0];
        for (let i = 0; i < allMs.length; i++) {
            const mi = allMs[i];
            const mItem = db.items.get(mi);
            const costVal = getMachineAmortizationCost(mItem, db);
            if (costVal < minMachineCost) {
                minMachineCost = costVal;
                bestMachineIdx = mi;
            }
        }
        return bestMachineIdx;
    }
    return r.machineItemIndex !== undefined ? r.machineItemIndex : -1;
}

export function compareByComplexity(a, b) {
    const compA = (a.item.complexity === -1 || (a.item.flags & 0x10)) ? Number.MAX_VALUE : a.item.complexity;
    const compB = (b.item.complexity === -1 || (b.item.flags & 0x10)) ? Number.MAX_VALUE : b.item.complexity;
    return compA - compB;
}

export function resolveActiveVariant(slotVariants, activeVariantIdx, stateObj) {
    if (activeVariantIdx === -1 || !slotVariants.some(v => v.index === activeVariantIdx)) {
        const selectedIdx = slotVariants.findIndex(v => v.index === stateObj.selectedItem);
        activeVariantIdx = selectedIdx !== -1 ? slotVariants[selectedIdx].index : slotVariants[0].index;
    }
    return activeVariantIdx;
}

function calculateSlotCost(slot, dbMap, stateKey, body, qty, isFluid = false) {
    if (!slot.variants || slot.variants.length === 0) return 0;
    const count = isFluid ? (qty / 1000) : qty;

    let activeVariant = -1;
    if (body?._customState?.selectedIngredients?.has(stateKey)) {
        activeVariant = body._customState.selectedIngredients.get(stateKey);
    }

    if (activeVariant !== -1) {
        const entity = dbMap.get(activeVariant);
        if (isEntityCalculable(entity)) return count * entity.complexity;
    }

    let minComp = Infinity;
    for (let i = 0; i < slot.variants.length; i++) {
        const v = slot.variants[i];
        const entity = dbMap.get(v);
        if (isEntityCalculable(entity) && entity.complexity < minComp) minComp = entity.complexity;
    }
    return minComp !== Infinity ? count * minComp : 0;
}

export function getRecipeCost(r, db, body = null) {
    const recipeKey = getRecipeIdentityKey(r);
    const activeMachineIdx = resolveBestMachine(r, db, body);
    const machineItem = db.items.get(activeMachineIdx);
    let cost = getMachineAmortizationCost(machineItem, db);

    if (r.ingredients) {
        for (let slotIdx = 0; slotIdx < r.ingredients.length; slotIdx++) {
            const slot = r.ingredients[slotIdx];
            cost += calculateSlotCost(slot, db.items, `${recipeKey}_ing_${slotIdx}`, body, slot.count, false);
        }
    }

    if (r.fluidIngredients) {
        for (let slotIdx = 0; slotIdx < r.fluidIngredients.length; slotIdx++) {
            const slot = r.fluidIngredients[slotIdx];
            cost += calculateSlotCost(slot, db.fluids, `${recipeKey}_fluid_${slotIdx}`, body, slot.amount, true);
        }
    }

    return cost;
}

export function isRecipeCalculable(r, db) {
    if (r.ingredients) {
        for (let s = 0; s < r.ingredients.length; s++) {
            const slot = r.ingredients[s];
            if (slot.variants && slot.variants.length > 0) {
                let hasCalc = false;
                for (let vIdx = 0; vIdx < slot.variants.length; vIdx++) {
                    const item = db.items.get(slot.variants[vIdx]);
                    if (item && item.complexity !== -1 && !(item.flags & 0x10)) {
                        hasCalc = true;
                        break;
                    }
                }
                if (!hasCalc) return false;
            }
        }
    }
    if (r.fluidIngredients) {
        for (let s = 0; s < r.fluidIngredients.length; s++) {
            const slot = r.fluidIngredients[s];
            if (slot.variants && slot.variants.length > 0) {
                let hasCalc = false;
                for (let vIdx = 0; vIdx < slot.variants.length; vIdx++) {
                    const fl = db.fluids.get(slot.variants[vIdx]);
                    if (fl && fl.complexity !== -1 && !(fl.flags & 0x10)) {
                        hasCalc = true;
                        break;
                    }
                }
                if (!hasCalc) return false;
            }
        }
    }
    return true;
}

export function getRecipeUnitCost(r, db, body = null) {
    const totalCost = getRecipeCost(r, db, body);
    const tab = state.tab;
    const itemIndex = state.selectedItem;

    if (tab === "item-recipes" && itemIndex >= 0) {
        let yieldCount = 1.0;
        if (r.itemOutputs && r.itemOutputs.length > 0) {
            const out = r.itemOutputs.find(o => o && o.itemIndex === itemIndex);
            if (out) yieldCount = out.count;
        } else if (r.outputItemIndex === itemIndex) {
            yieldCount = r.resultCount || 1.0;
        }
        return totalCost / Math.max(1, yieldCount);
    }

    if (tab === "fluid-recipes" && itemIndex >= 0) {
        let yieldAmount = 1000.0;
        if (r.fluidOutputs && r.fluidOutputs.length > 0) {
            const out = r.fluidOutputs.find(o => o && o.fluidIndex === itemIndex);
            if (out) yieldAmount = out.amount;
        }
        return (totalCost / Math.max(1, yieldAmount)) * 1000;
    }

    return totalCost;
}

export function getSelectedOutputComplexity(db) {
    const tab = state.tab;
    const index = state.selectedItem;
    if (index === undefined || index < 0) return null;

    if (tab === "fluid-recipes") {
        const fl = db.fluids.get(index);
        return (fl && isFinite(fl.complexity) && fl.complexity > 0) ? fl.complexity : null;
    } else {
        const item = db.items.get(index);
        return (item && isFinite(item.complexity) && item.complexity > 0) ? item.complexity : null;
    }
}

export function sortRecipes(recipes, db, sortType) {
    if (!recipes || recipes.length <= 1) return recipes ? [...recipes] : [];

    const itemComp = getSelectedOutputComplexity(db);

    const decorated = recipes.map(r => {
        const isCalc = isRecipeCalculable(r, db);
        const bestMachineIdx = resolveBestMachine(r, db);
        const machine = db.items.get(bestMachineIdx);
        const craftable = bestMachineIdx < 0 || (machine ? !!((machine.flags & 0x01) && machine.complexity > 0 && !(machine.flags & 0x10)) : false);
        const cost = getRecipeUnitCost(r, db, null);
        return {
            r,
            isCalc,
            craftable,
            cost,
            diffOptimal: itemComp !== null ? Math.abs(cost - itemComp) : 0,
            priority: r.priority || 0
        };
    });

    decorated.sort((a, b) => {
        if (a.isCalc !== b.isCalc) return a.isCalc ? -1 : 1;
        if (a.craftable !== b.craftable) return a.craftable ? -1 : 1;

        if (sortType === "optimal") {
            if (itemComp !== null) {
                if (Math.abs(a.diffOptimal - b.diffOptimal) > 0.001) return a.diffOptimal - b.diffOptimal;
            }
        }

        if (sortType === "cheapest") {
            if (Math.abs(a.cost - b.cost) > 0.001) return a.cost - b.cost;
            return b.priority - a.priority;
        }

        const diff = a.cost - b.cost;
        const threshold = Math.max(10.0, 0.15 * Math.min(a.cost, b.cost));
        if (Math.abs(diff) > threshold) return diff;

        if (a.priority !== b.priority) return b.priority - a.priority;
        if (Math.abs(diff) > 0.001) return diff;
        return 0;
    });

    return decorated.map(d => d.r);
}

export function getRecipeSearchString(r, db) {
    if (r._searchString !== undefined) return r._searchString;

    const parts = [];
    if (r.recipeType) parts.push(r.recipeType);

    if (r.itemOutputs && r.itemOutputs.length > 0) {
        for (let i = 0; i < r.itemOutputs.length; i++) {
            const o = r.itemOutputs[i];
            if (o.hoverName) parts.push(o.hoverName);
            const it = db.items.get(o.itemIndex);
            if (it) {
                if (it.name) parts.push(it.name);
                if (it.id) parts.push(it.id);
            }
        }
    } else if (r.outputItemIndex >= 0) {
        const it = db.items.get(r.outputItemIndex);
        if (it) {
            if (it.name) parts.push(it.name);
            if (it.id) parts.push(it.id);
        }
    }

    if (r.fluidOutputs) {
        for (let i = 0; i < r.fluidOutputs.length; i++) {
            const o = r.fluidOutputs[i];
            const fl = db.fluids.get(o.fluidIndex);
            if (fl) {
                if (fl.name) parts.push(fl.name);
                if (fl.id) parts.push(fl.id);
            }
        }
    }

    if (r.ingredients) {
        for (let s = 0; s < r.ingredients.length; s++) {
            const slot = r.ingredients[s];
            if (slot.variantNames) {
                for (let v = 0; v < slot.variantNames.length; v++) {
                    const vn = slot.variantNames[v];
                    if (vn) parts.push(vn);
                }
            }
            if (slot.variants) {
                for (let v = 0; v < slot.variants.length; v++) {
                    const it = db.items.get(slot.variants[v]);
                    if (it) {
                        if (it.name) parts.push(it.name);
                        if (it.id) parts.push(it.id);
                    }
                }
            }
        }
    }

    if (r.fluidIngredients) {
        for (let s = 0; s < r.fluidIngredients.length; s++) {
            const slot = r.fluidIngredients[s];
            if (slot.variants) {
                for (let v = 0; v < slot.variants.length; v++) {
                    const fl = db.fluids.get(slot.variants[v]);
                    if (fl) {
                        if (fl.name) parts.push(fl.name);
                        if (fl.id) parts.push(fl.id);
                    }
                }
            }
        }
    }

    const machineIdxs = r.allMachineIndexes && r.allMachineIndexes.length > 0
        ? r.allMachineIndexes
        : (r.machineItemIndex >= 0 ? [r.machineItemIndex] : []);
    for (let m = 0; m < machineIdxs.length; m++) {
        const mi = machineIdxs[m];
        const machineItem = db.items.get(mi);
        if (machineItem) {
            if (machineItem.name) parts.push(machineItem.name);
            if (machineItem.id) parts.push(machineItem.id);
        }
    }

    r._searchString = parts.join(" ").toLowerCase();
    return r._searchString;
}

export function resolveActiveByproducts(r, db, body, recipeKey) {
    const possibleByproducts = new Map();
    if (r.ingredients) {
        r.ingredients.forEach((slot, slotIdx) => {
            if (slot.variantRemainingItemIndexes) {
                slot.variantRemainingItemIndexes.forEach((remIdx, varIdx) => {
                    if (remIdx >= 0) possibleByproducts.set(remIdx, {slotIdx, varIdx});
                });
            }
        });
    }

    const activeByproducts = new Map();
    if (r.ingredients) {
        for (let slotIdx = 0; slotIdx < r.ingredients.length; slotIdx++) {
            const slot = r.ingredients[slotIdx];
            if (!slot.variants || slot.variants.length === 0) continue;

            let activeItemIndex = -1;
            if (slot.variants.length === 1) {
                activeItemIndex = slot.variants[0];
            } else {
                const ingKey = `${recipeKey}_ing_${slotIdx}`;
                let activeVariantIdx = body?._customState?.selectedIngredients?.has(ingKey)
                    ? body._customState.selectedIngredients.get(ingKey) : -1;

                const slotVariants = [...slot.variants].map((v, idx) => ({
                    index: v,
                    item: db.items.get(v),
                    originalIdx: idx
                })).filter(x => x.item).sort(compareByComplexity);

                if (slotVariants.length > 0) {
                    activeItemIndex = resolveActiveVariant(slotVariants, activeVariantIdx, state);
                }
            }

            if (activeItemIndex >= 0 && slot.variantRemainingItemIndexes) {
                const varIdx = slot.variants.indexOf(activeItemIndex);
                if (varIdx >= 0) {
                    const remIdx = slot.variantRemainingItemIndexes[varIdx];
                    if (remIdx >= 0) {
                        activeByproducts.set(remIdx, (activeByproducts.get(remIdx) || 0) + slot.count);
                    }
                }
            }
        }
    }

    return {possibleByproducts, activeByproducts};
}