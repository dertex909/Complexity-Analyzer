import {escapeHtml, fmt} from "../../core/utils.js";
import {state, setState} from "../../core/state.js";

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

export function mergeDuplicateRecipes(recipes, db) {
    const unique = [];
    const keyToRecipe = new Map();
    for (const r of recipes) {
        const idKey = getRecipeIdentityKey(r);
        if (!keyToRecipe.has(idKey)) {
            const rCopy = {
                ...r,
                allMachineIndexes: []
            };
            if (r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
                rCopy.allMachineIndexes.push(r.machineItemIndex);
            }
            keyToRecipe.set(idKey, rCopy);
            unique.push(rCopy);
        } else {
            const existing = keyToRecipe.get(idKey);
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
    let activeMachineIdx = (body && body._customState && body._customState.selectedMachines.has(recipeKey))
        ? body._customState.selectedMachines.get(recipeKey)
        : r.machineItemIndex;

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

export function sortRecipes(recipes, db, sortType, body = null) {
    const sorted = [...recipes];
    sorted.sort((a, b) => {
        const calcA = isRecipeCalculable(a, db);
        const calcB = isRecipeCalculable(b, db);

        if (calcA !== calcB) return calcA ? -1 : 1;

        const activeMachineA = (body && body._customState && body._customState.selectedMachines.has(getRecipeIdentityKey(a)))
            ? body._customState.selectedMachines.get(getRecipeIdentityKey(a))
            : a.machineItemIndex;

        const activeMachineB = (body && body._customState && body._customState.selectedMachines.has(getRecipeIdentityKey(b)))
            ? body._customState.selectedMachines.get(getRecipeIdentityKey(b))
            : b.machineItemIndex;

        const machineA = db.items.get(activeMachineA);
        const machineB = db.items.get(activeMachineB);

        const craftableA = activeMachineA < 0 || (machineA ? !!((machineA.flags & 0x01) && machineA.complexity > 0 && !(machineA.flags & 0x10)) : false);
        const craftableB = activeMachineB < 0 || (machineB ? !!((machineB.flags & 0x01) && machineB.complexity > 0 && !(machineB.flags & 0x10)) : false);

        if (craftableA !== craftableB) return craftableA ? -1 : 1;

        const costA = getRecipeUnitCost(a, db, body);
        const costB = getRecipeUnitCost(b, db, body);

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
    if (!body._customState) {
        body._customState = {
            selectedMachines: new Map(),
            selectedIngredients: new Map()
        };
    }

    const merged = mergeDuplicateRecipes(sorted, db);
    const activeSort = localStorage.getItem("recipes-sort") || "optimal";
    const resorted = sortRecipes(merged, db, activeSort, body);

    const machineToRecipes = new Map();
    for (const r of resorted) {
        let allMachinesIdxs = r.allMachineIndexes || [];
        if (allMachinesIdxs.length === 0 && r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
            allMachinesIdxs = [r.machineItemIndex];
        }

        const recipeKey = getRecipeIdentityKey(r);
        const activeMachineIdx = body._customState.selectedMachines.has(recipeKey)
            ? body._customState.selectedMachines.get(recipeKey)
            : (allMachinesIdxs.length > 0 ? allMachinesIdxs[0] : (r.machineItemIndex !== undefined ? r.machineItemIndex : -1));

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

        const nameHtml = mItem
            ? `<strong style="color:var(--accent); cursor:pointer;" class="machine-link" data-index="${mi}">${escapeHtml(mItem.name)}</strong>`
            : `<strong style="color:#ef4444; margin-right: 8px;">Unknown Machine (${escapeHtml(mRecipes[0].recipeType || "Code")})</strong>`;

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
}

export function renderAndWireFlatRecipes(body, recipes, db, onSortChange, machineOverride = null) {
    const activeSort = localStorage.getItem("recipes-sort") || "optimal";

    if (!body._customState) {
        body._customState = {
            selectedMachines: new Map(),
            selectedIngredients: new Map()
        };
    }

    const merged = mergeDuplicateRecipes(recipes, db);
    const sorted = sortRecipes(merged, db, activeSort, body);

    body.innerHTML = renderRecipeControlsHtml(activeSort, merged.length) + `
        <div class="flat-recipes-list" style="display: flex; flex-direction: column; gap: 8px;">
            ${sorted.map(r => renderRecipeRow(r, db, body, machineOverride)).join("")}
        </div>
    `;

    wireRecipeLinks(body);
    wireRecipeSortListener(body, onSortChange);
    wireRecipeDropdowns(body, onSortChange);
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
            const machineIndex = parseInt(el.dataset.machineIndex, 10);
            if (container && container._customState) {
                container._customState.selectedMachines.set(recipeKey, machineIndex);
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
                inputsHtml.push(`<span class="ingredient item-link" data-index="${v.index}">${escapeHtml(v.item.name || "#" + v.index)} × ${slot.count}</span>`);
            } else {
                const ingKey = `${recipeKey}_ing_${slotIdx}`;
                let activeVariantIdx = (body && body._customState && body._customState.selectedIngredients.has(ingKey))
                    ? body._customState.selectedIngredients.get(ingKey)
                    : -1;

                if (activeVariantIdx === -1 || !slotVariants.some(v => v.index === activeVariantIdx)) {
                    const selectedIdx = slotVariants.findIndex(v => v.index === state.selectedItem);
                    activeVariantIdx = selectedIdx !== -1 ? slotVariants[selectedIdx].index : slotVariants[0].index;
                }

                const head = slotVariants.find(v => v.index === activeVariantIdx) || slotVariants[0];

                const variantItemsHtml = slotVariants.map(v => {
                    const isHead = v.index === head.index;
                    return `
                        <div class="variant-item variant-item-substitute" data-recipe-key="${recipeKey}" data-slot-index="${slotIdx}" data-variant-index="${v.index}">
                            <span class="name" style="${isHead ? 'font-weight: 600; color: #fbbf24;' : ''}">${escapeHtml(v.item.name || "#" + v.index)}</span>
                            <span class="count">× ${slot.count}</span>
                        </div>
                    `;
                }).join("");

                inputsHtml.push(`
                    <div class="variant-group">
                        <div style="display: inline-flex; align-items: center; border-radius: 4px; overflow: hidden; border: 1px solid #f59e0b; background: rgba(245, 158, 11, 0.05); font-family: var(--mono), monospace; font-size: 11px;">
                            <span class="item-link" data-index="${head.index}" style="padding: 2px 6px 2px 8px; cursor: pointer; color: #f59e0b; border-right: 1px solid rgba(245, 158, 11, 0.2);" onmouseover="this.style.color='#fbbf24'; this.style.background='rgba(245, 158, 11, 0.1)';" onmouseout="this.style.color='#f59e0b'; this.style.background='transparent';">
                                ${escapeHtml(head.item.name || "#" + head.index)} × ${slot.count}
                            </span>
                            <span class="variant-trigger cursor-pointer" style="padding: 2px 6px; cursor: pointer; display: flex; align-items: center; color: #f59e0b;" onmouseover="this.style.color='#fbbf24'; this.style.background='rgba(245, 158, 11, 0.1)';" onmouseout="this.style.color='#f59e0b'; this.style.background='transparent';">
                                <span class="arrow">▼</span>
                            </span>
                        </div>
                        <div class="variant-dropdown" style="border-color: #f59e0b; text-align: left;">
                            <div class="variant-dropdown-header">
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
                inputsHtml.push(`<span class="ingredient fluid-link" data-index="${v.index}" style="color: #5ec7ff; border-color: rgba(94, 199, 255, 0.4); background: rgba(94, 199, 255, 0.08);">${escapeHtml(v.item.name || "#" + v.index)} × ${slot.amount} mB</span>`);
            } else {
                const fluidKey = `${recipeKey}_fluid_${slotIdx}`;
                let activeVariantIdx = (body && body._customState && body._customState.selectedIngredients.has(fluidKey))
                    ? body._customState.selectedIngredients.get(fluidKey)
                    : -1;

                if (activeVariantIdx === -1 || !slotVariants.some(v => v.index === activeVariantIdx)) {
                    const selectedIdx = slotVariants.findIndex(v => v.index === state.selectedItem);
                    activeVariantIdx = selectedIdx !== -1 ? slotVariants[selectedIdx].index : slotVariants[0].index;
                }

                const head = slotVariants.find(v => v.index === activeVariantIdx) || slotVariants[0];

                const variantItemsHtml = slotVariants.map(v => {
                    const isHead = v.index === head.index;
                    return `
                        <div class="variant-item variant-fluid-substitute" data-recipe-key="${recipeKey}" data-slot-index="${slotIdx}" data-variant-index="${v.index}" style="border-left: 2px solid rgba(94, 199, 255, 0.4);">
                            <span class="name" style="${isHead ? 'font-weight: 600; color: #5ec7ff;' : ''}">${escapeHtml(v.item.name || "#" + v.index)}</span>
                            <span class="count" style="color: #5ec7ff;">× ${slot.amount} mB</span>
                        </div>
                    `;
                }).join("");

                inputsHtml.push(`
                    <div class="variant-group">
                        <div style="display: inline-flex; align-items: center; border-radius: 4px; overflow: hidden; border: 1px solid #5ec7ff; background: rgba(94, 199, 255, 0.05); font-family: var(--mono), monospace; font-size: 11px;">
                            <span class="fluid-link" data-index="${head.index}" style="padding: 2px 6px 2px 8px; cursor: pointer; color: #5ec7ff; border-right: 1px solid rgba(94, 199, 255, 0.2);" onmouseover="this.style.color='#8dd5ff'; this.style.background='rgba(94, 199, 255, 0.1)';" onmouseout="this.style.color='#5ec7ff'; this.style.background='transparent';">
                                ${escapeHtml(head.item.name || "#" + head.index)} × ${slot.amount} mB
                            </span>
                            <span class="variant-trigger cursor-pointer" style="padding: 2px 6px; cursor: pointer; display: flex; align-items: center; color: #5ec7ff;" onmouseover="this.style.color='#8dd5ff'; this.style.background='rgba(94, 199, 255, 0.1)';" onmouseout="this.style.color='#5ec7ff'; this.style.background='transparent';">
                                <span class="arrow" style="color: #5ec7ff;">▼</span>
                            </span>
                        </div>
                        <div class="variant-dropdown" style="border-color: rgba(94, 199, 255, 0.6); text-align: left;">
                            <div class="variant-dropdown-header" style="color: #5ec7ff; border-bottom: 1px solid rgba(94, 199, 255, 0.2); background: rgba(94, 199, 255, 0.1);">
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

    let allMachinesIdxs = r.allMachineIndexes || [];
    if (allMachinesIdxs.length === 0 && r.machineItemIndex !== undefined && r.machineItemIndex >= 0) {
        allMachinesIdxs = [r.machineItemIndex];
    }

    let activeMachineIdx = (body && body._customState && body._customState.selectedMachines.has(recipeKey))
        ? body._customState.selectedMachines.get(recipeKey)
        : (allMachinesIdxs.length > 0 ? allMachinesIdxs[0] : r.machineItemIndex);

    if (allMachinesIdxs.length > 0 && !allMachinesIdxs.includes(activeMachineIdx)) {
        activeMachineIdx = allMachinesIdxs[0];
    }

    const machineItem = machineOverride || db.items.get(activeMachineIdx);
    const machineName = machineItem ? machineItem.name : r.recipeType;

    let amortizationHtml = "";
    if (machineItem && machineItem.complexity > 0) {
        const taxVal = (db.meta && db.meta.machineTaxMultiplier !== undefined) ? db.meta.machineTaxMultiplier : 0.05;
        const amortization = machineItem.complexity * taxVal;
        amortizationHtml = `<span style="font-size: 10px; color: var(--text-dim); margin-top: -2px; margin-bottom: 4px;" title="Amortization (machine complexity tax): ${fmt.format(machineItem.complexity)} * ${taxVal * 100}%">amort: +${fmt.format(amortization)}</span>`;
    }

    let machineHtml = "";
    if (allMachinesIdxs.length > 1) {
        const sortedMachines = [...allMachinesIdxs].map(mi => ({
            index: mi,
            item: db.items.get(mi)
        })).filter(x => x.item).sort(compareByComplexity);

        const machineItemsHtml = sortedMachines.map(mOpt => {
            const isHead = mOpt.index === activeMachineIdx;
            return `
                <div class="variant-item variant-machine-substitute" data-recipe-key="${recipeKey}" data-machine-index="${mOpt.index}">
                    <span class="name" style="${isHead ? 'font-weight: 600; color: #fbbf24;' : ''}">${escapeHtml(mOpt.item.name)}</span>
                </div>
            `;
        }).join("");

        machineHtml = `
            <div class="variant-group" style="margin-bottom: 4px;">
                <div style="display: inline-flex; align-items: center; border-radius: 4px; overflow: hidden; border: 1px solid #f59e0b; background: rgba(245, 158, 11, 0.05); font-family: var(--mono), monospace; font-size: 11px;">
                    <strong class="machine-link" data-index="${activeMachineIdx}" style="padding: 2px 6px 2px 8px; cursor: pointer; color: #f59e0b; border-right: 1px solid rgba(245, 158, 11, 0.2); font-size: 11px; font-weight: 600; line-height: 1.3;" onmouseover="this.style.color='#fbbf24'; this.style.background='rgba(245, 158, 11, 0.1)';" onmouseout="this.style.color='#f59e0b'; this.style.background='transparent';">
                        ${escapeHtml(machineName)}
                    </strong>
                    <span class="variant-trigger cursor-pointer" style="padding: 2px 6px; cursor: pointer; display: flex; align-items: center; color: #f59e0b;" onmouseover="this.style.color='#fbbf24'; this.style.background='rgba(245, 158, 11, 0.1)';" onmouseout="this.style.color='#f59e0b'; this.style.background='transparent';">
                        <span class="arrow">▼</span>
                    </span>
                </div>
                <div class="variant-dropdown" style="text-align: left; border-color: #f59e0b;">
                    <div class="variant-dropdown-header">
                        <span>COMPATIBLE MACHINES</span>
                        <span>(Lowest cost first)</span>
                    </div>
                    ${machineItemsHtml}
                </div>
            </div>
        `;
    } else {
        machineHtml = machineItem
            ? `<strong style="cursor:pointer; color: var(--accent); font-size: 11px; font-weight: 600; line-height: 1.3;" class="machine-link" data-index="${activeMachineIdx}">${escapeHtml(machineName)}</strong>`
            : `<strong style="font-size: 11px; font-weight: 600; color: var(--accent); line-height: 1.3;">${escapeHtml(machineName)}</strong>`;
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