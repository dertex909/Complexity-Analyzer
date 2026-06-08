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

package org.complexityanalyzer.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.api.ComplexityAnalyzerAPI;
import org.complexityanalyzer.api.ComplexityQuery;
import org.complexityanalyzer.api.GeoData;
import org.complexityanalyzer.api.IBossRegistry;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.api.IRenewableRegistry;
import org.complexityanalyzer.api.MachineData;
import org.complexityanalyzer.api.MobData;
import org.complexityanalyzer.api.MobInfo;
import org.complexityanalyzer.api.RecipeData;
import org.complexityanalyzer.analyzer.resource.sources.HardcodedSourcesProvider;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

public final class ComplexityAnalyzerAPIImpl implements ComplexityAnalyzerAPI {

    private final AnalysisEngine engine;

    private final ComplexityQuery items = new Items();
    private final RecipeData recipes = new Recipes();
    private final MobData mobs = new Mobs();
    private final GeoData geo = new Geo();
    private final MachineData machines = new Machines();

    public ComplexityAnalyzerAPIImpl(AnalysisEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean isReady() {
        return engine.isReady();
    }

    @Override
    public ComplexityQuery items() {
        return items;
    }

    @Override
    public RecipeData recipes() {
        return recipes;
    }

    @Override
    public MobData mobs() {
        return mobs;
    }

    @Override
    public GeoData geo() {
        return geo;
    }

    @Override
    public MachineData machines() {
        return machines;
    }

    @Override
    public IBossRegistry bosses() {
        return engine.getMobPropertyProvider();
    }

    @Override
    public IHardcodedSourceRegistry hardcodedSources() {
        return HardcodedSourcesProvider.getRegistry();
    }

    @Override
    public IRenewableRegistry renewables() {
        return engine.getMobPropertyProvider();
    }

    private final class Items implements ComplexityQuery {
        @Override
        public double getComplexity(Item item) {
            return engine.getComplexity(item);
        }

        @Override
        public double getComplexity(ItemStack stack) {
            return stack == null || stack.isEmpty() ? -1.0 : engine.getComplexity(stack.getItem());
        }

        @Override
        public ComplexityCategory getCategory(Item item) {
            var detailed = engine.getComplexityResult(item);
            return detailed != null ? detailed.getCategory() : ComplexityCategory.UNCALCULABLE;
        }

        @Override
        public Optional<ItemComplexity> getDetailed(Item item) {
            return Optional.ofNullable(engine.getComplexityResult(item));
        }

        @Override
        public boolean isAnalyzed(Item item) {
            var r = engine.getComplexityResult(item);
            return r != null && r.isValid();
        }

        @Override
        public Collection<Item> getAnalyzedItems() {
            var graph = engine.getGraph();
            return graph != null && engine.isReady() ? Collections.unmodifiableCollection(graph.getCorpus()) : List.of();
        }

        @Override
        public int count() {
            var graph = engine.getGraph();
            return graph != null && engine.isReady() ? graph.getCorpus().size() : 0;
        }
    }

    private final class Recipes implements RecipeData {
        @Override
        public boolean hasRecipe(Item item) {
            return engine.hasRecipe(item);
        }

        @Override
        public List<RecipeNode> getRecipes(Item item) {
            var graph = engine.getGraph();
            return graph != null && engine.isReady() ? List.copyOf(graph.getRecipes(item)) : List.of();
        }

        @Override
        public Optional<RecipeNode> getBestRecipe(Item item) {
            var graph = engine.getGraph();
            return graph != null && engine.isReady() ? Optional.ofNullable(graph.getBestRecipe(item)) : Optional.empty();
        }

        @Override
        public int getUsageCount(Item item) {
            return engine.getUsageCount(item);
        }

        @Override
        public Set<Item> getItemsUsing(Item ingredient) {
            var graph = engine.getGraph();
            return graph != null && engine.isReady() ? Set.copyOf(graph.getItemsUsingIngredient(ingredient)) : Set.of();
        }

        @Override
        public List<BaseResourceData> getBaseSources(Item item) {
            return List.copyOf(engine.findAllSourcesForItem(item));
        }

        @Override
        public Optional<BaseResourceData> getBestBaseSource(Item item) {
            return Optional.ofNullable(engine.getBaseResourceData(item));
        }

        @Override
        public int totalRecipeCount() {
            var graph = engine.getGraph();
            return graph != null && engine.isReady() ? graph.getTotalRecipeCount() : 0;
        }
    }

    private final class Mobs implements MobData {
        @Override
        public double getRarity(EntityType<?> type) {
            var p = engine.getMobPropertyProvider();
            return p != null ? p.getRarity(type) : Double.POSITIVE_INFINITY;
        }

        @Override
        public double getCombatPower(EntityType<?> type) {
            var p = engine.getMobPropertyProvider();
            if (p == null) return 0.0;
            var props = p.getProperties(type);
            return props != null ? props.calculateCombatPower() : 0.0;
        }

        @Override
        public boolean isBoss(EntityType<?> type) {
            var p = engine.getMobPropertyProvider();
            return p != null && p.isBoss(type);
        }

        @Override
        public boolean isMiniBoss(EntityType<?> type) {
            var p = engine.getMobPropertyProvider();
            return p != null && p.isMiniBoss(type);
        }

        @Override
        public boolean isRenewable(EntityType<?> type) {
            var p = engine.getMobPropertyProvider();
            return p != null && p.isRenewable(type);
        }

        @Override
        public Optional<MobInfo> getInfo(EntityType<?> type) {
            var p = engine.getMobPropertyProvider();
            if (p == null) return Optional.empty();
            var props = p.getProperties(type);
            if (props == null) return Optional.empty();
            return Optional.of(new MobInfo(
                    type,
                    props.maxHealth(),
                    props.attackDamage(),
                    props.armor(),
                    props.calculateCombatPower(),
                    p.getRarity(type),
                    props.classification(),
                    p.isBoss(type),
                    p.isMiniBoss(type),
                    p.isRenewable(type)
            ));
        }
    }

    private final class Geo implements GeoData {
        @Override
        public boolean isScanned() {
            var db = engine.getGeoDatabase();
            return db != null && db.isLoaded();
        }

        @Override
        public Set<ResourceLocation> getScannedDimensions() {
            var db = engine.getGeoDatabase();
            if (db == null || !db.isLoaded()) return Set.of();
            return Set.copyOf(db.getAllDimensionData().keySet());
        }

        @Override
        public Set<ResourceLocation> getScannedBiomes(ResourceLocation dimension) {
            var db = engine.getGeoDatabase();
            if (db == null || !db.isLoaded()) return Set.of();
            var biomes = db.getAllDimensionData().get(dimension);
            return biomes != null ? Set.copyOf(biomes.keySet()) : Set.of();
        }

        @Override
        public OptionalDouble getBlockShare(ResourceLocation dimension, ResourceLocation biome, Block block) {
            var db = engine.getGeoDatabase();
            if (db == null || !db.isLoaded()) return OptionalDouble.empty();
            var biomes = db.getAllDimensionData().get(dimension);
            if (biomes == null) return OptionalDouble.empty();
            var data = biomes.get(biome);
            if (data == null) return OptionalDouble.empty();
            long total = data.getTotalBlocks();
            if (total <= 0) return OptionalDouble.empty();
            return OptionalDouble.of((double) data.getBlockCount(block) / total);
        }

        @Override
        public OptionalDouble getBestBlockShare(Block block) {
            var db = engine.getGeoDatabase();
            if (db == null || !db.isLoaded()) return OptionalDouble.empty();
            double best = -1.0;
            for (var dim : db.getAllDimensionData().values()) {
                for (var data : dim.values()) {
                    long total = data.getTotalBlocks();
                    if (total <= 0) continue;
                    double share = (double) data.getBlockCount(block) / total;
                    if (share > best) best = share;
                }
            }
            return best >= 0 ? OptionalDouble.of(best) : OptionalDouble.empty();
        }
    }

    private final class Machines implements MachineData {
        @Override
        public Optional<Item> getMachineForRecipe(RecipeType<?> type) {
            var reg = engine.getMachineRegistry();
            return reg != null ? Optional.ofNullable(reg.getMachineForRecipe(type)) : Optional.empty();
        }

        @Override
        public List<Item> getMachinesForRecipe(RecipeType<?> type) {
            var reg = engine.getMachineRegistry();
            return reg != null ? List.copyOf(reg.getMachinesForRecipe(type)) : List.of();
        }
    }
}