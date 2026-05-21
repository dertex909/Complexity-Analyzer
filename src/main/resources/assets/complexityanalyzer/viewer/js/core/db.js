import {
    SEC, CabinFile, StringPool, ItemTable, MobTable, FluidTable,
    RecipeIndex, UsageTable, readMeta, readCategories,
    readSourcesForItem, readBaseDataForItem, readRecipesAt,
    readDropsForMob, readMachineIndex, readSourceTypeIndex, readModSummary,
} from "./cabin.js";

export class CabinDatabase {
    constructor(url) { this.file = new CabinFile(url); }

    async open(opt = {}) {
        await this.file.open(opt);
        const [sB, iB, mB, meB, cB, rIB, flB, miB, stiB, msB] = await Promise.all([
            SEC.STRINGS, SEC.ITEMS, SEC.MOBS, SEC.META, SEC.CATEGORIES, SEC.IDX_RECIPES, SEC.FLUIDS,
            SEC.MACHINE_INDEX, SEC.SOURCE_TYPE_INDEX, SEC.MOD_SUMMARY,
        ].map(id => this.file.readSection(id).catch(() => null)));

        this.strings = new StringPool(sB);
        this.items = new ItemTable(iB, this.strings);
        this.mobs = new MobTable(mB, this.strings);
        this.meta = readMeta(meB, this.strings);
        this.categories = readCategories(cB, this.strings);
        this.recipeIndex = new RecipeIndex(rIB);
        this.fluids = new FluidTable(flB, this.strings);

        this.machines = miB ? readMachineIndex(miB) : [];
        this.sourceTypes = stiB ? readSourceTypeIndex(stiB) : [];
        this.modSummary = msB ? readModSummary(msB, this.strings) : [];
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

    async getMobDrops(i) {
        const m = this.mobs.get(i);
        return m ? readDropsForMob(await this._ensure('_dB', SEC.DROPS), this.strings, m.dropsOffset, m.dropCount) : [];
    }

    async getRecipesByMachine(machineItemIndex) {
        const machine = this.machines.find(m => m.itemIndex === machineItemIndex);
        if (!machine) return [];
        const recipes = [];
        for (const itemIdx of machine.items) {
            const itemRecipes = await this.getItemRecipes(itemIdx);
            recipes.push(...itemRecipes);
        }
        return recipes;
    }

    getItemsBySourceType(typeEnum) {
        const entry = this.sourceTypes.find(s => s.typeEnum === typeEnum);
        return entry ? entry.items : [];
    }
}
