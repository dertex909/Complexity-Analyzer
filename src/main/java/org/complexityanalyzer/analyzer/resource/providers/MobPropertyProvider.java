/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

public class MobPropertyProvider {
    private final Reference2ObjectMap<EntityType<?>, MobProperties> propertiesCache = new Reference2ObjectOpenHashMap<>();
    private MobRarityCalculator rarityCalculator;

    private static final Reference2ObjectMap<EntityType<?>, MobProperties> MANUAL_OVERRIDES = new Reference2ObjectOpenHashMap<>();

    static {
        MANUAL_OVERRIDES.put(EntityType.SHULKER, new MobProperties(30.0, 0.1, 20.0, MobCategory.MONSTER));
        MANUAL_OVERRIDES.put(EntityType.WITHER, new MobProperties(300.0, 8.0, 4.0, MobCategory.MONSTER));
        MANUAL_OVERRIDES.put(EntityType.ENDER_DRAGON, new MobProperties(200.0, 10.0, 0.0, MobCategory.MONSTER));
        MANUAL_OVERRIDES.put(EntityType.WARDEN, new MobProperties(500.0, 30.0, 0.0, MobCategory.MONSTER));
        MANUAL_OVERRIDES.put(EntityType.BLAZE, new MobProperties(20.0, 6.0, 0.0, MobCategory.MONSTER));
        MANUAL_OVERRIDES.put(EntityType.GHAST, new MobProperties(10.0, 12.0, 0.0, MobCategory.MONSTER));
        MANUAL_OVERRIDES.put(EntityType.CREEPER, new MobProperties(20.0, 0.1, 0.0, MobCategory.MONSTER));
    }

    public void initialize() {
        ComplexityAnalyzer.LOGGER.info("Initializing MobPropertyProvider...");

        int failedCount = 0;

        for (var type : GameRegistryManager.getAllEntityTypes()) {
            if (MANUAL_OVERRIDES.containsKey(type)) continue;
            if (type.getCategory() == MobCategory.MISC) continue;

            try {
                @SuppressWarnings("unchecked")
                var livingType = (EntityType<? extends LivingEntity>) type;

                if (!DefaultAttributes.hasSupplier(livingType)) {
                    failedCount++;
                    continue;
                }

                var attributes = DefaultAttributes.getSupplier(livingType);

                var maxHealth = attributes.getBaseValue(Attributes.MAX_HEALTH);
                var attackDamage = attributes.hasAttribute(Attributes.ATTACK_DAMAGE)
                        ? attributes.getBaseValue(Attributes.ATTACK_DAMAGE) : 0.0;
                var armor = attributes.hasAttribute(Attributes.ARMOR)
                        ? attributes.getBaseValue(Attributes.ARMOR) : 0.0;

                attackDamage = Math.max(attackDamage, 0.1);
                MobCategory classification = type.getCategory();

                propertiesCache.put(type, new MobProperties(
                        maxHealth,
                        attackDamage,
                        armor,
                        classification
                ));

            } catch (ClassCastException e) {
                failedCount++;
            } catch (Exception e) {
                var id = GameRegistryManager.getEntityTypeId(type);
                ComplexityAnalyzer.LOGGER.warn("Could not analyze entity type: {}", id);
                failedCount++;
            }
        }

        ComplexityAnalyzer.LOGGER.debug("MobPropertyProvider initialized:");
        ComplexityAnalyzer.LOGGER.debug("  ✓ Manual overrides: {}", MANUAL_OVERRIDES.size());
        ComplexityAnalyzer.LOGGER.debug("  ✓ Total entities: {}", propertiesCache.size());
        ComplexityAnalyzer.LOGGER.debug("  ⚠ Failed/Skipped: {}", failedCount);
    }

    @Nullable
    public MobProperties getProperties(EntityType<?> type) {
        if (MANUAL_OVERRIDES.containsKey(type)) return MANUAL_OVERRIDES.get(type);

        var cached = propertiesCache.get(type);
        if (cached != null) return cached;

        var id = GameRegistryManager.getEntityTypeId(type);

        try {
            @SuppressWarnings("unchecked")
            var livingType = (EntityType<? extends LivingEntity>) type;

            if (!DefaultAttributes.hasSupplier(livingType)) return null;

            var attributes = DefaultAttributes.getSupplier(livingType);

            var maxHealth = attributes.hasAttribute(Attributes.MAX_HEALTH)
                    ? attributes.getBaseValue(Attributes.MAX_HEALTH) : 20.0;

            var attackDamage = attributes.hasAttribute(Attributes.ATTACK_DAMAGE)
                    ? attributes.getBaseValue(Attributes.ATTACK_DAMAGE) : 0.1;

            var armor = attributes.hasAttribute(Attributes.ARMOR)
                    ? attributes.getBaseValue(Attributes.ARMOR) : 0.0;

            var classification = type.getCategory();

            var props = new MobProperties(maxHealth, attackDamage, armor, classification);
            propertiesCache.put(type, props);

            return props;

        } catch (ClassCastException e) {
            ComplexityAnalyzer.LOGGER.debug("Entity {} cannot be cast to LivingEntity type", id);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.warn("Error loading properties for {}: {}", id, e.getMessage());
        }

        return null;
    }

    public void setRarityCalculator(MobRarityCalculator calculator) {
        this.rarityCalculator = calculator;
    }

    public double getRarity(EntityType<?> type) {
        if (rarityCalculator == null) {
            ComplexityAnalyzer.LOGGER.warn("RarityCalculator not set! Returning default rarity.");
            return 1.0;
        }
        return rarityCalculator.calculateRarity(type);
    }

    public boolean isBoss(EntityType<?> type) {
        if (rarityCalculator == null) {
            ComplexityAnalyzer.LOGGER.warn("RarityCalculator not set! Cannot determine boss status.");
            return false;
        }
        return rarityCalculator.isBoss(type);
    }

    public boolean isMiniBoss(EntityType<?> type) {
        if (rarityCalculator == null) {
            ComplexityAnalyzer.LOGGER.warn("RarityCalculator not set! Cannot determine mini-boss status.");
            return false;
        }
        return rarityCalculator.isMiniBoss(type);
    }

    public record MobProperties(
            double maxHealth,
            double attackDamage,
            double armor,
            MobCategory classification
    ) {
        public double calculateSurvivability() {
            return maxHealth * (1 + armor / 5.0);
        }

        public double calculateThreat() {
            return 1 + Math.log1p(attackDamage);
        }

        public double calculateCombatPower() {
            return calculateSurvivability() * calculateThreat();
        }
    }
}