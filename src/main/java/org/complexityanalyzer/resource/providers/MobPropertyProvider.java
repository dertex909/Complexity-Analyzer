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

package org.complexityanalyzer.resource.providers;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.api.IBossRegistry;
import org.complexityanalyzer.api.IRenewableRegistry;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public class MobPropertyProvider implements IBossRegistry, IRenewableRegistry {
    public static final double DEFAULT_MAX_HEALTH = 20.0;
    public static final double ARMOR_COEFFICIENT = 0.05;

    public static final double BASE_MIN_RARITY = 1.0;
    public static final double MINI_BOSS_RARITY_SCALE = 10.0;

    public static final double MONSTER_BASE_RARITY = 1.5;
    public static final double CREATURE_BASE_RARITY = 1.0;
    public static final double OTHER_BASE_RARITY = 1.0;
    public static final double AMBIENT_BASE_RARITY = 0.8;
    public static final double WATER_CREATURE_BASE_RARITY = 1.25;

    public static final double POWER_TO_RARITY_COEFFICIENT = 0.05;

    private final ConcurrentHashMap<EntityType<?>, MobProperties> propertiesCache = new ConcurrentHashMap<>(256);
    private final ConcurrentHashMap.KeySetView<EntityType<?>, Boolean> renewableTypes = ConcurrentHashMap.newKeySet(64);
    private final ConcurrentHashMap<EntityType<?>, BossType> registeredBosses = new ConcurrentHashMap<>(32);

    public MobPropertyProvider() {
        registerBoss(EntityType.WITHER, BossType.BOSS);
        registerBoss(EntityType.ENDER_DRAGON, BossType.BOSS);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static AttributeSupplier getSupplier(EntityType<?> type) {
        var livingType = (EntityType<? extends LivingEntity>) type;
        return DefaultAttributes.hasSupplier(livingType) ? DefaultAttributes.getSupplier(livingType) : null;
    }

    public void initialize() {
        ComplexityAnalyzer.LOGGER.info("Initializing MobPropertyProvider...");
        int failedCount = 0;

        for (var type : GameRegistryManager.getAllEntityTypes()) {
            if (type.getCategory() == MobCategory.MISC) continue;
            var props = getProperties(type);
            if (props == null) failedCount++;
        }

        ComplexityAnalyzer.LOGGER.debug("MobPropertyProvider initialized:");
        ComplexityAnalyzer.LOGGER.debug("  ✓ Total entities: {}", propertiesCache.size());
        ComplexityAnalyzer.LOGGER.debug("  ⚠ Failed/Skipped: {}", failedCount);
    }

    @Nullable
    public MobProperties getProperties(EntityType<?> type) {
        var cached = propertiesCache.get(type);
        if (cached != null) return cached;

        var attributes = getSupplier(type);
        if (attributes == null) return null;

        var maxHealth = attributes.hasAttribute(Attributes.MAX_HEALTH)
                ? attributes.getBaseValue(Attributes.MAX_HEALTH)
                : DEFAULT_MAX_HEALTH;

        var attackDamage = attributes.hasAttribute(Attributes.ATTACK_DAMAGE)
                ? attributes.getBaseValue(Attributes.ATTACK_DAMAGE)
                : 0.0;

        var armor = attributes.hasAttribute(Attributes.ARMOR)
                ? attributes.getBaseValue(Attributes.ARMOR)
                : 0.0;

        var props = new MobProperties(maxHealth, attackDamage, armor, type.getCategory());
        propertiesCache.put(type, props);

        return props;
    }

    public void markRenewable(EntityType<?> type) {
        renewableTypes.add(type);
    }

    public boolean isRenewable(EntityType<?> type) {
        return renewableTypes.contains(type);
    }

    @Override
    public void registerBoss(EntityType<?> entityType, BossType type) {
        if (entityType != null && type != null) registeredBosses.put(entityType, type);
    }

    @Override
    public void registerBoss(String entityId, BossType type) {
        var id = ResourceLocation.tryParse(entityId);
        if (id == null) {
            ComplexityAnalyzer.LOGGER.warn("[BossRegistry] Invalid entity ID: {}", entityId);
            return;
        }
        var entityType = GameRegistryManager.getEntityType(id);
        if (entityType != null) registerBoss(entityType, type);
    }

    @Override
    public boolean isBoss(EntityType<?> type) {
        return registeredBosses.get(type) == BossType.BOSS;
    }

    @Override
    public boolean isMiniBoss(EntityType<?> type) {
        return registeredBosses.get(type) == BossType.MINI_BOSS;
    }

    public double getRarity(EntityType<?> type) {
        if (isBoss(type)) return ComplexityConfig.BOSS_RARITY_MULTIPLIER.get();
        if (isMiniBoss(type)) return MINI_BOSS_RARITY_SCALE;

        var classification = type.getCategory();
        double baseRarity = switch (classification) {
            case MONSTER -> MONSTER_BASE_RARITY;
            case CREATURE -> CREATURE_BASE_RARITY;
            case AMBIENT -> AMBIENT_BASE_RARITY;
            case WATER_CREATURE, UNDERGROUND_WATER_CREATURE, WATER_AMBIENT -> WATER_CREATURE_BASE_RARITY;
            default -> OTHER_BASE_RARITY;
        };

        if (classification == MobCategory.MONSTER) {
            var props = getProperties(type);
            if (props != null) {
                double combatPower = props.calculateCombatPower();
                baseRarity += combatPower * POWER_TO_RARITY_COEFFICIENT;
            }
        }

        return Math.max(BASE_MIN_RARITY, baseRarity);
    }

    public void clearCache() {
        propertiesCache.clear();
        ComplexityAnalyzer.LOGGER.info("MobPropertyProvider cache cleared");
    }

    public record MobProperties(
            double maxHealth,
            double attackDamage,
            double armor,
            MobCategory classification
    ) {
        public double calculateSurvivability() {
            return maxHealth * (1.0 + armor * ARMOR_COEFFICIENT);
        }

        public double calculateThreat() {
            return 1.0 + Math.log1p(attackDamage);
        }

        public double calculateCombatPower() {
            return calculateSurvivability() * calculateThreat();
        }
    }
}