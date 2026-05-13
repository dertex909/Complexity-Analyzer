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

export const SEC = {
    META: 0x01, STRINGS: 0x02, ITEMS: 0x03, BASE_DATA: 0x04, SOURCES: 0x05,
    RECIPES: 0x06, USAGE: 0x07, MOBS: 0x08, DROPS: 0x09, SCC: 0x0A,
    CATEGORIES: 0x0B, IDX_ITEM_HASH: 0x20, IDX_RECIPES: 0x21, IDX_MOB_HASH: 0x22,
};

const MAGIC = 0x4E424143;
const HEADER_SIZE = 32;
const CODEC_RAW = 0;

export const ITEM_RECORD = 48;
export const MOB_RECORD = 80;

export const ITEM_FLAG = {
    HAS_RECIPE: 0x01, HAS_CYCLE: 0x02, IS_HARDCODED: 0x04,
    IS_VALID: 0x08, IS_INFINITE: 0x10, NO_RECIPE: 0x20,
};

export const MOB_FLAG = {BOSS: 0x01, MINIBOSS: 0x02};

class Buf {
    constructor(bytes, offset = 0) {
        this.b = bytes;
        this.v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        this.p = offset;
    }

    u8() {
        return this.b[this.p++];
    }

    u16() {
        const r = this.v.getUint16(this.p, true);
        this.p += 2;
        return r;
    }

    i32() {
        const r = this.v.getInt32(this.p, true);
        this.p += 4;
        return r;
    }

    u32() {
        const r = this.v.getUint32(this.p, true);
        this.p += 4;
        return r;
    }

    i64() {
        const r = this.v.getBigInt64(this.p, true);
        this.p += 8;
        return r;
    }

    f64() {
        const r = this.v.getFloat64(this.p, true);
        this.p += 8;
        return r;
    }

    skip(n) {
        this.p += n;
        return this;
    }

    seek(n) {
        this.p = n;
        return this;
    }
}

async function decompress(bytes) {
    if (typeof DecompressionStream === "undefined") throw new Error("No DecompressionStream support");
    const ds = new DecompressionStream("deflate-raw");
    const stream = new Blob([bytes]).stream().pipeThrough(ds);
    return new Response(stream).arrayBuffer().then(buf => new Uint8Array(buf));
}

export class CabinFile {
    constructor(url) {
        this.url = url;
        this.sections = new Map();
        this.fileHash = 0n;
        this.tocOffset = 0n;
        this.cache = new Map();
        this.fullBytes = null;
    }

    async open({preferFullDownload = false} = {}) {
        if (preferFullDownload) {
            const resp = await fetch(this.url, {cache: "no-store"});
            if (!resp.ok) throw new Error("Failed to fetch: " + resp.status);
            this.fullBytes = new Uint8Array(await resp.arrayBuffer());
            this._parseHeader(new Buf(this.fullBytes));
        } else {
            const head = await this._range(0, 4095);
            if (head.length < HEADER_SIZE) throw new Error("File too small");
            const b = new Buf(head);
            const magic = b.u32();
            if (magic !== MAGIC) throw new Error("Bad magic");
            b.seek(8);
            const toc = Number(b.i64());
            let currentHead = head;
            if (toc + 2 > head.length) currentHead = this._concat(head, await this._range(toc, toc + 8191), toc);
            const b2 = new Buf(currentHead, toc);
            const sectionCount = b2.u16();
            const totalTocSize = 2 + sectionCount * 26;
            if (toc + totalTocSize > currentHead.length) {
                const fullToc = await this._range(toc, toc + totalTocSize - 1);
                this._parseHeader(new Buf(this._concat(currentHead, fullToc, toc)));
            } else {
                this._parseHeader(new Buf(currentHead));
            }
        }
    }

    _concat(a, b, bOffset) {
        const total = new Uint8Array(Math.max(a.length, bOffset + b.length));
        total.set(a, 0);
        total.set(b, bOffset);
        return total;
    }

    _parseHeader(b) {
        if (b.u32() !== MAGIC) throw new Error("Bad magic");
        this.version = b.u16();
        this.tocOffset = b.seek(8).i64();
        this.fileHash = b.seek(24).i64();

        b.seek(Number(this.tocOffset));
        const count = b.u16();
        for (let i = 0; i < count; i++) {
            const id = b.u8(), codec = b.u8();
            const offset = Number(b.i64()), length = Number(b.i64()), uncompressed = Number(b.i64());
            this.sections.set(id, {id, codec, offset, length, uncompressed});
        }
    }

    async readSection(id) {
        if (this.cache.has(id)) return this.cache.get(id);
        const sec = this.sections.get(id);
        if (!sec) throw new Error("Section 0x" + Number(id).toString(16) + " not found");
        const raw = this.fullBytes ? this.fullBytes.subarray(sec.offset, sec.offset + sec.length) : await this._range(sec.offset, sec.offset + sec.length - 1);
        const data = sec.codec === CODEC_RAW ? raw.slice() : await decompress(raw);
        this.cache.set(id, data);
        return data;
    }

    async _range(s, e) {
        const r = await fetch(this.url, {headers: {Range: `bytes=${s}-${e}`}, cache: "no-store"});
        if (!r.ok && r.status !== 206) throw new Error("HTTP " + r.status);
        return new Uint8Array(await r.arrayBuffer());
    }
}

export class StringPool {
    constructor(bytes) {
        this.b = bytes;
        const b = new Buf(bytes);
        this.count = b.i32();
        this.offsets = new Uint32Array(this.count);
        this.cache = new Array(this.count);
        for (let i = 0; i < this.count; i++) {
            this.offsets[i] = b.p;
            b.skip(b.u16());
        }
        this.decoder = new TextDecoder("utf-8");
    }

    get(ref) {
        if (ref < 0 || ref >= this.count || this.cache[ref] !== undefined) return this.cache[ref] ?? "";
        const b = new Buf(this.b, this.offsets[ref]);
        const len = b.u16();
        return this.cache[ref] = this.decoder.decode(this.b.subarray(b.p, b.p + len));
    }
}

export class ItemTable {
    constructor(bytes, strings) {
        this.b = bytes;
        this.s = strings;
        this.count = new Buf(bytes).i32();
    }

    get(i) {
        if (i < 0 || i >= this.count) return null;
        const b = new Buf(this.b, 4 + i * ITEM_RECORD);
        return {
            index: i, id: this.s.get(b.i32()), name: this.s.get(b.i32()),
            complexity: b.f64(), depth: b.i32(), totalIngredients: b.i32(), usageCount: b.i32(),
            categoryName: this.s.get(b.i32()), baseDataOffset: b.u32(), sourcesOffset: b.u32(),
            sourceCount: b.u16(), categoryIndex: b.u8(), flags: b.u8(),
            errorMessage: this.s.get(b.i32())
        };
    }
}

export class MobTable {
    constructor(bytes, strings) {
        this.b = bytes;
        this.s = strings;
        this.count = new Buf(bytes).i32();
    }

    get(i) {
        if (i < 0 || i >= this.count) return null;
        const b = new Buf(this.b, 4 + i * MOB_RECORD);
        return {
            index: i, id: this.s.get(b.i32()), name: this.s.get(b.i32()), categoryName: this.s.get(b.i32()),
            health: b.seek(b.p + 4).f64(), damage: b.f64(), armor: b.f64(), survivability: b.f64(),
            threat: b.f64(), combatPower: b.f64(), rarity: b.f64(),
            dropsOffset: b.u32(), dropCount: b.u16(), flags: b.u8(), categoryEnum: b.u8()
        };
    }
}

export function readSourcesForItem(bytes, strings, offset, count) {
    if (offset === 0xFFFFFFFF || count === 0) return [];
    const b = new Buf(bytes, offset), out = [];
    for (let i = 0; i < count; i++) {
        const type = strings.get(b.i32()), typeEnum = b.u8(), details = strings.get(b.i32());
        const baseFactor = b.f64(), estimated = b.f64(), ingCount = b.u16();
        const ingredients = Array.from({length: ingCount}, () => ({itemIndex: b.i32(), amount: b.f64()}));
        out.push({
            sourceType: type,
            sourceTypeEnum: typeEnum,
            details,
            baseFactor,
            estimatedCost: estimated,
            ingredients
        });
    }
    return out;
}

export function readBaseDataForItem(bytes, strings, offset) {
    if (offset === 0xFFFFFFFF) return null;
    const b = new Buf(bytes, offset);
    const res = {
        sourceType: strings.get(b.i32()), sourceTypeEnum: b.u8(), details: strings.get(b.i32()),
        sourceName: strings.get(b.i32()), sourceSpecifier: strings.get(b.i32()), baseFactor: b.f64(),
        isOverride: b.u8() !== 0, overrideModId: strings.get(b.i32())
    };
    const ingCount = b.u16();
    res.ingredients = Array.from({length: ingCount}, () => ({itemIndex: b.i32(), amount: b.f64()}));
    const metaCount = b.u8(), meta = {};
    for (let j = 0; j < metaCount; j++) meta[strings.get(b.i32())] = strings.get(b.i32());
    res.metadata = meta;
    return res;
}

export class RecipeIndex {
    constructor(bytes) {
        this.b = bytes;
        this.count = new Buf(bytes).i32();
    }

    get(i) {
        if (i < 0 || i >= this.count) return {offset: 0xFFFFFFFF, count: 0};
        const b = new Buf(this.b, 4 + i * 6);
        return {offset: b.u32(), count: b.u16()};
    }
}

export function readRecipesAt(bytes, strings, offset, count) {
    if (offset === 0xFFFFFFFF || count === 0) return [];
    const b = new Buf(bytes, offset);
    return Array.from({length: count}, () => readOneRecipe(b, strings));
}

function readOneRecipe(b, strings) {
    const res = {
        outputItemIndex: b.i32(), recipeType: strings.get(b.i32()), category: b.u8(),
        priority: b.i32(), resultCount: b.i32(), recipeMultiplier: b.f64(),
        flags: b.u8(), placeholderId: strings.get(b.i32())
    };
    res.ingredients = Array.from({length: b.u8()}, () => {
        const vc = b.u8(), count = b.i32();
        return {count, variants: Array.from({length: vc}, () => b.i32())};
    });
    res.fluidIngredients = Array.from({length: b.u8()}, () => {
        const vc = b.u8(), amount = b.i32();
        return {amount, variants: Array.from({length: vc}, () => b.i32())};
    });
    res.chemicalIngredients = Array.from({length: b.u8()}, () => ({id: strings.get(b.i32()), amount: b.i32()}));
    res.itemOutputs = Array.from({length: b.u8()}, () => ({itemIndex: b.i32(), count: b.i32()}));
    res.fluidOutputs = Array.from({length: b.u8()}, () => ({fluidIndex: b.i32(), amount: b.i32()}));
    res.chemicalOutputs = Array.from({length: b.u8()}, () => ({id: strings.get(b.i32()), amount: Number(b.i64())}));
    return res;
}

export class UsageTable {
    constructor(bytes) {
        this.b = bytes;
        this.count = new Buf(bytes).i32();
        this.flat = 8 + this.count * 8;
    }

    get(i) {
        if (i < 0 || i >= this.count) return [];
        const b = new Buf(this.b, 8 + i * 8), start = b.i32(), n = b.i32();
        if (start === -1 || n === 0) return [];
        return Array.from({length: n}, (_, j) => new Buf(this.b, this.flat + start + j * 4).i32());
    }
}

export function readDropsForMob(bytes, strings, offset, count) {
    if (offset === 0xFFFFFFFF || count === 0) return [];
    const b = new Buf(bytes, offset);
    return Array.from({length: count}, () => ({
        itemIndex: b.i32(), itemName: strings.get(b.i32()), yieldPerKill: b.f64(),
        killMethod: strings.get(b.i32()), itemId: strings.get(b.i32())
    }));
}

export function readMeta(bytes, strings) {
    const b = new Buf(bytes);
    const res = {
        modId: strings.get(b.i32()),
        modVersion: strings.get(b.i32()),
        serverName: strings.get(b.i32()),
        timestampStr: strings.get(b.i32()),
        timestampMs: Number(b.i64())
    };
    Object.assign(res, {
        itemCount: b.i32(),
        mobCount: b.i32(),
        fluidCount: b.i32(),
        recipeCount: b.i32(),
        validItems: b.i32(),
        infiniteItems: b.i32()
    });
    res.categories = Array.from({length: b.u8()}, () => ({name: strings.get(b.i32()), maxComplexity: b.f64()}));
    return res;
}

export function readCategories(bytes, strings) {
    const b = new Buf(bytes);
    return Array.from({length: b.u8()}, () => {
        const name = strings.get(b.i32()), n = b.i32();
        return {name, items: Array.from({length: n}, () => b.i32())};
    });
}

export class CabinDatabase {
    constructor(url) {
        this.file = new CabinFile(url);
    }

    async open(opt = {}) {
        await this.file.open(opt);
        const [sB, iB, mB, meB, cB, rIB] = await Promise.all([SEC.STRINGS, SEC.ITEMS, SEC.MOBS, SEC.META, SEC.CATEGORIES, SEC.IDX_RECIPES].map(id => this.file.readSection(id)));
        this.strings = new StringPool(sB);
        this.items = new ItemTable(iB, this.strings);
        this.mobs = new MobTable(mB, this.strings);
        this.meta = readMeta(meB, this.strings);
        this.categories = readCategories(cB, this.strings);
        this.recipeIndex = new RecipeIndex(rIB);
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
}