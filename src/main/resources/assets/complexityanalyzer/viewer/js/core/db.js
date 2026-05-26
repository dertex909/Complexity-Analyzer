import {
    SEC, CabinFile, StringPool, ItemTable, MobTable, FluidTable,
    RecipeIndex, FluidRecipeIndex, UsageTable, FluidUsageTable, readMeta, readCategories,
    readSourcesForItem, readBaseDataForItem, readRecipesAt,
    readDropsForMob, readMachineIndex, readSourceTypeIndex, readModSummary,
    ITEM_FLAG,
} from "./cabin.js";

export class CabinDatabase {
    constructor(url) {
        this.file = new CabinFile(url);
    }

    async open(opt = {}) {
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
        this.sourceTypes = stiB ? readSourceTypeIndex(stiB) : [];
        this.modSummary = msB ? readModSummary(msB, this.strings) : [];
        this._rB = rB;

        if (this._rB) {
            const cache = new Map();
            const visiting = new Set();
            const self = this;

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

                const r = recipes.find(rec => rec.category === 0) || recipes[0];

                let sum = 0;
                if (r.ingredients && r.ingredients.length > 0) for (const slot of r.ingredients) {
                    if (slot.variants && slot.variants.length > 0) {
                        const variantIdx = slot.variants[0];
                        const variantIngCount = solve(variantIdx);
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

    async _ensure(key, id) {
        if (!this[key]) this[key] = await this.file.readSection(id);
        return this[key];
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
        const machine = this.machines.find(m => m.itemIndex === machineItemIndex);
        if (!machine) return [];

        const recipes = [];
        const seenKeys = new Set();

        const addRecipe = (r) => {
            const key = `${r.recipeType}_${r.machineItemIndex}_${r.priority}_` +
                (r.ingredients ? r.ingredients.map(ing => ing.variants.join(",")).join(";") : "") + "_" +
                (r.fluidIngredients ? r.fluidIngredients.map(f => f.variants.join(",")).join(";") : "") + "_" +
                (r.itemOutputs ? r.itemOutputs.map(out => `${out.itemIndex}:${out.count}`).join(",") : "") + "_" +
                (r.fluidOutputs ? r.fluidOutputs.map(out => `${out.fluidIndex}:${out.amount}`).join(",") : "");

            if (!seenKeys.has(key)) {
                seenKeys.add(key);
                recipes.push(r);
            }
        };

        for (const itemIdx of machine.items) {
            try {
                const itemRecipes = await this.getItemRecipes(itemIdx);
                for (const r of itemRecipes) addRecipe(r);
            } catch (e) {
                console.error("Error loading item recipes:", e);
            }
        }

        for (let i = 0; i < this.fluids.count; i++) {
            try {
                const fluidRecipes = await this.getFluidRecipes(i);
                for (const r of fluidRecipes) if (r.machineItemIndex === machineItemIndex) addRecipe(r);
            } catch (e) {
            }
        }
        return recipes;
    }
}