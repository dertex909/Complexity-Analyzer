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

import {
    CabinFile,
    FluidRecipeIndex,
    FluidTable,
    FluidUsageTable,
    ITEM_FLAG,
    ItemTable,
    MobTable,
    readBaseDataForItem,
    readCategories,
    readDropsForMob,
    readMachineIndex,
    readMeta,
    readModSummary,
    readRecipesAt,
    readSourcesForItem,
    readSourceTypeIndex,
    RecipeIndex,
    SEC,
    StringPool,
    UsageTable,
} from "./cabin.js";
import {mergeDuplicateRecipes} from "../views/sub/recipe-shared.js";

export class CabinDatabase {
    constructor(url) {
        this.file = new CabinFile(url);
        this.machineIndexSet = new Set();
        this._machineRecipesCache = new Map();
    }

    async open(opt = {}) {
        this._sB = this._bB = this._rB = this._frB = this._dB = null;
        this._u = this._fu = null;
        this._machineRecipesCache = new Map();
        await this.file.open(opt);
        const [sB, iB, mB, meB, cB, rIB, flB, miB, stiB, msB, flRIB, rB] = await Promise.all([
            SEC.STRINGS, SEC.ITEMS, SEC.MOBS, SEC.META, SEC.CATEGORIES, SEC.IDX_RECIPES, SEC.FLUIDS,
            SEC.MACHINE_INDEX, SEC.SOURCE_TYPE_INDEX, SEC.MOD_SUMMARY, SEC.IDX_FLUID_RECIPES, SEC.RECIPES
        ].map(id => this.file.readSection(id).catch(() => null)));

        this.strings = new StringPool(sB);
        this.items = new ItemTable(iB, this.strings);
        this.mobs = new MobTable(mB, this.strings);
        this.meta = readMeta(meB, this.strings);
        this.categories = readCategories(cB, this.strings);
        this.recipeIndex = new RecipeIndex(rIB);
        this.fluids = new FluidTable(flB, this.strings);
        this.fluidRecipeIndex = new FluidRecipeIndex(flRIB);

        this.machines = miB ? readMachineIndex(miB) : [];
        this.machineIndexSet = new Set(this.machines.map(m => m.itemIndex));
        this.sourceTypes = stiB ? readSourceTypeIndex(stiB) : [];
        this.modSummary = msB ? readModSummary(msB, this.strings) : [];
        this._rB = rB;

        if (this._rB) {
            const cache = new Map();
            const visiting = new Set();
            const self = this;

            function getHeuristicCost(rec) {
                let cost = 0;
                if (rec.ingredients) for (const slot of rec.ingredients) {
                    if (slot.variants && slot.variants.length > 0) {
                        let minComp = Infinity;
                        for (const v of slot.variants) {
                            const it = self.items.get(v);
                            if (it && it.complexity !== -1 && !(it.flags & 0x10)) {
                                if (it.complexity < minComp) minComp = it.complexity;
                            }
                        }
                        if (minComp !== Infinity) {
                            cost += slot.count * minComp;
                        } else {
                            cost += 1000000;
                        }
                    }
                }
                return cost;
            }

            function hasSelfLoop(rec, targetIdx) {
                if (rec.ingredients) for (const slot of rec.ingredients) {
                    if (slot.variants && slot.variants.includes(targetIdx)) return true;
                }
                return false;
            }

            function solve(itemIdx) {
                if (cache.has(itemIdx)) return cache.get(itemIdx);
                if (visiting.has(itemIdx)) return 1;

                visiting.add(itemIdx);

                const item = self.items.get(itemIdx);
                if (!item) {
                    visiting.delete(itemIdx);
                    return 0;
                }

                const ref = self.recipeIndex.get(itemIdx);
                if (!ref || ref.count === 0 || !(item.flags & 0x01)) {
                    cache.set(itemIdx, 1);
                    visiting.delete(itemIdx);
                    return 1;
                }

                let recipes;
                try {
                    recipes = readRecipesAt(self._rB, self.strings, ref.offset, ref.count);
                } catch (e) {
                    recipes = null;
                }

                if (!recipes || recipes.length === 0) {
                    cache.set(itemIdx, 1);
                    visiting.delete(itemIdx);
                    return 1;
                }

                const sortedRecipes = [...recipes].sort((a, b) => {
                    const loopA = hasSelfLoop(a, itemIdx);
                    const loopB = hasSelfLoop(b, itemIdx);
                    if (loopA !== loopB) return loopA ? 1 : -1;

                    const costA = getHeuristicCost(a);
                    const costB = getHeuristicCost(b);
                    return costA - costB;
                });

                const r = sortedRecipes[0];

                let sum = 0;
                if (r.ingredients && r.ingredients.length > 0) for (const slot of r.ingredients) {
                    if (slot.variants && slot.variants.length > 0) {
                        let bestVariantIdx = slot.variants[0];
                        let minComp = Infinity;

                        for (const v of slot.variants) {
                            const it = self.items.get(v);
                            if (it) {
                                const comp = (it.complexity === -1 || (it.flags & 0x10)) ? Infinity : it.complexity;
                                if (comp < minComp) {
                                    minComp = comp;
                                    bestVariantIdx = v;
                                }
                            }
                        }

                        const variantIngCount = solve(bestVariantIdx);
                        sum += slot.count * variantIngCount;
                    }
                }

                const resultCount = r.resultCount || 1;
                const total = sum / resultCount;

                cache.set(itemIdx, total);
                visiting.delete(itemIdx);
                return total;
            }

            for (let i = 0; i < this.items.count; i++) solve(i);

            for (let i = 0; i < this.items.count; i++) {
                const item = this.items.get(i);
                if (item) {
                    const calculated = cache.get(i);
                    if (calculated !== undefined) item.totalIngredients = Math.round(calculated * 100) / 100;
                }
            }
        }

        let infCount = 0;
        for (let i = 0; i < this.items.count; i++) {
            const it = this.items.get(i);
            if (it && (it.complexity === -1 || (it.flags & ITEM_FLAG.IS_UNCALCULABLE))) infCount++;
        }
        this.meta.infiniteItems = infCount;
    }

    isMachine(itemIndex) {
        return this.machineIndexSet ? this.machineIndexSet.has(itemIndex) : false;
    }

    async _ensure(key, id) {
        if (!this[key]) this[key] = await this.file.readSection(id);
        return this[key];
    }

    async ensureRecipesLoaded() {
        await this._ensure('_rB', SEC.RECIPES);
        return this._rB;
    }

    getItemRecipesSync(i) {
        if (!this._rB) return [];
        const ref = this.recipeIndex.get(i);
        if (!ref || ref.offset === 0xFFFFFFFF || ref.count <= 0) return [];
        return readRecipesAt(this._rB, this.strings, ref.offset, ref.count);
    }

    async getItemSources(i) {
        const it = this.items.get(i);
        return it ? readSourcesForItem(await this._ensure('_sB', SEC.SOURCES), this.strings, it.sourcesOffset, it.sourceCount) : [];
    }

    async getItemBaseData(i) {
        const it = this.items.get(i);
        return it ? readBaseDataForItem(await this._ensure('_bB', SEC.BASE_DATA), this.strings, it.baseDataOffset) : null;
    }

    async getItemRecipes(i) {
        const ref = this.recipeIndex.get(i);
        return readRecipesAt(await this._ensure('_rB', SEC.RECIPES), this.strings, ref.offset, ref.count);
    }

    async getItemUsage(i) {
        if (!this._u) this._u = new UsageTable(await this.file.readSection(SEC.USAGE));
        return this._u.get(i);
    }

    async getFluidRecipes(i) {
        const ref = this.fluidRecipeIndex.get(i);
        return readRecipesAt(await this._ensure('_frB', SEC.FLUID_RECIPES), this.strings, ref.offset, ref.count);
    }

    async getFluidUsage(i) {
        if (!this._fu) this._fu = new FluidUsageTable(await this.file.readSection(SEC.FLUID_USAGE));
        return this._fu.get(i);
    }

    async getMobDrops(i) {
        const m = this.mobs.get(i);
        return m ? readDropsForMob(await this._ensure('_dB', SEC.DROPS), this.strings, m.dropsOffset, m.dropCount) : [];
    }

    async getRecipesByMachine(machineItemIndex) {
        if (!this._machineRecipesCache) this._machineRecipesCache = new Map();
        if (this._machineRecipesCache.has(machineItemIndex)) return this._machineRecipesCache.get(machineItemIndex);

        const machine = this.machines.find(m => m.itemIndex === machineItemIndex);
        if (!machine) return [];

        const rB = await this.ensureRecipesLoaded();
        const rawRecipes = [];

        for (let i = 0; i < machine.items.length; i++) {
            const itemIdx = machine.items[i];
            const ref = this.recipeIndex.get(itemIdx);
            if (ref && ref.offset !== 0xFFFFFFFF && ref.count > 0) {
                try {
                    const itemRecipes = readRecipesAt(rB, this.strings, ref.offset, ref.count);
                    for (let j = 0; j < itemRecipes.length; j++) rawRecipes.push(itemRecipes[j]);
                } catch (e) {
                    console.error("Error loading item recipes:", e);
                }
            }
        }

        const frB = await this._ensure('_frB', SEC.FLUID_RECIPES);
        for (let i = 0; i < this.fluids.count; i++) {
            const ref = this.fluidRecipeIndex.get(i);
            if (ref && ref.offset !== 0xFFFFFFFF && ref.count > 0) try {
                const fluidRecipes = readRecipesAt(frB, this.strings, ref.offset, ref.count);
                for (let j = 0; j < fluidRecipes.length; j++) {
                    const r = fluidRecipes[j];
                    if ((r.allMachineIndexes && r.allMachineIndexes.includes(machineItemIndex)) || r.machineItemIndex === machineItemIndex) {
                        rawRecipes.push(r);
                    }
                }
            } catch (e) {
            }
        }

        const result = mergeDuplicateRecipes(rawRecipes);
        this._machineRecipesCache.set(machineItemIndex, result);
        return result;
    }

    deduplicateRecipes(recipes) {
        return mergeDuplicateRecipes(recipes);
    }
}