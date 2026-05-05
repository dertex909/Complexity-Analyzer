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

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.api.IBossRegistry;

import org.jetbrains.annotations.Nullable;

public class MobRarityCalculator implements IBossRegistry {

    private final DimensionRarityAnalyzer dimensionAnalyzer;

    private final Reference2DoubleMap<EntityType<?>> rarityCache = Reference2DoubleMaps.synchronize(new Reference2DoubleOpenHashMap<>());
    private final Reference2DoubleMap<EntityType<?>> healthCache = Reference2DoubleMaps.synchronize(new Reference2DoubleOpenHashMap<>());
    private final Reference2ObjectMap<EntityType<?>, BossLevel> bossCache = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    private final Reference2ObjectMap<EntityType<?>, BossLevel> registeredBosses = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    private static final double BOSS_RARITY = 50.0;
    private static final double MINI_BOSS_RARITY = 15.0;
    private static final double HIGH_HEALTH_BONUS = 5.0;
    private static final double VERY_HIGH_HEALTH_BONUS = 10.0;
    private static final double NETHER_BONUS = 3.0;
    private static final double END_BONUS = 5.0;
    private static final double MODDED_BONUS = 2.0;
    private static final double FALLBACK_RARITY = 1.0;

    private static final double BOSS_HEALTH_THRESHOLD = 200.0;
    private static final double MINI_BOSS_HEALTH_THRESHOLD = 80.0;
    private static final double HIGH_HEALTH_THRESHOLD = 50.0;

    private static final ObjectSet<String> BOSS_KEYWORDS = new ObjectOpenHashSet<>(java.util.List.of(
            "boss", "dragon", "king", "queen", "lord", "ancient", "elder", "wither"
    ));

    private static final ObjectSet<String> RARE_KEYWORDS = new ObjectOpenHashSet<>(java.util.List.of(
            "rare", "elite", "champion", "alpha", "mutant", "titan", "legendary", "prime"
    ));

    private enum BossLevel {NONE, MINI_BOSS, BOSS}

    public MobRarityCalculator(DimensionRarityAnalyzer dimensionAnalyzer) {
        this.dimensionAnalyzer = dimensionAnalyzer;
    }

    @Override
    public void registerBoss(EntityType<?> entityType, IBossRegistry.BossType type) {
        var level = type == IBossRegistry.BossType.BOSS ? BossLevel.BOSS : BossLevel.MINI_BOSS;
        registeredBosses.put(entityType, level);
        bossCache.put(entityType, level);
        ComplexityAnalyzer.LOGGER.info("[BossRegistry] Registered {} as {}",
                BuiltInRegistries.ENTITY_TYPE.getKey(entityType), type);
    }

    @Override
    public void registerBoss(String entityId, IBossRegistry.BossType type) {
        var id = ResourceLocation.tryParse(entityId);
        if (id == null) {
            ComplexityAnalyzer.LOGGER.warn("[BossRegistry] Invalid entity ID: {}", entityId);
            return;
        }

        var entityType = BuiltInRegistries.ENTITY_TYPE.get(id);

        registerBoss(entityType, type);
    }

    @Override
    public boolean isBoss(EntityType<?> entityType) {
        var level = detectBossLevel(entityType);
        return level == BossLevel.BOSS;
    }

    @Override
    public boolean isMiniBoss(EntityType<?> entityType) {
        var level = detectBossLevel(entityType);
        return level == BossLevel.MINI_BOSS;
    }

    public double calculateRarity(EntityType<?> entityType) {
        if (rarityCache.containsKey(entityType)) return rarityCache.getDouble(entityType);
        var result = calculateRarityInternal(entityType);
        rarityCache.put(entityType, result);
        return result;
    }

    private double calculateRarityInternal(EntityType<?> entityType) {
        var isModded = isModdedEntity(entityType);

        if (!isModded) {
            var hardcoded = getHardcodedRarity(entityType);
            if (hardcoded != null) return hardcoded;
        }

        double rarity = 0.0;

        BossLevel bossLevel = detectBossLevel(entityType);
        switch (bossLevel) {
            case BOSS -> rarity += BOSS_RARITY;
            case MINI_BOSS -> rarity += MINI_BOSS_RARITY;
        }

        double structRarity = dimensionAnalyzer.getStructureMultiplier(entityType);
        if (structRarity > 0) {
            rarity += structRarity;
        }

        if (structRarity == 0) {
            double biomeRarity = dimensionAnalyzer.getBiomeMultiplier(entityType);
            if (biomeRarity > 0) rarity += biomeRarity;
        }

        var health = getEntityHealth(entityType);
        if (health >= BOSS_HEALTH_THRESHOLD && bossLevel != BossLevel.BOSS) {
            rarity += VERY_HIGH_HEALTH_BONUS;
        } else if (health >= HIGH_HEALTH_THRESHOLD && bossLevel == BossLevel.NONE) {
            rarity += HIGH_HEALTH_BONUS;
        }

        double categoryBonus = analyzeMobCategory(entityType);
        if (categoryBonus > 0) rarity += categoryBonus;

        double dimensionBonus = analyzeDimensionExclusivity(entityType);
        if (dimensionBonus > 0) rarity += dimensionBonus;

        if (isModded) {
            var nameBonus = analyzeEntityName(entityType);
            if (nameBonus > 0) rarity += nameBonus;
            rarity += MODDED_BONUS;
        }

        double yLevelBonus = analyzeYLevelRestrictions(entityType);
        if (yLevelBonus > 0) rarity += yLevelBonus;
        if (rarity == 0) rarity = FALLBACK_RARITY;
        return rarity;
    }


    private BossLevel detectBossLevel(EntityType<?> entityType) {
        var registered = registeredBosses.get(entityType);
        if (registered != null) return registered;

        var cached = bossCache.get(entityType);
        if (cached != null) return cached;

        var detected = detectBossLevelInternal(entityType);
        bossCache.put(entityType, detected);
        return detected;
    }

    private BossLevel detectBossLevelInternal(EntityType<?> entityType) {
        BossLevel classCheck = detectByClass(entityType);
        if (classCheck != BossLevel.NONE) {
            ComplexityAnalyzer.LOGGER.debug("[BossDetection] {} detected as {} by class",
                    getEntityName(entityType), classCheck);
            return classCheck;
        }

        BossLevel vanillaCheck = detectVanillaBoss(entityType);
        if (vanillaCheck != BossLevel.NONE) return vanillaCheck;

        double health = getEntityHealth(entityType);
        if (health >= BOSS_HEALTH_THRESHOLD) {
            ComplexityAnalyzer.LOGGER.debug("[BossDetection] {} detected as BOSS by health ({})",
                    getEntityName(entityType), health);
            return BossLevel.BOSS;
        } else if (health >= MINI_BOSS_HEALTH_THRESHOLD) {
            if (hasMiniBossIndicators(entityType)) {
                ComplexityAnalyzer.LOGGER.debug("[BossDetection] {} detected as MINI_BOSS by health + indicators",
                        getEntityName(entityType));
                return BossLevel.MINI_BOSS;
            }
        }

        if (isModdedEntity(entityType) && health >= 40.0) {
            var nameCheck = detectByName(entityType);
            if (nameCheck != BossLevel.NONE) {
                ComplexityAnalyzer.LOGGER.debug("[BossDetection] {} detected as {} by name pattern",
                        getEntityName(entityType), nameCheck);
                return nameCheck;
            }
        }

        return BossLevel.NONE;
    }


    private BossLevel detectByClass(EntityType<?> entityType) {
        try {
            Class<?> entityClass = entityType.getBaseClass();

            if (WitherBoss.class.isAssignableFrom(entityClass)) return BossLevel.BOSS;
            if (EnderDragon.class.isAssignableFrom(entityClass)) return BossLevel.BOSS;

            var className = entityClass.getSimpleName();
            if (className.endsWith("Boss") || className.contains("BossEntity")) return BossLevel.BOSS;

            var superClass = entityClass.getSuperclass();
            while (superClass != null && superClass != Object.class) {
                var superName = superClass.getSimpleName();
                if (superName.endsWith("Boss") || superName.equals("BossEntity")) return BossLevel.BOSS;
                superClass = superClass.getSuperclass();
            }

            var packageName = entityClass.getPackage() != null ?
                    entityClass.getPackage().getName() : "";
            if (packageName.contains(".boss.") || packageName.endsWith(".boss")) {

                var health = getEntityHealth(entityType);
                if (health >= MINI_BOSS_HEALTH_THRESHOLD) {
                    return health >= BOSS_HEALTH_THRESHOLD ? BossLevel.BOSS : BossLevel.MINI_BOSS;
                }
            }

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Error checking boss class for {}: {}",
                    getEntityName(entityType), e.getMessage());
        }

        return BossLevel.NONE;
    }


    private BossLevel detectVanillaBoss(EntityType<?> entityType) {
        if (entityType == EntityType.ENDER_DRAGON || entityType == EntityType.WITHER) return BossLevel.BOSS;
        if (entityType == EntityType.ELDER_GUARDIAN || entityType == EntityType.WARDEN) return BossLevel.MINI_BOSS;
        return BossLevel.NONE;
    }


    private BossLevel detectByName(EntityType<?> entityType) {
        var name = getEntityName(entityType).toLowerCase();
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        var idString = id.getPath().toLowerCase();

        for (var keyword : BOSS_KEYWORDS) {
            if (name.contains(keyword) || idString.contains(keyword)) return BossLevel.BOSS;
        }

        for (var keyword : RARE_KEYWORDS) {
            if (name.contains(keyword) || idString.contains(keyword)) return BossLevel.MINI_BOSS;
        }

        return BossLevel.NONE;
    }


    private boolean hasMiniBossIndicators(EntityType<?> entityType) {
        if (entityType.getCategory() == MobCategory.MISC) return true;
        if (isModdedEntity(entityType)) {
            var nameCheck = detectByName(entityType);
            return nameCheck == BossLevel.MINI_BOSS;
        }

        return false;
    }


    private boolean isModdedEntity(EntityType<?> entityType) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        return !id.getNamespace().equals("minecraft");
    }

    private double getEntityHealth(EntityType<?> entityType) {
        if (healthCache.containsKey(entityType)) return healthCache.getDouble(entityType);
        var result = getEntityHealthInternal(entityType);
        healthCache.put(entityType, result);
        return result;
    }

    private double getEntityHealthInternal(EntityType<?> entityType) {
        try {
            @SuppressWarnings("unchecked")
            var livingType = (EntityType<? extends LivingEntity>) entityType;

            var attributes = DefaultAttributes.getSupplier(livingType);
            if (attributes.hasAttribute(Attributes.MAX_HEALTH)) return attributes.getValue(Attributes.MAX_HEALTH);
        } catch (ClassCastException ignored) {
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Could not get attributes for {}: {}",
                    getEntityName(entityType), e.getMessage());
        }

        if (!isModdedEntity(entityType)) {
            var vanillaHealth = getVanillaHealth(entityType);
            if (vanillaHealth != null) return vanillaHealth;
        }

        return 20.0;
    }

    @Nullable
    private Double getVanillaHealth(EntityType<?> entityType) {
        if (entityType == EntityType.ENDER_DRAGON) return 200.0;
        if (entityType == EntityType.WITHER) return 300.0;
        if (entityType == EntityType.WARDEN) return 500.0;
        if (entityType == EntityType.ELDER_GUARDIAN) return 80.0;
        if (entityType == EntityType.RAVAGER) return 100.0;
        if (entityType == EntityType.IRON_GOLEM) return 100.0;
        if (entityType == EntityType.PIGLIN_BRUTE) return 50.0;
        if (entityType == EntityType.HOGLIN) return 40.0;
        if (entityType == EntityType.ZOGLIN) return 40.0;
        if (entityType == EntityType.ENDERMAN) return 40.0;
        if (entityType == EntityType.GUARDIAN) return 30.0;
        if (entityType == EntityType.SHULKER) return 30.0;
        if (entityType == EntityType.WITCH) return 26.0;
        return null;
    }

    private double analyzeEntityName(EntityType<?> entityType) {
        var name = getEntityName(entityType).toLowerCase();
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        var idString = id.getPath().toLowerCase();

        double bonus = 0.0;
        if (name.contains("rare") || idString.contains("rare")) bonus += 5.0;
        if (name.contains("special") || name.contains("unique")) bonus += 3.0;
        if (name.contains("elite") || name.contains("champion")) bonus += 7.0;
        return bonus;
    }

    private double analyzeDimensionExclusivity(EntityType<?> entityType) {
        if (!isModdedEntity(entityType)) {
            if (isNetherExclusive(entityType)) return NETHER_BONUS;
            if (isEndExclusive(entityType)) return END_BONUS;
        }
        return 0.0;
    }

    private double analyzeMobCategory(EntityType<?> entityType) {
        MobCategory category = entityType.getCategory();
        return switch (category) {
            case MONSTER, CREATURE -> 0.0;
            case AMBIENT, WATER_CREATURE -> 2.0;
            case WATER_AMBIENT -> 3.0;
            case UNDERGROUND_WATER_CREATURE -> 4.0;
            case AXOLOTLS -> 6.0;
            case MISC -> 1.0;
        };
    }

    @Nullable
    private Double getHardcodedRarity(EntityType<?> entityType) {
        if (entityType == EntityType.ENDER_DRAGON) return 100.0;
        if (entityType == EntityType.WITHER) return 80.0;
        if (entityType == EntityType.WARDEN) return 50.0;
        if (entityType == EntityType.ELDER_GUARDIAN) return 30.0;
        if (entityType == EntityType.SHULKER) return 20.0;
        if (entityType == EntityType.EVOKER) return 15.0;
        if (entityType == EntityType.VINDICATOR) return 12.0;
        if (entityType == EntityType.RAVAGER) return 18.0;
        if (entityType == EntityType.PIGLIN_BRUTE) return 12.0;
        if (entityType == EntityType.BLAZE) return 10.0;
        if (entityType == EntityType.WITHER_SKELETON) return 10.0;
        if (entityType == EntityType.GUARDIAN) return 8.0;
        if (entityType == EntityType.GHAST) return 7.0;
        if (entityType == EntityType.ZOMBIE) return 1.0;
        if (entityType == EntityType.SKELETON) return 1.0;
        if (entityType == EntityType.CREEPER) return 1.0;
        if (entityType == EntityType.SPIDER) return 1.0;

        return null;
    }

    private boolean isNetherExclusive(EntityType<?> entityType) {
        return entityType == EntityType.GHAST || entityType == EntityType.BLAZE ||
                entityType == EntityType.MAGMA_CUBE || entityType == EntityType.WITHER_SKELETON ||
                entityType == EntityType.PIGLIN || entityType == EntityType.PIGLIN_BRUTE ||
                entityType == EntityType.HOGLIN || entityType == EntityType.STRIDER;
    }

    private boolean isEndExclusive(EntityType<?> entityType) {
        return entityType == EntityType.ENDER_DRAGON || entityType == EntityType.SHULKER ||
                entityType == EntityType.ENDERMITE;
    }

    private double analyzeYLevelRestrictions(EntityType<?> entityType) {
        if (entityType == EntityType.WARDEN) return 10.0;
        if (entityType == EntityType.PHANTOM) return 2.0;
        return 0.0;
    }

    private String getEntityName(EntityType<?> entityType) {
        return entityType.getDescription().getString();
    }

    public void clearCache() {
        rarityCache.clear();
        healthCache.clear();
        bossCache.clear();
        ComplexityAnalyzer.LOGGER.info("MobRarityCalculator cache cleared");
    }
}