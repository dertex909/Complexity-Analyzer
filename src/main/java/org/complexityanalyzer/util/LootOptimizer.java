package org.complexityanalyzer.util;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.common.loot.LootTableIdCondition;
import org.complexityanalyzer.mixin.Accessors.CompositeLootItemConditionAccessor;
import org.complexityanalyzer.mixin.Accessors.LootModifierAccessor;
import org.complexityanalyzer.mixin.Accessors.LootTableIdConditionAccessor;

import java.util.Collection;

public class LootOptimizer {

    private static volatile ObjectOpenHashSet<ResourceLocation> targetedTables = new ObjectOpenHashSet<>();
    private static volatile boolean hasAnyModifiers = false;
    private static volatile boolean hasWildcardModifiers = false;

    public static boolean hasModifiers(ResourceLocation lootTableId) {
        if (!hasAnyModifiers || lootTableId == null) return false;
        if (hasWildcardModifiers) return true;
        return targetedTables.contains(lootTableId);
    }

    public static void rebuildIndex(Collection<IGlobalLootModifier> modifiers) {
        var newTargetedTables = new ObjectOpenHashSet<ResourceLocation>();
        boolean wildcardFound = false;

        if (modifiers == null || modifiers.isEmpty()) {
            hasAnyModifiers = false;
            hasWildcardModifiers = false;
            targetedTables = newTargetedTables;
            return;
        }

        for (var modifier : modifiers) {
            if (!(modifier instanceof LootModifier standardModifier)) {
                wildcardFound = true;
                continue;
            }

            var tablesInModifier = new ObjectOpenHashSet<ResourceLocation>();
            var conditions = ((LootModifierAccessor) standardModifier).complexity$getConditions();
            boolean isTargeted = scanAndConditions(conditions, tablesInModifier);

            if (!isTargeted) {
                wildcardFound = true;
            } else {
                newTargetedTables.addAll(tablesInModifier);
            }
        }

        targetedTables = newTargetedTables;
        hasWildcardModifiers = wildcardFound;
        hasAnyModifiers = true;
    }

    private static boolean scanAndConditions(LootItemCondition[] conditions, ObjectOpenHashSet<ResourceLocation> collectedTables) {
        if (conditions == null || conditions.length == 0) return false;
        boolean foundTableRestriction = false;

        for (var condition : conditions) {
            if (condition instanceof InvertedLootItemCondition) continue;

            if (condition instanceof LootTableIdCondition tableCondition) {
                var id = ((LootTableIdConditionAccessor) tableCondition).complexity$getTargetLootTableId();
                if (id != null) {
                    collectedTables.add(id);
                    foundTableRestriction = true;
                }
            } else if (condition instanceof AnyOfCondition anyOf) {
                var terms = ((CompositeLootItemConditionAccessor) anyOf).complexity$getTerms();
                if (terms != null && scanOrConditions(terms.toArray(new LootItemCondition[0]), collectedTables)) {
                    foundTableRestriction = true;
                }
            } else if (condition instanceof AllOfCondition allOf) {
                var terms = ((CompositeLootItemConditionAccessor) allOf).complexity$getTerms();
                if (terms != null && scanAndConditions(terms.toArray(new LootItemCondition[0]), collectedTables)) {
                    foundTableRestriction = true;
                }
            } else if (condition instanceof CompositeLootItemConditionAccessor composite) {
                var terms = composite.complexity$getTerms();
                if (terms != null && scanAndConditions(terms.toArray(new LootItemCondition[0]), collectedTables)) {
                    foundTableRestriction = true;
                }
            }
        }

        return foundTableRestriction;
    }

    private static boolean scanOrConditions(LootItemCondition[] terms, ObjectOpenHashSet<ResourceLocation> collectedTables) {
        if (terms == null || terms.length == 0) return false;
        var branchTables = new ObjectOpenHashSet<ResourceLocation>();

        for (var term : terms) {
            var singleBranchTables = new ObjectOpenHashSet<ResourceLocation>();
            if (!scanAndConditions(new LootItemCondition[]{term}, singleBranchTables)) return false;
            branchTables.addAll(singleBranchTables);
        }

        collectedTables.addAll(branchTables);
        return true;
    }

    public static void clear() {
        targetedTables = new ObjectOpenHashSet<>();
        hasAnyModifiers = false;
        hasWildcardModifiers = false;
    }
}