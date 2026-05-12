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

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.graph.FluidIngredientSlot;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

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
        java.util.Arrays.fill(firstOffset, CabinFormat.NULL_OFFSET);

        if (graph == null)
            return new RecipesResult(new byte[]{0, 0, 0, 0}, encodeRecipeOutputIndex(firstOffset, count), 0);

        LeBuf out = new LeBuf(256 * 1024);
        out.i32(0);
        int totalRecipes = 0;

        for (int i = 0; i < n; i++) {
            Item item = ctx.orderedItems().get(i);
            ObjectList<RecipeNode> recipes = graph.getRecipes(item);
            if (recipes.isEmpty()) continue;
            firstOffset[i] = out.position();
            int written = 0;
            for (RecipeNode r : recipes) {
                writeRecipe(out, i, r);
                written++;
                if (written == 0xFFFF) break;
            }
            count[i] = written;
            totalRecipes += written;
        }
        out.putI32At(0, totalRecipes);

        byte[] outputIndex = encodeRecipeOutputIndex(firstOffset, count);
        return new RecipesResult(out.toByteArray(), outputIndex, totalRecipes);
    }

    private void writeRecipe(LeBuf buf, int outputItemIndex, RecipeNode r) {
        buf.i32(outputItemIndex);
        var rt = r.getRecipeType();
        String rtStr = rt != null ? rt.toString() : "minecraft:custom";
        buf.i32(ctx.strings().intern(rtStr));
        RecipeCategory cat = r.getCategory();
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

        var ings = r.getIngredients();
        buf.u8(Math.min(ings.size(), 0xFF));
        for (int s = 0; s < Math.min(ings.size(), 0xFF); s++) {
            IngredientSlot slot = ings.get(s);
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
            FluidIngredientSlot slot = fings.get(s);
            var variants = slot.getFluidVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.getAmount());
            for (int v = 0; v < vc; v++) {
                Fluid fluid = variants.get(v);
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
        LeBuf buf = new LeBuf(4 + n * 6);
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
            LeBuf empty = new LeBuf(8);
            empty.i32(n);
            empty.i32(0);
            return empty.toByteArray();
        }
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        LeBuf flat = new LeBuf(64 * 1024);
        for (int i = 0; i < n; i++) {
            Item item = ctx.orderedItems().get(i);
            ReferenceSet<Item> users = graph.getItemsUsingIngredient(item);
            if (users.isEmpty()) {
                firstOffset[i] = CabinFormat.NULL_OFFSET;
                continue;
            }
            firstOffset[i] = flat.position();
            int written = 0;
            for (Item u : users) {
                int ui = ctx.itemIndex().getInt(u);
                if (ui < 0) continue;
                flat.i32(ui);
                written++;
            }
            count[i] = written;
        }
        LeBuf out = new LeBuf(8 + n * 8 + flat.size());
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