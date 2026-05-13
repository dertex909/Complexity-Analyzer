// Complexity Analyzer — Cabin binary reader (v1.1)
// Zero dependencies. Uses native DecompressionStream('deflate-raw') for decompression.
// LICENSE: LGPL-3.0 (see mod source).

// ===== Format constants (mirror CabinFormat.java) =====
export const SEC = {
    META: 0x01,
    STRINGS: 0x02,
    ITEMS: 0x03,
    BASE_DATA: 0x04,
    SOURCES: 0x05,
    RECIPES: 0x06,
    USAGE: 0x07,
    MOBS: 0x08,
    DROPS: 0x09,
    SCC: 0x0A,
    CATEGORIES: 0x0B,
    IDX_ITEM_HASH: 0x20,
    IDX_RECIPES: 0x21,
    IDX_MOB_HASH: 0x22,
};

const MAGIC = 0x4E424143;
const HEADER_SIZE = 32;
const CODEC_RAW = 0;
const CODEC_DEFLATE = 1;

export const ITEM_RECORD = 48;
export const MOB_RECORD = 80;

export const ITEM_FLAG = {
    HAS_RECIPE: 0x01,
    HAS_CYCLE: 0x02,
    IS_HARDCODED: 0x04,
    IS_VALID: 0x08,
    IS_INFINITE: 0x10,
    NO_RECIPE: 0x20,
};

export const MOB_FLAG = {
    BOSS: 0x01,
    MINIBOSS: 0x02,
};

// ===== Low-level helpers =====

function decompressDeflateRaw(bytes) {
    if (typeof DecompressionStream === "undefined") {
        throw new Error("This browser has no DecompressionStream support (needs Chrome 80+, Firefox 113+, Safari 16.4+).");
    }
    const ds = new DecompressionStream("deflate-raw");
    const stream = new Blob([bytes]).stream().pipeThrough(ds);
    return new Response(stream).arrayBuffer().then(buf => new Uint8Array(buf));
}

// ===== CabinFile: header + TOC + lazy section loader =====

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
            if (!resp.ok) throw new Error("Failed to fetch cabin: " + resp.status);
            const buf = await resp.arrayBuffer();
            this.fullBytes = new Uint8Array(buf);
            this._parseHeaderAndToc(this.fullBytes, 0);
        } else {
            const head = await this._range(0, 4095);
            const initialLen = head.length;
            if (initialLen < HEADER_SIZE) throw new Error("File too small");
            const dv = new DataView(head.buffer, head.byteOffset, head.byteLength);
            const magic = dv.getUint32(0, true);
            if (magic !== MAGIC) throw new Error("Bad magic: 0x" + magic.toString(16));
            const tocOffsetBig = dv.getBigUint64(8, true);
            const tocOffset = Number(tocOffsetBig);
            if (tocOffset + 2 > initialLen) {
                const extra = await this._range(tocOffset, tocOffset + 8192);
                this._parseHeaderAndToc(this._concat(head, extra, tocOffset), 0);
            } else {
                this._parseHeaderAndToc(head, 0);
            }
        }
    }

    _concat(a, b, bOffset) {
        const total = new Uint8Array(bOffset + b.length);
        total.set(a, 0);
        total.set(b, bOffset);
        return total;
    }

    _parseHeaderAndToc(bytes, baseOffset) {
        const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        const magic = dv.getUint32(baseOffset + 0, true);
        if (magic !== MAGIC) throw new Error("Bad magic: 0x" + magic.toString(16));
        this.version = dv.getUint16(baseOffset + 4, true);
        this.tocOffset = dv.getBigUint64(baseOffset + 8, true);
        this.fileHash = dv.getBigUint64(baseOffset + 24, true);

        const tocStart = Number(this.tocOffset);
        const sectionCount = dv.getUint16(tocStart, true);
        let p = tocStart + 2;
        for (let i = 0; i < sectionCount; i++) {
            const id = dv.getUint8(p);
            p += 1;
            const codec = dv.getUint8(p);
            p += 1;
            const offset = Number(dv.getBigUint64(p, true));
            p += 8;
            const length = Number(dv.getBigUint64(p, true));
            p += 8;
            const uncompressed = Number(dv.getBigUint64(p, true));
            p += 8;
            this.sections.set(id, {id, codec, offset, length, uncompressed});
        }
    }

    async readSection(id) {
        if (this.cache.has(id)) return this.cache.get(id);
        const sec = this.sections.get(id);
        if (!sec) throw new Error("Section not found: 0x" + Number(id).toString(16));
        let raw;
        if (this.fullBytes) {
            raw = this.fullBytes.subarray(sec.offset, sec.offset + sec.length);
        } else {
            raw = await this._range(sec.offset, sec.offset + sec.length - 1);
        }
        let data;
        if (sec.codec === CODEC_RAW) {
            data = raw.slice();
        } else if (sec.codec === CODEC_DEFLATE) {
            data = await decompressDeflateRaw(raw);
            if (data.length !== sec.uncompressed) {
                console.warn(`Section 0x${Number(id).toString(16)}: decompressed ${data.length} != declared ${sec.uncompressed}`);
            }
        } else {
            throw new Error("Unknown codec: " + sec.codec);
        }
        this.cache.set(id, data);
        return data;
    }

    async _range(start, endInclusive) {
        const resp = await fetch(this.url, {
            headers: {Range: `bytes=${start}-${endInclusive}`},
            cache: "no-store",
        });
        if (!resp.ok && resp.status !== 206) throw new Error("HTTP " + resp.status);
        return new Uint8Array(await resp.arrayBuffer());
    }
}

// ===== Reader helpers =====

export function readU8(bytes, o) {
    return bytes[o];
}

export function readU16(bytes, o) {
    return bytes[o] | (bytes[o + 1] << 8);
}

export function readI32(bytes, o) {
    return (bytes[o] | (bytes[o + 1] << 8) | (bytes[o + 2] << 16) | (bytes[o + 3] << 24)) | 0;
}

export function readU32(bytes, o) {
    return ((bytes[o] | (bytes[o + 1] << 8) | (bytes[o + 2] << 16) | (bytes[o + 3] << 24)) >>> 0);
}

export function readI64(bytes, o) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset + o, 8);
    return dv.getBigInt64(0, true);
}

export function readF64(bytes, o) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset + o, 8);
    return dv.getFloat64(0, true);
}

// ===== String pool =====

export class StringPool {
    constructor(bytes) {
        this.bytes = bytes;
        const n = readI32(bytes, 0);
        this.count = n;
        const offsets = new Uint32Array(n);
        let p = 4;
        for (let i = 0; i < n; i++) {
            offsets[i] = p;
            const len = readU16(bytes, p);
            p += 2 + len;
        }
        this.offsets = offsets;
        this.decoder = new TextDecoder("utf-8");
        this.cache = new Array(n);
    }

    get(ref) {
        if (ref < 0 || ref >= this.count) return "";
        const cached = this.cache[ref];
        if (cached !== undefined) return cached;
        const p = this.offsets[ref];
        const len = readU16(this.bytes, p);
        const str = this.decoder.decode(this.bytes.subarray(p + 2, p + 2 + len));
        this.cache[ref] = str;
        return str;
    }
}

export class ItemTable {
    constructor(bytes, strings) {
        this.bytes = bytes;
        this.strings = strings;
        this.count = readI32(bytes, 0);
        this.baseOffset = 4;
    }

    get(i) {
        if (i < 0 || i >= this.count) return null;
        const b = this.bytes;
        const off = this.baseOffset + i * ITEM_RECORD;
        const categoryIndex = readU8(b, off + 42);
        return {
            index: i,
            id: this.strings.get(readI32(b, off)),
            name: this.strings.get(readI32(b, off + 4)),
            complexity: readF64(b, off + 8),
            depth: readI32(b, off + 16),
            totalIngredients: readI32(b, off + 20),
            usageCount: readI32(b, off + 24),
            categoryName: this.strings.get(readI32(b, off + 28)),
            baseDataOffset: readU32(b, off + 32),
            sourcesOffset: readU32(b, off + 36),
            sourceCount: readU16(b, off + 40),
            categoryIndex,
            flags: readU8(b, off + 43),
            errorMessage: this.strings.get(readI32(b, off + 44)),
        };
    }
}

// ===== Mobs table =====

export class MobTable {
    constructor(bytes, strings) {
        this.bytes = bytes;
        this.strings = strings;
        this.count = readI32(bytes, 0);
        this.baseOffset = 4;
    }

    get(i) {
        if (i < 0 || i >= this.count) return null;
        const b = this.bytes;
        const off = this.baseOffset + i * MOB_RECORD;
        return {
            index: i,
            id: this.strings.get(readI32(b, off)),
            name: this.strings.get(readI32(b, off + 4)),
            categoryName: this.strings.get(readI32(b, off + 8)),
            health: readF64(b, off + 16),
            damage: readF64(b, off + 24),
            armor: readF64(b, off + 32),
            survivability: readF64(b, off + 40),
            threat: readF64(b, off + 48),
            combatPower: readF64(b, off + 56),
            rarity: readF64(b, off + 64),
            dropsOffset: readU32(b, off + 72),
            dropCount: readU16(b, off + 76),
            flags: readU8(b, off + 78),
            categoryEnum: readU8(b, off + 79),
        };
    }
}

// ===== Sources (alt sources per item) =====

export function readSourcesForItem(sourcesBytes, strings, offset, count) {
    if (offset === 0xFFFFFFFF || count === 0) return [];
    const b = sourcesBytes;
    const out = new Array(count);
    let p = offset;
    for (let i = 0; i < count; i++) {
        const typeRef = readI32(b, p);
        p += 4;
        const typeEnum = readU8(b, p);
        p += 1;
        const detailsRef = readI32(b, p);
        p += 4;
        const baseFactor = readF64(b, p);
        p += 8;
        const estimated = readF64(b, p);
        p += 8;
        const ingCount = readU16(b, p);
        p += 2;
        const ingredients = new Array(ingCount);
        for (let j = 0; j < ingCount; j++) {
            const idx = readI32(b, p);
            p += 4;
            const amount = readF64(b, p);
            p += 8;
            ingredients[j] = {itemIndex: idx, amount};
        }
        out[i] = {
            sourceType: strings.get(typeRef),
            sourceTypeEnum: typeEnum,
            details: strings.get(detailsRef),
            baseFactor,
            estimatedCost: estimated,
            ingredients,
        };
    }
    return out;
}

export function readBaseDataForItem(baseBytes, strings, offset) {
    if (offset === 0xFFFFFFFF) return null;
    const b = baseBytes;
    let p = offset;
    const typeRef = readI32(b, p);
    p += 4;
    const typeEnum = readU8(b, p);
    p += 1;
    const detailsRef = readI32(b, p);
    p += 4;
    const nameRef = readI32(b, p);
    p += 4;
    const specifierRef = readI32(b, p);
    p += 4;
    const baseFactor = readF64(b, p);
    p += 8;
    const isOverride = readU8(b, p);
    p += 1;
    const overrideModRef = readI32(b, p);
    p += 4;
    const ingCount = readU16(b, p);
    p += 2;
    const ingredients = [];
    for (let j = 0; j < ingCount; j++) {
        const idx = readI32(b, p);
        p += 4;
        const amount = readF64(b, p);
        p += 8;
        ingredients.push({itemIndex: idx, amount});
    }
    const metaCount = readU8(b, p);
    p += 1;
    const metadata = {};
    for (let j = 0; j < metaCount; j++) {
        const k = readI32(b, p);
        p += 4;
        const v = readI32(b, p);
        p += 4;
        metadata[strings.get(k)] = strings.get(v);
    }
    return {
        sourceType: strings.get(typeRef),
        sourceTypeEnum: typeEnum,
        details: strings.get(detailsRef),
        sourceName: strings.get(nameRef),
        sourceSpecifier: strings.get(specifierRef),
        baseFactor,
        isOverride: isOverride !== 0,
        overrideModId: strings.get(overrideModRef),
        ingredients,
        metadata,
    };
}

// ===== Recipes =====

export class RecipeIndex {
    constructor(indexBytes) {
        this.bytes = indexBytes;
        this.itemCount = readI32(indexBytes, 0);
    }

    get(itemIndex) {
        if (itemIndex < 0 || itemIndex >= this.itemCount) return {offset: 0xFFFFFFFF, count: 0};
        const p = 4 + itemIndex * 6;
        return {
            offset: readU32(this.bytes, p),
            count: readU16(this.bytes, p + 4),
        };
    }
}

export function readRecipesAt(recipeBytes, strings, offset, count) {
    if (offset === 0xFFFFFFFF || count === 0) return [];
    const b = recipeBytes;
    let p = offset;
    const out = new Array(count);
    for (let i = 0; i < count; i++) {
        out[i] = readOneRecipe(b, strings, {p});
    }
    return out;
}

function readOneRecipe(b, strings, cur) {
    let p = cur.p;
    const outItem = readI32(b, p);
    p += 4;
    const typeRef = readI32(b, p);
    p += 4;
    const catEnum = readU8(b, p);
    p += 1;
    const priority = readI32(b, p);
    p += 4;
    const resultCount = readI32(b, p);
    p += 4;
    const multiplier = readF64(b, p);
    p += 8;
    const flags = readU8(b, p);
    p += 1;
    const placeholderRef = readI32(b, p);
    p += 4;

    const ingSlotCount = readU8(b, p);
    p += 1;
    const ingredients = [];
    for (let s = 0; s < ingSlotCount; s++) {
        const vc = readU8(b, p);
        p += 1;
        const cnt = readI32(b, p);
        p += 4;
        const variants = [];
        for (let v = 0; v < vc; v++) {
            variants.push(readI32(b, p));
            p += 4;
        }
        ingredients.push({count: cnt, variants});
    }

    const fSlotCount = readU8(b, p);
    p += 1;
    const fluidIngredients = [];
    for (let s = 0; s < fSlotCount; s++) {
        const vc = readU8(b, p);
        p += 1;
        const amount = readI32(b, p);
        p += 4;
        const variants = [];
        for (let v = 0; v < vc; v++) {
            variants.push(readI32(b, p));
            p += 4;
        }
        fluidIngredients.push({amount, variants});
    }

    const cSlotCount = readU8(b, p);
    p += 1;
    const chemicalIngredients = [];
    for (let s = 0; s < cSlotCount; s++) {
        const idRef = readI32(b, p);
        p += 4;
        const amount = readI32(b, p);
        p += 4;
        chemicalIngredients.push({id: strings.get(idRef), amount});
    }

    const iOutCount = readU8(b, p);
    p += 1;
    const itemOutputs = [];
    for (let o = 0; o < iOutCount; o++) {
        const idx = readI32(b, p);
        p += 4;
        const cnt = readI32(b, p);
        p += 4;
        itemOutputs.push({itemIndex: idx, count: cnt});
    }

    const fOutCount = readU8(b, p);
    p += 1;
    const fluidOutputs = [];
    for (let o = 0; o < fOutCount; o++) {
        const idx = readI32(b, p);
        p += 4;
        const amount = readI32(b, p);
        p += 4;
        fluidOutputs.push({fluidIndex: idx, amount});
    }

    const coutCount = readU8(b, p);
    p += 1;
    const chemicalOutputs = [];
    for (let o = 0; o < coutCount; o++) {
        const idRef = readI32(b, p);
        p += 4;
        const amount = readI64(b, p);
        p += 8;
        chemicalOutputs.push({id: strings.get(idRef), amount: Number(amount)});
    }

    cur.p = p;
    return {
        outputItemIndex: outItem,
        recipeType: strings.get(typeRef),
        category: catEnum,
        priority,
        resultCount,
        recipeMultiplier: multiplier,
        flags,
        placeholderId: strings.get(placeholderRef),
        ingredients,
        fluidIngredients,
        chemicalIngredients,
        itemOutputs,
        fluidOutputs,
        chemicalOutputs,
    };
}

// ===== Usage section =====

export class UsageTable {
    constructor(bytes) {
        this.bytes = bytes;
        this.itemCount = readI32(bytes, 0);
        this.indexStart = 8;
        this.flatStart = 8 + this.itemCount * 8;
    }

    get(itemIndex) {
        if (itemIndex < 0 || itemIndex >= this.itemCount) return [];
        const p = this.indexStart + itemIndex * 8;
        const first = readI32(this.bytes, p);
        const count = readI32(this.bytes, p + 4);
        if (first === -1 || count === 0) return [];
        const out = new Array(count);
        const base = this.flatStart + first;
        for (let i = 0; i < count; i++) out[i] = readI32(this.bytes, base + i * 4);
        return out;
    }
}

// ===== Drops =====

export function readDropsForMob(dropsBytes, strings, offset, count) {
    if (offset === 0xFFFFFFFF || count === 0) return [];
    let p = 4 + offset;
    const out = [];
    for (let i = 0; i < count; i++) {
        out.push({
            itemIndex: readI32(dropsBytes, p), // p+0
            itemName: strings.get(readI32(dropsBytes, p + 4)),
            yieldPerKill: readF64(dropsBytes, p + 8),
            killMethod: strings.get(readI32(dropsBytes, p + 16)),
            itemId: strings.get(readI32(dropsBytes, p + 20)),
        });
        p += 24;
    }
    return out;
}

// ===== Meta =====

export function readMeta(metaBytes, strings) {
    const b = metaBytes;
    let p = 0;
    const modIdRef = readI32(b, p);
    p += 4;
    const modVerRef = readI32(b, p);
    p += 4;
    const serverNameRef = readI32(b, p);
    p += 4;
    const timestampStrRef = readI32(b, p);
    p += 4;
    const timestampMs = readI64(b, p);
    p += 8;
    const itemCount = readI32(b, p);
    p += 4;
    const mobCount = readI32(b, p);
    p += 4;
    const fluidCount = readI32(b, p);
    p += 4;
    const recipeCount = readI32(b, p);
    p += 4;
    const validItems = readI32(b, p);
    p += 4;
    const infiniteItems = readI32(b, p);
    p += 4;
    const catCount = readU8(b, p);
    p += 1;
    const categories = [];
    for (let i = 0; i < catCount; i++) {
        const nameRef = readI32(b, p);
        p += 4;
        const max = readF64(b, p);
        p += 8;
        categories.push({name: strings.get(nameRef), maxComplexity: max});
    }
    return {
        modId: strings.get(modIdRef),
        modVersion: strings.get(modVerRef),
        serverName: strings.get(serverNameRef),
        timestampStr: strings.get(timestampStrRef),
        timestampMs: Number(timestampMs),
        itemCount, mobCount, fluidCount, recipeCount, validItems, infiniteItems,
        categories,
    };
}

// ===== Categories section =====

export function readCategories(bytes, strings) {
    const b = bytes;
    let p = 0;
    const catCount = readU8(b, p);
    p += 1;
    const out = [];
    for (let c = 0; c < catCount; c++) {
        const nameRef = readI32(b, p);
        p += 4;
        const count = readI32(b, p);
        p += 4;
        const items = new Int32Array(count);
        for (let i = 0; i < count; i++) {
            items[i] = readI32(b, p);
            p += 4;
        }
        out.push({name: strings.get(nameRef), items});
    }
    return out;
}

// ===== High-level database facade =====

export class CabinDatabase {
    constructor(url) {
        this.file = new CabinFile(url);
        this.strings = null;
        this.items = null;
        this.mobs = null;
        this.meta = null;
        this.categories = null;
        this.recipeIndex = null;
        this._baseDataBytes = null;
        this._sourcesBytes = null;
        this._recipesBytes = null;
        this._dropsBytes = null;
        this._usage = null;
    }

    async open(options = {}) {
        await this.file.open(options);
        const [stringsBytes, itemsBytes, mobsBytes, metaBytes, catBytes, recipeIdxBytes]
            = await Promise.all([
            this.file.readSection(SEC.STRINGS),
            this.file.readSection(SEC.ITEMS),
            this.file.readSection(SEC.MOBS),
            this.file.readSection(SEC.META),
            this.file.readSection(SEC.CATEGORIES),
            this.file.readSection(SEC.IDX_RECIPES),
        ]);
        this.strings = new StringPool(stringsBytes);
        this.items = new ItemTable(itemsBytes, this.strings);
        this.mobs = new MobTable(mobsBytes, this.strings);
        this.meta = readMeta(metaBytes, this.strings);
        this.categories = readCategories(catBytes, this.strings);
        this.recipeIndex = new RecipeIndex(recipeIdxBytes);
    }

    async ensureBaseData() {
        if (this._baseDataBytes) return this._baseDataBytes;
        this._baseDataBytes = await this.file.readSection(SEC.BASE_DATA);
        return this._baseDataBytes;
    }

    async ensureSources() {
        if (this._sourcesBytes) return this._sourcesBytes;
        this._sourcesBytes = await this.file.readSection(SEC.SOURCES);
        return this._sourcesBytes;
    }

    async ensureRecipes() {
        if (this._recipesBytes) return this._recipesBytes;
        this._recipesBytes = await this.file.readSection(SEC.RECIPES);
        return this._recipesBytes;
    }

    async ensureDrops() {
        if (this._dropsBytes) return this._dropsBytes;
        this._dropsBytes = await this.file.readSection(SEC.DROPS);
        return this._dropsBytes;
    }

    async ensureUsage() {
        if (this._usage) return this._usage;
        const bytes = await this.file.readSection(SEC.USAGE);
        this._usage = new UsageTable(bytes);
        return this._usage;
    }

    async getItemSources(itemIndex) {
        await this.ensureSources();
        const item = this.items.get(itemIndex);
        if (!item) return [];
        return readSourcesForItem(this._sourcesBytes, this.strings, item.sourcesOffset, item.sourceCount);
    }

    async getItemBaseData(itemIndex) {
        await this.ensureBaseData();
        const item = this.items.get(itemIndex);
        if (!item) return null;
        return readBaseDataForItem(this._baseDataBytes, this.strings, item.baseDataOffset);
    }

    async getItemRecipes(itemIndex) {
        await this.ensureRecipes();
        const ref = this.recipeIndex.get(itemIndex);
        return readRecipesAt(this._recipesBytes, this.strings, ref.offset, ref.count);
    }

    async getItemUsage(itemIndex) {
        await this.ensureUsage();
        return this._usage.get(itemIndex);
    }

    async getMobDrops(mobIndex) {
        await this.ensureDrops();
        const mob = this.mobs.get(mobIndex);
        if (!mob) return [];
        return readDropsForMob(this._dropsBytes, this.strings, mob.dropsOffset, mob.dropCount);
    }
}
