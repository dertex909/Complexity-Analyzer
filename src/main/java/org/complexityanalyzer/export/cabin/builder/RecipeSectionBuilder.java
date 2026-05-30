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

package org.complexityanalyzer.export.cabin.builder;

import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.Arrays;

import static org.complexityanalyzer.export.cabin.api.CabinFormat.NULL_OFFSET;

public final class RecipeSectionBuilder {

    private final SectionBuilderContext ctx;

    public RecipeSectionBuilder(SectionBuilderContext ctx) {
        this.ctx = ctx;
    }

    public static final class RecipesResult {
        public final byte[] payload;
        public final byte[] outputIndex;
        public final int recipeCount;

        public RecipesResult(byte[] payload, byte[] outputIndex, int recipeCount) {
            this.payload = payload;
            this.outputIndex = outputIndex;
            this.recipeCount = recipeCount;
        }
    }

    public RecipesResult buildRecipes(RecipeGraph graph) {
        int n = ctx.orderedItems().size();
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        Arrays.fill(firstOffset, NULL_OFFSET);

        if (graph == null)
            return new RecipesResult(new byte[]{0, 0, 0, 0}, encodeRecipeOutputIndex(firstOffset, count), 0);

        var out = new LeBuf(256 * 1024);
        out.i32(0);
        int totalRecipes = 0;

        var registry = ctx.engine().getMachineRegistry();

        for (int i = 0; i < n; i++) {
            var item = ctx.orderedItems().get(i);
            var recipes = graph.getRecipes(item);
            if (recipes.isEmpty()) continue;
            firstOffset[i] = out.position();
            int written = 0;
            for (var r : recipes) {
                var recipeType = r.getRecipeType();
                var machineItems = (registry != null && recipeType != null) ? registry.getMachinesForRecipe(recipeType) : null;
                if (machineItems != null && !machineItems.isEmpty()) {
                    for (var machineItem : machineItems) {
                        int machineItemIdx = ctx.itemIndex().getInt(machineItem);
                        if (machineItemIdx >= 0) {
                            writeRecipe(out, ctx, i, r, machineItemIdx);
                            written++;
                            if (written == 0xFFFF) break;
                        }
                    }
                } else {
                    writeRecipe(out, ctx, i, r, -1);
                    written++;
                }
                if (written == 0xFFFF) break;
            }
            count[i] = written;
            totalRecipes += written;
        }
        out.putI32At(0, totalRecipes);

        byte[] outputIndex = encodeRecipeOutputIndex(firstOffset, count);
        return new RecipesResult(out.toByteArray(), outputIndex, totalRecipes);
    }

    public static void writeRecipe(LeBuf buf, SectionBuilderContext ctx, int outputItemIndex, RecipeNode r, int machineItemIdx) {
        buf.i32(outputItemIndex);
        var rt = r.getRecipeType();
        String rtStr = rt != null ? rt.toString() : "minecraft:custom";
        buf.i32(ctx.strings().intern(rtStr));
        var cat = r.getCategory();
        buf.u8(cat != null ? cat.ordinal() : 0);
        buf.i32(r.getPriority());
        buf.i32(r.getResultCount());
        buf.f64(r.getRecipeMultiplier());
        int flags = 0;
        if (r.isPlaceholder()) flags |= 0x01;
        if (r.hasFluidIngredients()) flags |= 0x02;
        if (r.isBaseRecipe()) flags |= 0x04;
        buf.u8(flags);
        buf.i32(ctx.strings().intern(r.getPlaceholderId() != null ? r.getPlaceholderId() : ""));

        buf.i32(machineItemIdx);

        var ings = r.getIngredients();
        buf.u8(Math.min(ings.size(), 0xFF));
        for (int s = 0; s < Math.min(ings.size(), 0xFF); s++) {
            var slot = ings.get(s);
            var variants = slot.getVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.getCount());
            for (int v = 0; v < vc; v++) {
                int vi = ctx.itemIndex().getInt(variants.get(v));
                buf.i32(vi);
            }
        }

        var fings = r.getFluidIngredients();
        buf.u8(Math.min(fings.size(), 0xFF));
        for (int s = 0; s < Math.min(fings.size(), 0xFF); s++) {
            var slot = fings.get(s);
            var variants = slot.getFluidVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.getAmount());
            for (int v = 0; v < vc; v++) {
                var fluid = variants.get(v);
                int fi = ctx.fluidIndex().getInt(fluid);
                buf.i32(fi);
            }
        }

        var chems = r.getChemicalIngredients();
        buf.u8(Math.min(chems.size(), 0xFF));
        for (int c = 0; c < Math.min(chems.size(), 0xFF); c++) {
            var ci = chems.get(c);
            buf.i32(ctx.strings().intern(ci.id() != null ? ci.id().toString() : ""));
            buf.i32(ci.amount());
        }

        var iouts = r.getItemOutputs();
        buf.u8(Math.min(iouts.size(), 0xFF));
        for (int o = 0; o < Math.min(iouts.size(), 0xFF); o++) {
            var stack = iouts.get(o);
            int idx = ctx.itemIndex().getInt(stack.getItem());
            buf.i32(idx);
            buf.i32(stack.getCount());
        }

        var fouts = r.getFluidOutputs();
        buf.u8(Math.min(fouts.size(), 0xFF));
        for (int o = 0; o < Math.min(fouts.size(), 0xFF); o++) {
            var fs = fouts.get(o);
            int fi = ctx.fluidIndex().getInt(fs.getFluid());
            buf.i32(fi);
            buf.i32(fs.getAmount());
        }

        var cOuts = r.getChemicalOutputs();
        buf.u8(Math.min(cOuts.size(), 0xFF));
        for (int o = 0; o < Math.min(cOuts.size(), 0xFF); o++) {
            var co = cOuts.get(o);
            String cid = co.id() != null ? co.id().toString() : "";
            buf.i32(ctx.strings().intern(cid));
            buf.i64(co.amount());
        }
    }

    private byte[] encodeRecipeOutputIndex(int[] firstOffset, int[] count) {
        int n = firstOffset.length;
        var buf = new LeBuf(4 + n * 6);
        buf.i32(n);
        for (int i = 0; i < n; i++) {
            buf.i32(firstOffset[i]);
            buf.u16(Math.min(count[i], 0xFFFF));
        }
        return buf.toByteArray();
    }

    public byte[] buildUsage(RecipeGraph graph) {
        int n = ctx.orderedItems().size();
        if (graph == null) {
            var empty = new LeBuf(8);
            empty.i32(n);
            empty.i32(0);
            return empty.toByteArray();
        }
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        var flat = new LeBuf(64 * 1024);
        for (int i = 0; i < n; i++) {
            var item = ctx.orderedItems().get(i);
            var users = graph.getItemsUsingIngredient(item);
            if (users.isEmpty()) {
                firstOffset[i] = NULL_OFFSET;
                continue;
            }
            firstOffset[i] = flat.position();
            int written = 0;
            for (var u : users) {
                int ui = ctx.itemIndex().getInt(u);
                if (ui < 0) continue;
                flat.i32(ui);
                written++;
            }
            count[i] = written;
        }
        var out = new LeBuf(8 + n * 8 + flat.size());
        out.i32(n);
        out.i32(flat.size());
        for (int i = 0; i < n; i++) {
            out.i32(firstOffset[i]);
            out.i32(count[i]);
        }
        out.bytes(flat.array(), 0, flat.size());
        return out.toByteArray();
    }
}