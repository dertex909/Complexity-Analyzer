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

import {escapeHtml, fmt, formatComplexity} from "../../core/utils.js";
import {state, setState} from "../../core/state.js";

function saveScrollPositions(elem) {
    const scrollPositions = [];
    let parent = elem;
    while (parent) {
        if (parent.scrollTop !== undefined) {
            scrollPositions.push({element: parent, top: parent.scrollTop, left: parent.scrollLeft});
        }
        parent = parent.parentElement;
    }
    const winTop = window.scrollY || document.documentElement.scrollTop;
    const winLeft = window.scrollX || document.documentElement.scrollLeft;
    return {
        winTop,
        winLeft,
        scrollPositions
    };
}

function restoreScrollPositions(saved) {
    if (!saved) return;
    for (const p of saved.scrollPositions) {
        p.element.scrollTop = p.top;
        p.element.scrollLeft = p.left;
    }
    window.scrollTo(saved.winLeft, saved.winTop);
}

export function getRecipeIdentityKey(r) {
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

    return `${r.recipeType || "minecraft:custom"}_${ingPart}_${fluidIngPart}_${itemOutPart}_${fluidOutPart}`;
}

export function mergeDuplicateRecipes(recipes) {
    const unique = [];
    const keyToRecipe = new Map();
    for (const r of recipes) {
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
            if (r.allMachineIndexes) for (const mi of r.allMachineIndexes) {
                if (mi !== undefined && mi >= 0 && !existing.allMachineIndexes.includes(mi)) {
                    existing.allMachineIndexes.push(mi);
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

export function getRecipeCost(r, db, body = null) {
    let cost = 0;
    const recipeKey = getRecipeIdentityKey(r);
    const activeMachineIdx = resolveBestMachine(r, db, body);

    const machineItem = db.items.get(activeMachineIdx);
    if (machineItem) {
        const isCraftable = !!((machineItem.flags & 0x01) && machineItem.complexity > 0 && !(machineItem.flags & 0x10));
        if (isCraftable) {
            const taxVal = (db.meta && db.meta.machineTaxMultiplier !== undefined) ? db.meta.machineTaxMultiplier : 0.05;
            cost += machineItem.complexity * taxVal;
        } else {
            cost += 10000000;
        }
    }

    if (r.ingredients) {
        for (let slotIdx = 0; slotIdx < r.ingredients.length; slotIdx++) {
            const slot = r.ingredients[slotIdx];
            if (slot.variants && slot.variants.length > 0) {
                let activeVariant = -1;
                const ingKey = `${recipeKey}_ing_${slotIdx}`;
                if (body && body._customState && body._customState.selectedIngredients.has(ingKey)) {
                    activeVariant = body._customState.selectedIngredients.get(ingKey);
                }

                if (activeVariant !== -1) {
                    const item = db.items.get(activeVariant);
                    if (item && isFinite(item.complexity) && item.complexity > 0 && !(item.flags & 0x10)) {
                        cost += slot.count * item.complexity;
                    }
                } else {
                    let minComp = Infinity;
                    for (const v of slot.variants) {
                        const item = db.items.get(v);
                        if (item && isFinite(item.complexity) && item.complexity > 0 && !(item.flags & 0x10)) {
                            if (item.complexity < minComp) minComp = item.complexity;
                        }
                    }
                    if (minComp !== Infinity) cost += slot.count * minComp;
                }
            }
        }
    }

    if (r.fluidIngredients) {
        for (let slotIdx = 0; slotIdx < r.fluidIngredients.length; slotIdx++) {
            const slot = r.fluidIngredients[slotIdx];
            if (slot.variants && slot.variants.length > 0) {
                let activeVariant = -1;
                const fluidKey = `${recipeKey}_fluid_${slotIdx}`;
                if (body && body._customState && body._customState.selectedIngredients.has(fluidKey)) {
                    activeVariant = body._customState.selectedIngredients.get(fluidKey);
                }

                if (activeVariant !== -1) {
                    const fl = db.fluids.get(activeVariant);
                    if (fl && isFinite(fl.complexity) && fl.complexity > 0 && !(fl.flags & 0x10)) {
                        cost += (slot.amount / 1000) * fl.complexity;
                    }
                } else {
                    let minComp = Infinity;
                    for (const v of slot.variants) {
                        const fl = db.fluids.get(v);
                        if (fl && isFinite(fl.complexity) && fl.complexity > 0 && !(fl.flags & 0x10)) {
                            if (fl.complexity < minComp) minComp = fl.complexity;
                        }
                    }
                    if (minComp !== Infinity) cost += (slot.amount / 1000) * minComp;
                }
            }
        }
    }

    return cost;
}

export function isRecipeCalculable(r, db) {
    if (r.ingredients) for (const slot of r.ingredients) if (slot.variants && slot.variants.length > 0) {
        let hasCalculable = false;
        for (const v of slot.variants) {
            const item = db.items.get(v);
            if (item && item.complexity !== -1 && !(item.flags & 0x10)) {
                hasCalculable = true;
                break;
            }
        }
        if (!hasCalculable) return false;
    }
    if (r.fluidIngredients) for (const slot of r.fluidIngredients) if (slot.variants && slot.variants.length > 0) {
        let hasCalculable = false;
        for (const v of slot.variants) {
            const fl = db.fluids.get(v);
            if (fl && fl.complexity !== -1 && !(fl.flags & 0x10)) {
                hasCalculable = true;
                break;
            }
        }
        if (!hasCalculable) return false;
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

export function sortRecipes(recipes, db, sortType) {
    const sorted = [...recipes];
    sorted.sort((a, b) => {
        const calcA = isRecipeCalculable(a, db);
        const calcB = isRecipeCalculable(b, db);

        if (calcA !== calcB) return calcA ? -1 : 1;

        let allMsA = a.allMachineIndexes || [];
        if (allMsA.length === 0 && a.machineItemIndex !== undefined && a.machineItemIndex >= 0) allMsA = [a.machineItemIndex];
        let bestMachineIdxA = a.machineItemIndex;
        if (allMsA.length > 0) {
            let minVal = Infinity;
            for (const mi of allMsA) {
                const m = db.items.get(mi);
                if (m) {
                    const isC = !!((m.flags & 0x01) && m.complexity > 0 && !(m.flags & 0x10));
                    const cv = isC ? m.complexity : 10000000;
                    if (cv < minVal) {
                        minVal = cv;
                        bestMachineIdxA = mi;
                    }
                }
            }
        }

        let allMsB = b.allMachineIndexes || [];
        if (allMsB.length === 0 && b.machineItemIndex !== undefined && b.machineItemIndex >= 0) allMsB = [b.machineItemIndex];
        let bestMachineIdxB = b.machineItemIndex;
        if (allMsB.length > 0) {
            let minVal = Infinity;
            for (const mi of allMsB) {
                const m = db.items.get(mi);
                if (m) {
                    const isC = !!((m.flags & 0x01) && m.complexity > 0 && !(m.flags & 0x10));
                    const cv = isC ? m.complexity : 10000000;
                    if (cv < minVal) {
                        minVal = cv;
                        bestMachineIdxB = mi;
                    }
                }
            }
        }

        const machineA = db.items.get(bestMachineIdxA);
        const machineB = db.items.get(bestMachineIdxB);

        const craftableA = bestMachineIdxA < 0 || (machineA ? !!((machineA.flags & 0x01) && machineA.complexity > 0 && !(machineA.flags & 0x10)) : false);
        const craftableB = bestMachineIdxB < 0 || (machineB ? !!((machineB.flags & 0x01) && machineB.complexity > 0 && !(machineB.flags & 0x10)) : false);

        if (craftableA !== craftableB) return craftableA ? -1 : 1;

        const costA = getRecipeUnitCost(a, db, null);
        const costB = getRecipeUnitCost(b, db, null);

        if (sortType === "cheapest") {
            if (Math.abs(costA - costB) > 0.001) return costA - costB;
            if (a.category !== b.category) return a.category - b.category;
            return b.priority - a.priority;
        }

        const diff = costA - costB;
        const threshold = Math.max(10.0, 0.15 * Math.min(costA, costB));
        if (Math.abs(diff) > threshold) return diff;

        const isPrimaryA = a.category === 0;
        const isPrimaryB = b.category === 0;
        if (isPrimaryA !== isPrimaryB) return isPrimaryA ? -1 : 1;
        if (Math.abs(diff) > 0.001) return diff;
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
    const scrollState = saveScrollPositions(body);

    if (!body._customState) {
        body._customState = {
            selectedMachines: new Map(),
            selectedIngredients: new Map()
        };
    }

    const merged = mergeDuplicateRecipes(sorted);
    const activeSort = localStorage.getItem("recipes-sort") || "optimal";
    const resorted = sortRecipes(merged, db, activeSort);

    const machineToRecipes = new Map();
    for (const r of resorted) {
        let allMachinesIdxs = r.allMachineIndexes || [];
        if (allMachinesIdxs.length === 0 && r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
            allMachinesIdxs = [r.machineItemIndex];
        }

        const activeMachineIdx = resolveBestMachine(r, db, body);

        if (!machineToRecipes.has(activeMachineIdx)) {
            machineToRecipes.set(activeMachineIdx, []);
        }
        machineToRecipes.get(activeMachineIdx).push(r);
    }

    if (machineToRecipes.size === 0) {
        body.innerHTML = `<div class="empty-state"><div class="icon">∅</div><div class="message">${escapeHtml(emptyMessage)}</div></div>`;
        return;
    }

    const sortedMachines = Array.from(machineToRecipes.keys()).sort((a, b) => {
        const itemA = a >= 0 ? db.items.get(a) : null;
        const itemB = b >= 0 ? db.items.get(b) : null;
        if (!itemA && itemB) return 1;
        if (itemA && !itemB) return -1;
        return 0;
    });

    const groupsHtml = sortedMachines.map(mi => {
        const mItem = mi >= 0 ? db.items.get(mi) : null;
        const mRecipes = machineToRecipes.get(mi);
        const isRaw = !mItem;

        let nameHtml = "";
        if (mItem) {
            const isMUncalc = (mItem.flags & 0x10) || mItem.categoryName === "Uncalculable";
            const mComp = mItem.complexity;
            const mTooltip = `Complexity: ${isMUncalc || mComp === -1 || !isFinite(mComp) ? 'Uncalculable' : formatComplexity(mComp)}`;
            const mStyle = isMUncalc ? "color: #fca5a5 !important;" : "color:var(--accent);";
            nameHtml = `<strong style="${mStyle} cursor:pointer;" class="machine-link" data-index="${mi}" title="${mTooltip}">${escapeHtml(mItem.name)}</strong>`;
        } else {
            nameHtml = `<strong style="color:#ef4444; margin-right: 8px;">Unknown Machine (${escapeHtml(mRecipes[0].recipeType || "Code")})</strong>`;
        }

        const idHtml = mItem
            ? `<span class="mono-code" style="font-size:11px;">${escapeHtml(mItem.id)}</span>` : ``;

        return `
            <div class="detail-card ${isRaw ? 'raw-craft-card' : ''}" style="margin-bottom: 16px;">
                <div style="display:flex; justify-content:space-between; align-items:center; border-bottom: 1px solid ${isRaw ? 'rgba(239, 68, 68, 0.2)' : 'var(--border)'}; padding-bottom: 8px; margin-bottom: 8px;">
                    <span style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
                        ${nameHtml}
                        ${idHtml}
                    </span>
                    <span class="chip" style="${isRaw ? 'background: rgba(239, 68, 68, 0.15); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.2);' : ''}">${mRecipes.length} recipe(s)</span>
                </div>
                ${mRecipes.map(r => renderRecipeRow(r, db, body)).join("")}
            </div>
        `;
    }).join("");

    body.innerHTML = renderRecipeControlsHtml(activeSort, merged.length) + `
        <div class="recipes-grouped-list">
            ${groupsHtml}
        </div>
    `;

    body.querySelectorAll(".machine-link").forEach(el => {
        el.addEventListener("click", () => {
            setState({tab: "item-machine-recipes", selectedItem: parseInt(el.dataset.index, 10)});
        });
    });

    wireRecipeLinks(body);
    wireRecipeSortListener(body, onSortChange);
    wireRecipeDropdowns(body, onSortChange);

    restoreScrollPositions(scrollState);
    requestAnimationFrame(() => {
        restoreScrollPositions(scrollState);
    });
}

export function renderAndWireFlatRecipes(body, recipes, db, onSortChange, machineOverride = null) {
    const scrollState = saveScrollPositions(body);
    const activeSort = localStorage.getItem("recipes-sort") || "optimal";

    if (!body._customState) {
        body._customState = {
            selectedMachines: new Map(),
            selectedIngredients: new Map()
        };
    }

    const merged = mergeDuplicateRecipes(recipes);
    const sorted = sortRecipes(merged, db, activeSort);

    body.innerHTML = renderRecipeControlsHtml(activeSort, merged.length) + `
        <div class="flat-recipes-list" style="display: flex; flex-direction: column; gap: 8px;">
            ${sorted.map(r => renderRecipeRow(r, db, body, machineOverride)).join("")}
        </div>
    `;

    wireRecipeLinks(body);
    wireRecipeSortListener(body, onSortChange);
    wireRecipeDropdowns(body, onSortChange);

    restoreScrollPositions(scrollState);
    requestAnimationFrame(() => {
        restoreScrollPositions(scrollState);
    });
}

export function wireRecipeDropdowns(container, onReRender) {
    container.querySelectorAll(".variant-group").forEach(grp => {
        const trigger = grp.querySelector(".variant-trigger");
        if (!trigger) return;

        trigger.addEventListener("click", (e) => {
            e.stopPropagation();

            const isActive = grp.classList.contains("active");

            container.querySelectorAll(".variant-group").forEach(g => {
                g.classList.remove("active");
            });

            if (!isActive) {
                grp.classList.add("active");

                const onDocClick = () => {
                    grp.classList.remove("active");
                    document.removeEventListener("click", onDocClick);
                };

                setTimeout(() => {
                    document.addEventListener("click", onDocClick);
                }, 0);
            }
        });

        const dropdown = grp.querySelector(".variant-dropdown");
        if (dropdown) dropdown.addEventListener("click", (e) => {
            e.stopPropagation();
        });
    });

    container.querySelectorAll(".variant-machine-substitute").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            const recipeKey = el.dataset.recipeKey;
            const machineKey = el.dataset.machineKey || recipeKey;
            const machineIndex = parseInt(el.dataset.machineIndex, 10);
            if (container && container._customState) {
                container._customState.selectedMachines.set(machineKey, machineIndex);
            }
            onReRender();
        });
    });

    container.querySelectorAll(".variant-item-substitute").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            const recipeKey = el.dataset.recipeKey;
            const slotIndex = parseInt(el.dataset.slotIndex, 10);
            const variantIndex = parseInt(el.dataset.variantIndex, 10);
            if (container && container._customState) {
                container._customState.selectedIngredients.set(`${recipeKey}_ing_${slotIndex}`, variantIndex);
            }
            onReRender();
        });
    });

    container.querySelectorAll(".variant-fluid-substitute").forEach(el => {
        el.addEventListener("click", (e) => {
            e.stopPropagation();
            const recipeKey = el.dataset.recipeKey;
            const slotIndex = parseInt(el.dataset.slotIndex, 10);
            const variantIndex = parseInt(el.dataset.variantIndex, 10);
            if (container && container._customState) {
                container._customState.selectedIngredients.set(`${recipeKey}_fluid_${slotIndex}`, variantIndex);
            }
            onReRender();
        });
    });
}

function compareByComplexity(a, b) {
    const compA = (a.item.complexity === -1 || (a.item.flags & 0x10)) ? Number.MAX_VALUE : a.item.complexity;
    const compB = (b.item.complexity === -1 || (b.item.flags & 0x10)) ? Number.MAX_VALUE : b.item.complexity;
    return compA - compB;
}

function resolveActiveVariant(slotVariants, activeVariantIdx, state) {
    if (activeVariantIdx === -1 || !slotVariants.some(v => v.index === activeVariantIdx)) {
        const selectedIdx = slotVariants.findIndex(v => v.index === state.selectedItem);
        activeVariantIdx = selectedIdx !== -1 ? slotVariants[selectedIdx].index : slotVariants[0].index;
    }
    return activeVariantIdx;
}

export function resolveBestMachine(r, db, body) {
    const machineKey = r.recipeType || "minecraft:custom";
    if (body && body._customState && body._customState.selectedMachines.has(machineKey)) {
        return body._customState.selectedMachines.get(machineKey);
    }

    let allMs = r.allMachineIndexes || [];
    if (allMs.length === 0 && r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
        allMs = [r.machineItemIndex];
    }

    if (allMs.length > 0) {
        let minMachineCost = Infinity;
        let bestMachineIdx = allMs[0];
        const taxVal = (db.meta && db.meta.machineTaxMultiplier !== undefined) ? db.meta.machineTaxMultiplier : 0.05;
        for (const mi of allMs) {
            const mItem = db.items.get(mi);
            if (mItem) {
                const isCraftable = !!((mItem.flags & 0x01) && mItem.complexity > 0 && !(mItem.flags & 0x10));
                const costVal = isCraftable ? (mItem.complexity * taxVal) : 10000000;
                if (costVal < minMachineCost) {
                    minMachineCost = costVal;
                    bestMachineIdx = mi;
                }
            }
        }
        return bestMachineIdx;
    }
    return r.machineItemIndex !== undefined ? r.machineItemIndex : -1;
}

export function renderRecipeRow(r, db, body = null, machineOverride = null) {
    const inputsHtml = [];
    const recipeKey = getRecipeIdentityKey(r);

    if (r.ingredients && r.ingredients.length > 0) {
        for (let slotIdx = 0; slotIdx < r.ingredients.length; slotIdx++) {
            const slot = r.ingredients[slotIdx];
            const slotVariants = [...slot.variants].map(v => ({index: v, item: db.items.get(v)}))
                .filter(x => x.item)
                .sort(compareByComplexity);

            if (slotVariants.length === 0) continue;

            if (slotVariants.length === 1) {
                const v = slotVariants[0];
                const comp = v.item.complexity;
                const isUncalc = (v.item.flags & 0x10) || v.item.categoryName === "Uncalculable";
                const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;
                const glowClass = isUncalc ? "uncalculable-highlight" : "";
                inputsHtml.push(`<span class="ingredient item-link ${glowClass}" data-index="${v.index}" title="${tooltip}">${escapeHtml(v.item.name || "#" + v.index)} × ${slot.count}</span>`);
            } else {
                const ingKey = `${recipeKey}_ing_${slotIdx}`;
                let activeVariantIdx = (body && body._customState && body._customState.selectedIngredients.has(ingKey))
                    ? body._customState.selectedIngredients.get(ingKey)
                    : -1;

                activeVariantIdx = resolveActiveVariant(slotVariants, activeVariantIdx, state);

                const head = slotVariants.find(v => v.index === activeVariantIdx) || slotVariants[0];
                const headComp = head.item.complexity;
                const isHeadUncalc = (head.item.flags & 0x10) || head.item.categoryName === "Uncalculable";
                const headTooltip = `Complexity: ${isHeadUncalc || headComp === -1 || !isFinite(headComp) ? 'Uncalculable' : formatComplexity(headComp)}`;

                const themeColor = isHeadUncalc ? "#ef4444" : "#f59e0b";
                const hoverColor = isHeadUncalc ? "#fca5a5" : "#fbbf24";
                const bgColor = isHeadUncalc ? "rgba(239, 68, 68, 0.08)" : "rgba(245, 158, 11, 0.05)";
                const borderRightColor = isHeadUncalc ? "rgba(239, 68, 68, 0.25)" : "rgba(245, 158, 11, 0.2)";
                const hoverBg = isHeadUncalc ? "rgba(239, 68, 68, 0.15)" : "rgba(245, 158, 11, 0.1)";
                const glowShadowStyle = isHeadUncalc ? "box-shadow: 0 0 5px rgba(239, 68, 68, 0.45);" : "";

                const variantItemsHtml = slotVariants.map(v => {
                    const isHead = v.index === head.index;
                    const comp = v.item.complexity;
                    const isUncalc = (v.item.flags & 0x10) || v.item.categoryName === "Uncalculable";
                    const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;
                    const uncalcStyle = isUncalc ? 'color: #f87171; background: rgba(239, 68, 68, 0.08);' : '';
                    return `
                        <div class="variant-item variant-item-substitute" data-recipe-key="${recipeKey}" data-slot-index="${slotIdx}" data-variant-index="${v.index}" title="${tooltip}" style="${uncalcStyle}">
                            <span class="name" style="${isHead ? (isHeadUncalc ? 'font-weight: 600; color: #fca5a5;' : 'font-weight: 600; color: #fbbf24;') : (isUncalc ? 'color: #f87171;' : '')}">${escapeHtml(v.item.name || "#" + v.index)}</span>
                            <span class="count" style="color: ${isUncalc ? '#f87171' : ''};">× ${slot.count}</span>
                        </div>
                    `;
                }).join("");

                inputsHtml.push(`
                    <div class="variant-group">
                        <div style="display: inline-flex; align-items: center; border-radius: 4px; overflow: hidden; border: 1px solid ${themeColor}; background: ${bgColor}; font-family: var(--mono), monospace; font-size: 11px; ${glowShadowStyle}">
                            <span class="item-link" data-index="${head.index}" title="${headTooltip}" style="padding: 2px 6px 2px 8px; cursor: pointer; color: ${themeColor}; border-right: 1px solid ${borderRightColor};" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                                    ${escapeHtml(head.item.name || "#" + head.index)} × ${slot.count}
                            </span>
                            <span class="variant-trigger cursor-pointer" style="padding: 2px 6px; cursor: pointer; display: flex; align-items: center; color: ${themeColor};" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                                <span class="arrow">▼</span>
                            </span>
                        </div>
                        <div class="variant-dropdown" style="border-color: ${themeColor}; text-align: left;">
                            <div class="variant-dropdown-header" style="${isHeadUncalc ? 'color: #f87171;' : ''}">
                                <span>ALTERNATIVE VARIANTS</span>
                                <span>(Lowest cost first)</span>
                            </div>
                            ${variantItemsHtml}
                        </div>
                    </div>
                `);
            }
        }
    }

    if (r.fluidIngredients && r.fluidIngredients.length > 0) {
        for (let slotIdx = 0; slotIdx < r.fluidIngredients.length; slotIdx++) {
            const slot = r.fluidIngredients[slotIdx];
            const slotVariants = [...slot.variants].map(v => ({index: v, item: db.fluids.get(v)}))
                .filter(x => x.item)
                .sort(compareByComplexity);

            if (slotVariants.length === 0) continue;

            if (slotVariants.length === 1) {
                const v = slotVariants[0];
                const comp = v.item.complexity;
                const isUncalc = (v.item.flags & 0x10) || v.item.categoryName === "Uncalculable";
                const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;

                if (isUncalc) {
                    inputsHtml.push(`<span class="ingredient fluid-link uncalculable-highlight" data-index="${v.index}" title="${tooltip}">${escapeHtml(v.item.name || "#" + v.index)} × ${slot.amount} mB</span>`);
                } else {
                    inputsHtml.push(`<span class="ingredient fluid-link" data-index="${v.index}" title="${tooltip}" style="color: #5ec7ff; border-color: rgba(94, 199, 255, 0.4); background: rgba(94, 199, 255, 0.08);">${escapeHtml(v.item.name || "#" + v.index)} × ${slot.amount} mB</span>`);
                }
            } else {
                const fluidKey = `${recipeKey}_fluid_${slotIdx}`;
                let activeVariantIdx = (body && body._customState && body._customState.selectedIngredients.has(fluidKey))
                    ? body._customState.selectedIngredients.get(fluidKey)
                    : -1;

                activeVariantIdx = resolveActiveVariant(slotVariants, activeVariantIdx, state);

                const head = slotVariants.find(v => v.index === activeVariantIdx) || slotVariants[0];
                const headComp = head.item.complexity;
                const isHeadUncalc = (head.item.flags & 0x10) || head.item.categoryName === "Uncalculable";
                const headTooltip = `Complexity: ${isHeadUncalc || headComp === -1 || !isFinite(headComp) ? 'Uncalculable' : formatComplexity(headComp)}`;

                const themeColor = isHeadUncalc ? "#ef4444" : "#5ec7ff";
                const hoverColor = isHeadUncalc ? "#fca5a5" : "#8dd5ff";
                const bgColor = isHeadUncalc ? "rgba(239, 68, 68, 0.08)" : "rgba(94, 199, 255, 0.05)";
                const borderRightColor = isHeadUncalc ? "rgba(239, 68, 68, 0.25)" : "rgba(94, 199, 255, 0.2)";
                const hoverBg = isHeadUncalc ? "rgba(239, 68, 68, 0.15)" : "rgba(94, 199, 255, 0.1)";
                const glowShadowStyle = isHeadUncalc ? "box-shadow: 0 0 5px rgba(239, 68, 68, 0.45);" : "";

                const variantItemsHtml = slotVariants.map(v => {
                    const isHead = v.index === head.index;
                    const comp = v.item.complexity;
                    const isUncalc = (v.item.flags & 0x10) || v.item.categoryName === "Uncalculable";
                    const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;
                    const uncalcStyle = isUncalc ? 'color: #f87171; background: rgba(239, 68, 68, 0.08);' : '';
                    return `
                        <div class="variant-item variant-fluid-substitute" data-recipe-key="${recipeKey}" data-slot-index="${slotIdx}" data-variant-index="${v.index}" title="${tooltip}" style="border-left: 2px solid ${isUncalc ? '#ef4444' : 'rgba(94, 199, 255, 0.4)'}; ${uncalcStyle}">
                            <span class="name" style="${isHead ? (isHeadUncalc ? 'font-weight: 600; color: #fca5a5;' : 'font-weight: 600; color: #5ec7ff;') : (isUncalc ? 'color: #f87171;' : '')}">${escapeHtml(v.item.name || "#" + v.index)}</span>
                            <span class="count" style="color: ${isUncalc ? '#f87171' : '#5ec7ff'};">× ${slot.amount} mB</span>
                        </div>
                    `;
                }).join("");

                inputsHtml.push(`
                    <div class="variant-group">
                        <div style="display: inline-flex; align-items: center; border-radius: 4px; overflow: hidden; border: 1px solid ${themeColor}; background: ${bgColor}; font-family: var(--mono), monospace; font-size: 11px; ${glowShadowStyle}">
                            <span class="fluid-link" data-index="${head.index}" title="${headTooltip}" style="padding: 2px 6px 2px 8px; cursor: pointer; color: ${themeColor}; border-right: 1px solid ${borderRightColor};" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                                ${escapeHtml(head.item.name || "#" + head.index)} × ${slot.amount} mB
                            </span>
                            <span class="variant-trigger cursor-pointer" style="padding: 2px 6px; cursor: pointer; display: flex; align-items: center; color: ${themeColor};" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                                <span class="arrow" style="color: ${themeColor};">▼</span>
                            </span>
                        </div>
                        <div class="variant-dropdown" style="border-color: ${isHeadUncalc ? '#ef4444' : 'rgba(94, 199, 255, 0.6)'}; text-align: left;">
                            <div class="variant-dropdown-header" style="color: ${themeColor}; border-bottom: 1px solid ${borderRightColor}; background: ${bgColor};">
                                <span>ALTERNATIVE FLUIDS</span>
                                <span>(Lowest cost first)</span>
                            </div>
                            ${variantItemsHtml}
                        </div>
                    </div>
                `);
            }
        }
    }


    const outputsHtml = [];
    if (r.itemOutputs && r.itemOutputs.length > 0) for (const out of r.itemOutputs) {
        const outItem = db.items.get(out.itemIndex);
        if (outItem) {
            const isCurrent = out.itemIndex === state.selectedItem && state.tab.startsWith("item");
            const isUncalc = (outItem.flags & 0x10) || outItem.categoryName === "Uncalculable";
            const comp = outItem.complexity;
            const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;

            let style = "";
            let classes = "ingredient item-link";
            if (isUncalc) {
                classes += " uncalculable-highlight";
            } else if (isCurrent) {
                style = "font-weight: 600; border-color: var(--accent); color: var(--accent);";
            }
            outputsHtml.push(`<span class="${classes}" data-index="${out.itemIndex}" title="${tooltip}" style="${style}">${escapeHtml(outItem.name)} × ${out.count}</span>`);
        }
    }
    if (r.fluidOutputs && r.fluidOutputs.length > 0) for (const out of r.fluidOutputs) {
        const outFluid = db.fluids.get(out.fluidIndex);
        if (outFluid) {
            const isCurrent = out.fluidIndex === state.selectedItem && state.tab.startsWith("fluid");
            const isUncalc = (outFluid.flags & 0x10) || outFluid.categoryName === "Uncalculable";
            const comp = outFluid.complexity;
            const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;

            let classes = "ingredient fluid-link";
            let style = "";
            if (isUncalc) {
                classes += " uncalculable-highlight";
            } else {
                const boldStyle = isCurrent ? "font-weight: 600; border-width: 2px;" : "";
                style = `color: #5ec7ff; border-color: rgba(94, 199, 255, 0.4); background: rgba(94, 199, 255, 0.08); ${boldStyle}`;
            }
            outputsHtml.push(`<span class="${classes}" data-index="${out.fluidIndex}" title="${tooltip}" style="${style}">${escapeHtml(outFluid.name)} × ${out.amount} mB</span>`);
        }
    }
    if (outputsHtml.length === 0) if (r.outputItemIndex >= 0) {
        const outItem = db.items.get(r.outputItemIndex);
        if (outItem) {
            const isCurrent = r.outputItemIndex === state.selectedItem && state.tab.startsWith("item");
            const isUncalc = (outItem.flags & 0x10) || outItem.categoryName === "Uncalculable";
            const comp = outItem.complexity;
            const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;

            let style = "";
            let classes = "ingredient item-link";
            if (isUncalc) {
                classes += " uncalculable-highlight";
            } else if (isCurrent) {
                style = "font-weight: 600; border-color: var(--accent); color: var(--accent);";
            }
            outputsHtml.push(`<span class="${classes}" data-index="${r.outputItemIndex}" title="${tooltip}" style="${style}">${escapeHtml(outItem.name)} × ${r.resultCount}</span>`);
        }
    }

    let allMachinesIdxs = r.allMachineIndexes || [];
    if (allMachinesIdxs.length === 0 && r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
        allMachinesIdxs = [r.machineItemIndex];
    }

    const machineKey = r.recipeType || "minecraft:custom";
    let activeMachineIdx = machineOverride ? machineOverride.index : resolveBestMachine(r, db, body);

    const machineItem = db.items.get(activeMachineIdx);
    const machineName = machineItem ? machineItem.name : r.recipeType;

    let amortizationHtml = "";
    if (machineItem && machineItem.complexity > 0) {
        const taxVal = (db.meta && db.meta.machineTaxMultiplier !== undefined) ? db.meta.machineTaxMultiplier : 0.05;
        const amortization = machineItem.complexity * taxVal;
        amortizationHtml = `<span style="font-size: 10px; color: var(--text-dim); margin-top: -2px; margin-bottom: 4px;" title="Amortization (machine complexity tax): ${fmt.format(machineItem.complexity)} * ${taxVal * 100}%">amort: +${fmt.format(amortization)}</span>`;
    }

    let machineHtml;
    if (allMachinesIdxs.length > 1) {
        const sortedMachines = [...allMachinesIdxs].map(mi => ({
            index: mi,
            item: db.items.get(mi)
        })).filter(x => x.item).sort(compareByComplexity);

        const machineItemsHtml = sortedMachines.map(mOpt => {
            const isHead = mOpt.index === activeMachineIdx;
            const isUncalc = (mOpt.item.flags & 0x10) || mOpt.item.categoryName === "Uncalculable";
            const comp = mOpt.item.complexity;
            const tooltip = `Complexity: ${isUncalc || comp === -1 || !isFinite(comp) ? 'Uncalculable' : formatComplexity(comp)}`;
            return `
                <div class="variant-item variant-machine-substitute" data-recipe-key="${recipeKey}" data-machine-key="${machineKey}" data-machine-index="${mOpt.index}" title="${tooltip}">
                    <span class="name" style="${isHead ? (isUncalc ? 'font-weight: 600; color: #fca5a5;' : 'font-weight: 600; color: #fbbf24;') : (isUncalc ? 'color: #f87171;' : '')}">${escapeHtml(mOpt.item.name)}</span>
                </div>
            `;
        }).join("");

        const activeMItem = db.items.get(activeMachineIdx);
        const isActiveMUncalc = activeMItem ? ((activeMItem.flags & 0x10) || activeMItem.categoryName === "Uncalculable") : false;
        const activeMComp = activeMItem ? activeMItem.complexity : -1;
        const activeMTooltip = activeMItem ? `Complexity: ${isActiveMUncalc || activeMComp === -1 || !isFinite(activeMComp) ? 'Uncalculable' : formatComplexity(activeMComp)}` : "";

        const themeColor = isActiveMUncalc ? "#ef4444" : "#f59e0b";
        const hoverColor = isActiveMUncalc ? "#fca5a5" : "#fbbf24";
        const bgColor = isActiveMUncalc ? "rgba(239, 68, 68, 0.08)" : "rgba(245, 158, 11, 0.05)";
        const borderRightColor = isActiveMUncalc ? "rgba(239, 68, 68, 0.25)" : "rgba(245, 158, 11, 0.2)";
        const hoverBg = isActiveMUncalc ? "rgba(239, 68, 68, 0.15)" : "rgba(245, 158, 11, 0.1)";
        const glowShadowStyle = isActiveMUncalc ? "box-shadow: 0 0 5px rgba(239, 68, 68, 0.45);" : "";

        machineHtml = `
            <div class="variant-group" style="margin-bottom: 4px; white-space: nowrap;">
                <div style="display: inline-flex; align-items: center; border-radius: 4px; overflow: hidden; border: 1px solid ${themeColor}; background: ${bgColor}; font-family: var(--mono), monospace; font-size: 11px; white-space: nowrap; ${glowShadowStyle}">
                    <strong class="machine-link" data-index="${activeMachineIdx}" title="${activeMTooltip}" style="padding: 2px 6px 2px 8px; cursor: pointer; color: ${themeColor}; border-right: 1px solid ${borderRightColor}; font-size: 11px; font-weight: 600; line-height: 1.3; white-space: nowrap;" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                        ${escapeHtml(machineName)}
                    </strong>
                    <span class="variant-trigger cursor-pointer" style="padding: 2px 6px; cursor: pointer; display: flex; align-items: center; color: ${themeColor};" onmouseover="this.style.color='${hoverColor}'; this.style.background='${hoverBg}';" onmouseout="this.style.color='${themeColor}'; this.style.background='transparent';">
                        <span class="arrow" style="color: ${themeColor};">▼</span>
                    </span>
                </div>
                <div class="variant-dropdown" style="text-align: left; border-color: ${themeColor};">
                    <div class="variant-dropdown-header" style="${isActiveMUncalc ? 'color: #f87171;' : ''}">
                        <span>COMPATIBLE MACHINES</span>
                        <span>(Lowest cost first)</span>
                    </div>
                    ${machineItemsHtml}
                </div>
            </div>
        `;
    } else {
        let mIsUncalc = false;
        let mTooltip = "";
        if (machineItem) {
            mIsUncalc = (machineItem.flags & 0x10) || machineItem.categoryName === "Uncalculable";
            mTooltip = `Complexity: ${mIsUncalc || machineItem.complexity === -1 || !isFinite(machineItem.complexity) ? 'Uncalculable' : formatComplexity(machineItem.complexity)}`;
        }
        machineHtml = machineItem
            ? `<strong style="cursor:pointer; color: ${mIsUncalc ? '#fca5a5' : 'var(--accent)'}; font-size: 11px; font-weight: 600; line-height: 1.3; white-space: nowrap;" class="machine-link" data-index="${activeMachineIdx}" title="${mTooltip}">${escapeHtml(machineName)}</strong>`
            : `<strong style="font-size: 11px; font-weight: 600; color: var(--accent); line-height: 1.3; white-space: nowrap;">${escapeHtml(machineName)}</strong>`;
    }

    return `
        <div class="recipe-card ${r.category === 0 ? "primary" : ""}" style="margin-top: 6px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; gap: 20px;">
            <div style="flex: 1; display: flex; flex-direction: column; gap: 4px;">
                <span style="font-size: 10px; color: var(--text-dim); text-transform: uppercase; letter-spacing: 1px; font-weight: 500;">Ingredients</span>
                <div class="ingredient-list" style="display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px;">
                    ${inputsHtml.length > 0 ? inputsHtml.join("") : `<span class="hint" style="font-size: 11px;">No input ingredients required</span>`}
                </div>
            </div>
            
            <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; flex-shrink: 0; min-width: 120px; max-width: 450px; text-align: center; padding: 0 16px; border-left: 1px dashed rgba(255,255,255,0.08); border-right: 1px dashed rgba(255,255,255,0.08);">
                ${machineHtml}
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