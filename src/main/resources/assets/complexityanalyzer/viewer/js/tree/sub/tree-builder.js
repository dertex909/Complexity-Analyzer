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

export const NodeType = {
    CRAFTING: "CRAFTING",
    BASE_RESOURCE: "BASE_RESOURCE",
    CYCLE: "CYCLE",
    NO_DATA: "NO_DATA"
};

export class TreeBuilder {
    constructor(db) {
        this.db = db;
        this.nodeMap = new Map();
    }

    async buildRoot(kind, index, autoExpandDepth = 2) {
        this.nodeMap.clear();
        return await this._buildNode(kind, index, 1.0, 0, "0", new Set(), autoExpandDepth);
    }

    async _buildNode(kind, index, neededAmount, depth, uid, parentPath, autoExpandDepth) {
        const key = `${kind}:${index}`;
        const isCycle = parentPath?.has(key) ?? false;

        const entity = kind === "item" ? this.db.items.get(index) : this.db.fluids.get(index);
        if (!entity) return {
            uid,
            type: NodeType.NO_DATA,
            kind,
            index,
            depth,
            name: "Unknown",
            id: "unknown",
            neededAmount,
            amountText: this._formatAmount(neededAmount, kind),
            complexity: 0,
            collapsed: false,
            children: []
        };

        const currentPath = new Set(parentPath || []);
        currentPath.add(key);

        const complexity = (entity.complexity > 0 && !(entity.flags & 0x10)) ? entity.complexity : 0;
        const hasBaseSource = kind === "item" && entity.sourceCount > 0;

        if (isCycle) {
            const cycleNode = {
                uid,
                type: NodeType.CYCLE,
                kind,
                index,
                depth,
                path: currentPath,
                name: entity.name,
                id: entity.id,
                neededAmount,
                amountText: this._formatAmount(neededAmount, kind),
                complexity,
                categoryName: entity.categoryName || "Uncalculable",
                collapsed: false,
                children: []
            };
            this.nodeMap.set(uid, cycleNode);
            return cycleNode;
        }

        const node = {
            uid,
            type: NodeType.CRAFTING,
            kind,
            index,
            depth,
            path: currentPath,
            name: entity.name,
            id: entity.id,
            neededAmount,
            amountText: this._formatAmount(neededAmount, kind),
            complexity,
            categoryName: entity.categoryName || "Uncalculable",
            collapsed: depth >= autoExpandDepth,
            hasBaseSource,
            children: []
        };

        this.nodeMap.set(uid, node);

        const rawRecipes = await this._getRecipes(kind, index);
        const recipe = this._resolveOptimalRecipe(rawRecipes, index, currentPath);

        if (!recipe || (hasBaseSource && complexity > 0 && this._getRecipeUnitCost(recipe) > complexity * 1.05)) {
            node.type = NodeType.BASE_RESOURCE;
            node.collapsed = false;
            node.children = [];
            return node;
        }

        node.recipe = recipe;
        this._attachMachineInfo(node, recipe);
        if (!node.collapsed) await this.expandNode(node, autoExpandDepth);

        return node;
    }

    async expandNode(node, autoExpandDepth = 0) {
        const visitedOnPath = node.path || new Set();

        if (!node.recipe) {
            const rawRecipes = await this._getRecipes(node.kind, node.index);
            node.recipe = this._resolveOptimalRecipe(rawRecipes, node.index, visitedOnPath);
            if (node.recipe) this._attachMachineInfo(node, node.recipe);
        }

        if (!node.recipe || node.type === NodeType.BASE_RESOURCE || node.type === NodeType.CYCLE) return;

        const recipe = node.recipe;
        const resultCount = recipe.resultCount || 1;
        const craftOperations = node.neededAmount / resultCount;

        const itemIngredientsMap = this._aggregateSlotIngredients(recipe.ingredients, this.db.items, "count", 1);
        const fluidIngredientsMap = this._aggregateSlotIngredients(recipe.fluidIngredients, this.db.fluids, "amount", 1000);

        node.children = [];
        let childIdx = 0;

        for (const [itemIdx, countPerCraft] of itemIngredientsMap.entries()) {
            const totalNeeded = craftOperations * countPerCraft;
            const childUid = `${node.uid}.${childIdx++}`;
            const child = await this._buildNode("item", itemIdx, totalNeeded, node.depth + 1, childUid, node.path, autoExpandDepth);
            node.children.push(child);
        }

        for (const [fluidIdx, amountPerCraft] of fluidIngredientsMap.entries()) {
            const totalNeeded = craftOperations * amountPerCraft;
            const childUid = `${node.uid}.${childIdx++}`;
            const child = await this._buildNode("fluid", fluidIdx, totalNeeded, node.depth + 1, childUid, node.path, autoExpandDepth);
            node.children.push(child);
        }

        if (node.children.length === 0) node.type = NodeType.BASE_RESOURCE;
    }

    _attachMachineInfo(node, recipe) {
        if (!recipe) return;

        let allMs = recipe.allMachineIndexes ?? [];
        if (allMs.length === 0 && recipe.machineItemIndex !== undefined && recipe.machineItemIndex >= 0) {
            allMs = [recipe.machineItemIndex];
        }

        let bestMachineIdx = recipe.machineItemIndex >= 0 ? recipe.machineItemIndex : -1;
        const taxMultiplier = this.db.meta?.machineTaxMultiplier ?? 0.05;
        const fallbackComplexity = this.db.meta?.machineFallbackComplexity ?? 100.0;

        if (allMs.length > 0) {
            let minMachineCost = Infinity;
            bestMachineIdx = allMs[0];
            for (const mi of allMs) {
                const mItem = this.db.items.get(mi);
                if (mItem) {
                    const isCraftable = !!((mItem.flags & 0x01) && mItem.complexity > 0 && !(mItem.flags & 0x10));
                    const costVal = isCraftable ? (mItem.complexity * taxMultiplier) : 10_000_000;
                    if (costVal < minMachineCost) {
                        minMachineCost = costVal;
                        bestMachineIdx = mi;
                    }
                }
            }
        }

        if (bestMachineIdx >= 0) {
            const mItem = this.db.items.get(bestMachineIdx);
            if (mItem) {
                node.machineName = mItem.name;
                node.machineIndex = bestMachineIdx;
                if (mItem.complexity > 0 && !(mItem.flags & 0x10)) {
                    node.amortization = mItem.complexity * taxMultiplier;
                }
            }
        }

        if (!node.machineName && recipe.recipeType) {
            node.machineName = recipe.recipeType;
            const isCraftingTable = recipe.recipeType.includes("crafting_table") || recipe.recipeType === "minecraft:crafting";
            if (!isCraftingTable) {
                node.amortization = fallbackComplexity * taxMultiplier;
                node.isFallbackMachine = true;
            }
        }
    }

    _resolveOptimalRecipe(recipes, itemIndex, visitedOnPath) {
        if (!recipes?.length) return null;

        const valid = recipes.filter(r => !this._isSelfLoop(r, itemIndex) && !this._recipeCreatesCycle(r, visitedOnPath));
        const pool = valid.length > 0 ? valid : recipes.filter(r => !this._isSelfLoop(r, itemIndex));

        if (pool.length === 0) return null;

        return [...pool].sort((a, b) => {
            const costA = this._getRecipeUnitCost(a);
            const costB = this._getRecipeUnitCost(b);
            if (Math.abs(costA - costB) > 0.001) return costA - costB;
            return (b.priority || 0) - (a.priority || 0);
        })[0];
    }

    _getRecipeUnitCost(rec) {
        let total = 0;
        const tax = this.db.meta?.machineTaxMultiplier ?? 0.05;
        const fallbackComplexity = this.db.meta?.machineFallbackComplexity ?? 100.0;

        if (rec.machineItemIndex !== undefined && rec.machineItemIndex >= 0) {
            const m = this.db.items.get(rec.machineItemIndex);
            if (m && m.complexity > 0 && !(m.flags & 0x10)) total += m.complexity * tax;
        } else if (rec.recipeType && !rec.recipeType.includes("crafting_table") && rec.recipeType !== "minecraft:crafting") {
            total += fallbackComplexity * tax;
        }

        for (const slot of rec.ingredients ?? []) {
            if (!slot.variants?.length) continue;
            const {minComplexity} = this._getSlotVariantInfo(slot.variants, this.db.items);
            const complexity = minComplexity !== Infinity ? minComplexity : 1_000_000;
            total += complexity * (slot.count || 1);
        }

        for (const slot of rec.fluidIngredients ?? []) {
            if (!slot.variants?.length) continue;
            const {minComplexity} = this._getSlotVariantInfo(slot.variants, this.db.fluids);
            const complexity = minComplexity !== Infinity ? minComplexity : 1_000_000;
            total += complexity * ((slot.amount || 1000) / 1000);
        }

        const count = rec.resultCount || 1;
        return total / Math.max(1, count);
    }

    _recipeCreatesCycle(rec, visitedOnPath) {
        if (!visitedOnPath?.size) return false;

        for (const slot of rec.ingredients ?? []) {
            for (const v of slot.variants ?? []) {
                if (visitedOnPath.has(`item:${v}`)) return true;
            }
        }
        for (const slot of rec.fluidIngredients ?? []) {
            for (const v of slot.variants ?? []) {
                if (visitedOnPath.has(`fluid:${v}`)) return true;
            }
        }
        return false;
    }

    _isSelfLoop(rec, itemIndex) {
        return rec.ingredients?.some(slot => slot.variants?.includes(itemIndex)) ?? false;
    }

    _formatAmount(amount, kind) {
        if (kind === "fluid") {
            return amount >= 1000 ? `${(amount / 1000).toFixed(2)}B` : `${Math.round(amount)}mB`;
        }
        return amount % 1 === 0 ? `${amount}×` : `${amount.toFixed(2)}×`;
    }

    _getRecipes(kind, index) {
        return kind === "item" ? this.db.getItemRecipes(index) : this.db.getFluidRecipes(index);
    }

    _getSlotVariantInfo(variants, dbMap) {
        let bestVariant = variants[0];
        let minComplexity = Infinity;

        for (const v of variants) {
            const entity = dbMap.get(v);
            if (!entity || entity.complexity <= 0 || (entity.flags & 0x10)) continue;

            if (entity.complexity < minComplexity) {
                minComplexity = entity.complexity;
                bestVariant = v;
            }
        }

        return {bestVariant, minComplexity};
    }

    _aggregateSlotIngredients(slots, dbMap, qtyProp, defaultQty) {
        const map = new Map();
        for (const slot of slots ?? []) {
            if (!slot.variants?.length) continue;
            const {bestVariant} = this._getSlotVariantInfo(slot.variants, dbMap);
            const amount = slot[qtyProp] || defaultQty;
            map.set(bestVariant, (map.get(bestVariant) || 0) + amount);
        }
        return map;
    }
}