package org.complexityanalyzer.export.cabin.builder;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.resource.SourceManager;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.util.FormatUtils;

public final class ItemSectionBuilder {

    private final SectionBuilderContext ctx;

    public ItemSectionBuilder(SectionBuilderContext ctx) {
        this.ctx = ctx;
    }

    private static Reference2ObjectMap<Item, Double> toRefMap(Reference2DoubleMap<Item> in) {
        var out = new Reference2ObjectOpenHashMap<Item, Double>(in.size());
        for (var e : Reference2DoubleMaps.fastIterable(in)) out.put(e.getKey(), e.getDoubleValue());
        return out;
    }

    public ItemSectionResult buildItemsSection(IHardcodedSourceRegistry hardcodedRegistry, BaseDataAccumulator baseAcc,
                                               SourcesAccumulator sourcesAcc) {
        int n = ctx.orderedItems().size();
        var buf = new LeBuf(CabinFormat.ITEM_RECORD_SIZE * n + 4);
        buf.i32(n);
        int validCount = 0;
        int infiniteCount = 0;
        for (int i = 0; i < n; i++) {
            var item = ctx.orderedItems().get(i);
            var id = GameRegistryManager.getItemId(item);
            String idStr = id != null ? id.toString() : "minecraft:air";
            String displayName = FormatUtils.safeDisplayName(item);

            var ic = ctx.engine().getComplexityResult(item);
            double complexity;
            int depth;
            int totalIngredients;
            ComplexityCategory category;
            int flags;
            int errorRef;
            if (ic == null) {
                complexity = -1.0;
                depth = 0;
                totalIngredients = 0;
                category = ComplexityCategory.UNCALCULABLE;
                flags = 0;
                errorRef = ctx.strings().intern("not analyzed");
            } else {
                complexity = ic.getComplexity();
                depth = ic.getDepth();
                totalIngredients = ic.getTotalIngredients();
                category = ic.getCategory();
                int f = 0;
                if (ic.hasRecipe()) f |= CabinFormat.ITEM_FLAG_HAS_RECIPE;
                if (ic.hasCycle()) f |= CabinFormat.ITEM_FLAG_HAS_CYCLE;
                if (ic.isValid()) f |= CabinFormat.ITEM_FLAG_IS_VALID;
                if (Double.isInfinite(complexity)) f |= CabinFormat.ITEM_FLAG_IS_INFINITE;
                if (!ic.hasRecipe()) f |= CabinFormat.ITEM_FLAG_NO_RECIPE_RESULT;
                flags = f;
                errorRef = ctx.strings().intern(ic.getErrorMessage() != null ? ic.getErrorMessage() : "");
                if (ic.isValid() && !Double.isInfinite(complexity)) validCount++;
                if (Double.isInfinite(complexity)) infiniteCount++;
            }
            if (hardcodedRegistry != null && hardcodedRegistry.isRegistered(item))
                flags |= CabinFormat.ITEM_FLAG_IS_HARDCODED;

            int usageCount = ctx.engine().getUsageCount(item);
            int idRef = ctx.strings().intern(idStr);
            int nameRef = ctx.strings().intern(displayName);
            int categoryRef = ctx.strings().intern(category.getDisplayName());

            var baseEntry = baseAcc.entries[i];
            var sourcesEntry = sourcesAcc.entries[i];

            int recordStart = buf.position();
            buf.i32(idRef);
            buf.i32(nameRef);
            buf.f64(complexity);
            buf.i32(depth);
            buf.i32(totalIngredients);
            buf.i32(usageCount);
            buf.i32(categoryRef);
            buf.i32(baseEntry != null ? baseEntry.offset : CabinFormat.NULL_OFFSET);
            buf.i32(sourcesEntry != null ? sourcesEntry.offset : CabinFormat.NULL_OFFSET);
            buf.u16(sourcesEntry != null ? sourcesEntry.count : 0);
            buf.u8(category.ordinal());
            buf.u8(flags & 0xFF);
            buf.i32(errorRef);
            int recordEnd = buf.position();
            int written = recordEnd - recordStart;
            if (written != CabinFormat.ITEM_RECORD_SIZE)
                throw new IllegalStateException("ITEM record size mismatch: " + written);
        }
        return new ItemSectionResult(buf.toByteArray(), validCount, infiniteCount);
    }

    public BaseDataAccumulator buildBaseData(SourceManager sourceManager) {
        var acc = new BaseDataAccumulator();
        int n = ctx.orderedItems().size();
        acc.entries = new BaseDataEntry[n];
        if (sourceManager == null) return acc;
        for (int i = 0; i < n; i++) {
            var item = ctx.orderedItems().get(i);
            var data = sourceManager.analyze(item);
            if (data == null) continue;
            int offset = acc.payload.position();
            writeBaseData(acc.payload, data);
            acc.entries[i] = new BaseDataEntry(offset);
        }
        return acc;
    }

    private void writeBaseData(LeBuf buf, BaseResourceData d) {
        buf.i32(ctx.strings().intern(d.getSourceType().getDisplayName()));
        buf.u8(d.getSourceType().ordinal());
        buf.i32(ctx.strings().intern(d.getDetails() != null ? d.getDetails() : ""));
        buf.i32(ctx.strings().intern(d.getSourceName() != null ? d.getSourceName() : ""));
        buf.i32(ctx.strings().intern(d.getSourceSpecifier() != null ? d.getSourceSpecifier() : ""));
        buf.f64(d.getBaseFactor());
        buf.u8(d.isOverride() ? 1 : 0);
        buf.i32(ctx.strings().intern(d.isOverride() ? d.getOverrideModId() : ""));
        var srcItems = d.getSourceItems();
        int siSize = srcItems.size();
        if (siSize > 0xFFFF) throw new IllegalStateException("Too many source items: " + siSize);
        buf.u16(siSize);
        for (var entry : Reference2ObjectMaps.fastIterable(toRefMap(srcItems))) {
            int idx = ctx.itemIndex().getInt(entry.getKey());
            buf.i32(idx >= 0 ? idx : -1);
            buf.f64(entry.getValue());
        }
        var meta = d.getMetadata();
        int metaSize = meta.size();
        if (metaSize > 0xFF) metaSize = 0xFF;
        buf.u8(metaSize);
        int written = 0;
        for (var entry : meta.entrySet()) {
            if (written >= metaSize) break;
            buf.i32(ctx.strings().intern(entry.getKey()));
            buf.i32(ctx.strings().intern(String.valueOf(entry.getValue())));
            written++;
        }
    }

    public SourcesAccumulator buildSources(SourceManager sourceManager) {
        var acc = new SourcesAccumulator();
        int n = ctx.orderedItems().size();
        acc.entries = new SourcesEntry[n];
        if (sourceManager == null) return acc;
        for (int i = 0; i < n; i++) {
            var item = ctx.orderedItems().get(i);
            var all = sourceManager.findAllSources(item);
            if (all.isEmpty()) continue;
            int offset = acc.payload.position();
            int count = Math.min(all.size(), 0xFFFF);
            for (int j = 0; j < count; j++) writeAltSource(acc.payload, all.get(j));
            acc.entries[i] = new SourcesEntry(offset, count);
        }
        return acc;
    }

    private void writeAltSource(LeBuf buf, BaseResourceData d) {
        buf.i32(ctx.strings().intern(d.getSourceType().getDisplayName()));
        buf.u8(d.getSourceType().ordinal());
        buf.i32(ctx.strings().intern(d.getDetails() != null ? d.getDetails() : ""));
        double base = d.getBaseFactor();
        double estimated = base;
        var src = d.getSourceItems();
        if (!src.isEmpty()) for (var e : Reference2DoubleMaps.fastIterable(src)) {
            var child = ctx.engine().getComplexityResult(e.getKey());
            if (child != null && child.isValid()) {
                estimated += child.getComplexity() * e.getDoubleValue();
            } else {
                estimated = Double.POSITIVE_INFINITY;
                break;
            }
        }
        buf.f64(base);
        buf.f64(estimated);
        int srcSize = src.size();
        if (srcSize > 0xFFFF) srcSize = 0xFFFF;
        buf.u16(srcSize);
        int written = 0;
        for (var e : Reference2DoubleMaps.fastIterable(src)) {
            if (written >= srcSize) break;
            int idx = ctx.itemIndex().getInt(e.getKey());
            buf.i32(idx >= 0 ? idx : -1);
            buf.f64(e.getDoubleValue());
            written++;
        }
    }

    public record ItemSectionResult(byte[] items, int validItems, int infiniteItems) {
    }

    public static final class BaseDataAccumulator {
        final LeBuf payload = new LeBuf(64 * 1024);
        BaseDataEntry[] entries;

        public byte[] bytes() {
            return payload.toByteArray();
        }
    }

    public static final class BaseDataEntry {
        final int offset;

        BaseDataEntry(int offset) {
            this.offset = offset;
        }
    }

    public static final class SourcesAccumulator {
        final LeBuf payload = new LeBuf(64 * 1024);
        SourcesEntry[] entries;

        public byte[] bytes() {
            return payload.toByteArray();
        }
    }

    public static final class SourcesEntry {
        final int offset;
        final int count;

        SourcesEntry(int offset, int count) {
            this.offset = offset;
            this.count = count;
        }
    }
}