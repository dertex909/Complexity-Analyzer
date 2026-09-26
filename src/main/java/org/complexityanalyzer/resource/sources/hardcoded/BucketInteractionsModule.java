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

package org.complexityanalyzer.resource.sources.hardcoded;

import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.graph.RecipeNode;
import org.complexityanalyzer.mixin.accessors.MobBucketItemAccessor;
import org.complexityanalyzer.resource.data.BaseResourceData;

public final class BucketInteractionsModule {

    private BucketInteractionsModule() {
    }

    public static void register(Level level, IHardcodedSourceRegistry registry) {
        var registryAccess = level.registryAccess();
        var fluidRegistry = registryAccess.registryOrThrow(Registries.FLUID);
        var itemRegistry = registryAccess.registryOrThrow(Registries.ITEM);
        registerFluidBucketRecipes(fluidRegistry);
        registerSolidAndMobBuckets(itemRegistry, registry);
    }

    private static void registerFluidBucketRecipes(Registry<Fluid> fluidRegistry) {
        var graph = AnalysisEngine.getInstance().getGraph();
        if (graph == null) return;
        var seenBuckets = new ReferenceOpenHashSet<Item>();

        for (var holder : fluidRegistry.holders().toList()) {
            var fluid = holder.value();
            var bucket = fluid.getBucket();
            if (bucket == Items.AIR || bucket == Items.BUCKET || !seenBuckets.add(bucket)) continue;
            var builder = new RecipeNode.Builder(bucket);
            builder.resultCount(1);
            builder.addIngredient(ObjectLists.singleton(new ItemStack(Items.BUCKET)), 1);
            builder.addFluidIngredient(ObjectLists.singleton(fluid), 1000);
            graph.addRecipe(builder.build());
        }
    }

    private static void registerSolidAndMobBuckets(Registry<Item> itemRegistry, IHardcodedSourceRegistry registry) {
        var engine = AnalysisEngine.getInstance();
        var mobProvider = engine.getMobPropertyProvider();

        for (var holder : itemRegistry.holders().toList()) {
            var item = holder.value();

            if (item instanceof SolidBucketItem solidBucket) {
                var ingredients = new Reference2DoubleOpenHashMap<Item>();
                ingredients.put(Items.BUCKET, 1.0);
                var blockItem = solidBucket.getBlock().asItem();
                if (blockItem != Items.AIR) ingredients.put(blockItem, 1.0);

                registry.register(item, new BaseResourceData.Builder(item)
                        .sourceType(BaseResourceData.ResourceSourceType.SPECIAL_ACTION)
                        .baseFactor(1.0)
                        .sourceItems(ingredients)
                        .details("Scooping " + solidBucket.getBlock().getName().getString() + " with empty bucket")
                        .build());
                continue;
            }

            if (item instanceof MobBucketItem mobBucket) {
                var entityType = ((MobBucketItemAccessor) mobBucket).getType();
                if (entityType == null) continue;
                var ingredients = new Reference2DoubleOpenHashMap<Item>();
                ingredients.put(Items.WATER_BUCKET, 1.0);

                double catchDifficulty = 5.0;
                if (mobProvider != null) {
                    double rarity = mobProvider.getRarity(entityType);
                    var props = mobProvider.getProperties(entityType);
                    double combatPower = props != null ? props.calculateCombatPower() : 10.0;
                    catchDifficulty = Math.max(1.0, combatPower * rarity * ComplexityConfig.MOB_DIFFICULTY_SCALER.get());
                }

                registry.register(item, new BaseResourceData.Builder(item)
                        .sourceType(BaseResourceData.ResourceSourceType.SPECIAL_ACTION)
                        .baseFactor(catchDifficulty)
                        .sourceItems(ingredients)
                        .details("Catching " + entityType.getDescription().getString() + " with water bucket (Bucketable)")
                        .build());
            }
        }
    }
}