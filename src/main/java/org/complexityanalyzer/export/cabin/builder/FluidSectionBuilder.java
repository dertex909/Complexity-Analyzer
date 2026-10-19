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

package org.complexityanalyzer.export.cabin.builder;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.export.cabin.util.CabinIndexUtils;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.util.FormatUtils;

import java.util.Arrays;

public final class FluidSectionBuilder {

    private final SectionBuilderContext ctx;

    public FluidSectionBuilder(SectionBuilderContext ctx) {
        this.ctx = ctx;
    }

    public byte[] buildFluidsSection() {
        var solverResult = ctx.engine().getSolverResult();
        int n = ctx.orderedFluids().size();
        var buf = new LeBuf(4 + n * CabinFormat.FLUID_RECORD_SIZE);
        buf.i32(n);
        for (int i = 0; i < n; i++) {
            var fluid = ctx.orderedFluids().get(i);
            var id = GameRegistryManager.getFluidId(fluid);
            String idStr = id != null ? id.toString() : "minecraft:empty";
            String displayName = FormatUtils.safeDisplayName(fluid);

            double complexity = -1.0;
            var category = ComplexityCategory.UNCALCULABLE;
            int flags = 0;
            int errorRef = ctx.strings().intern("not analyzed");

            if (solverResult != null) {
                if (solverResult.optimalFluidComplexities().containsKey(fluid)) {
                    double rawComplexity = solverResult.optimalFluidComplexities().getDouble(fluid);
                    complexity = rawComplexity;

                    if (Double.isInfinite(complexity) || complexity < 0) complexity = -1.0;

                    category = ComplexityCategory.fromComplexity(complexity);
                    flags |= CabinFormat.FLUID_FLAG_IS_VALID;
                    if (Double.isInfinite(rawComplexity)) flags |= CabinFormat.FLUID_FLAG_IS_INFINITE;

                    errorRef = ctx.strings().intern("");
                }
            }

            if (ctx.engine().getGraph() != null) {
                if (ctx.engine().getGraph().hasFluidRecipe(fluid)) {
                    flags |= CabinFormat.FLUID_FLAG_HAS_RECIPE;
                } else {
                    flags |= CabinFormat.FLUID_FLAG_NO_RECIPE_RESULT;
                }
            }

            if (fluid == Fluids.WATER || fluid == Fluids.LAVA) flags |= CabinFormat.FLUID_FLAG_IS_PROTECTED;

            int usageCount = ctx.engine().getGraph() != null ? ctx.engine().getGraph().getFluidUsageCount(fluid) : 0;
            int idRef = ctx.strings().intern(idStr);
            int nameRef = ctx.strings().intern(displayName);
            int categoryRef = ctx.strings().intern(category.getDisplayName());

            int recordStart = buf.position();
            buf.i32(idRef);
            buf.i32(nameRef);
            buf.f64(complexity);
            buf.i32(usageCount);
            buf.i32(categoryRef);
            buf.u8(category.ordinal());
            buf.u8(flags & 0xFF);
            buf.i32(errorRef);
            int recordEnd = buf.position();
            int written = recordEnd - recordStart;
            if (written != CabinFormat.FLUID_RECORD_SIZE) {
                throw new IllegalStateException("FLUID record size mismatch: " + written);
            }
        }
        return buf.toByteArray();
    }

    public FluidRecipesResult buildFluidRecipes(RecipeGraph graph) {
        int n = ctx.orderedFluids().size();
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        Arrays.fill(firstOffset, CabinFormat.NULL_OFFSET);

        if (graph == null) return new FluidRecipesResult(new byte[]{0, 0, 0, 0},
                CabinIndexUtils.encodeRecipeOutputIndex(firstOffset, count), 0);

        var out = new LeBuf(64 * 1024);
        out.i32(0);
        int totalRecipes = 0;

        var registry = ctx.engine().getMachineRegistry();
        var solverResult = ctx.engine().getSolverResult();

        for (int i = 0; i < n; i++) {
            var fluid = ctx.orderedFluids().get(i);
            var recipes = graph.getFluidRecipes(fluid);
            if (recipes.isEmpty()) continue;

            if (solverResult != null) {
                var optimalFluidRecipe = solverResult.optimalFluidRecipes().get(fluid);
                if (optimalFluidRecipe != null) {
                    var mutableRecipes = new ObjectArrayList<>(recipes);
                    RecipeSectionBuilder.sortOptimalFirst(mutableRecipes, optimalFluidRecipe);
                    recipes = mutableRecipes;
                }
            }

            firstOffset[i] = out.position();
            int written = RecipeSectionBuilder.writeRecipeGroup(out, ctx, registry, i, recipes);
            count[i] = written;
            totalRecipes += written;
        }
        out.putI32At(0, totalRecipes);

        byte[] outputIndex = CabinIndexUtils.encodeRecipeOutputIndex(firstOffset, count);
        return new FluidRecipesResult(out.toByteArray(), outputIndex, totalRecipes);
    }

    public byte[] buildFluidUsage(RecipeGraph graph) {
        return CabinIndexUtils.buildUsageSection(ctx.orderedFluids(), ctx.itemIndex(), graph,
                RecipeGraph::getItemsUsingFluid, 16 * 1024);
    }

    public byte[] buildFluidHashIndex() {
        return CabinIndexUtils.buildHashIndex(ctx.orderedFluids(), fluid -> {
            var id = GameRegistryManager.getFluidId(fluid);
            return id != null ? id.toString() : "";
        });
    }

    public record FluidRecipesResult(byte[] payload, byte[] outputIndex, int recipeCount) {
    }
}