package org.complexityanalyzer.analyzer.resource.providers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.complexityanalyzer.ComplexityAnalyzer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MobRarityCalculator {

    private final DimensionRarityAnalyzer dimensionAnalyzer;

    private final Map<EntityType<?>, Double> rarityCache = new ConcurrentHashMap<>();
    private final Map<EntityType<?>, Double> healthCache = new ConcurrentHashMap<>();
    private final Map<EntityType<?>, BossLevel> bossCache = new ConcurrentHashMap<>();

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

    private static final Set<String> BOSS_KEYWORDS = Set.of(
            "boss", "dragon", "wither", "king", "queen", "lord", "ancient", "elder"
    );

    private static final Set<String> RARE_KEYWORDS = Set.of(
            "rare", "elite", "champion", "alpha", "mutant", "titan", "legendary"
    );

    private enum BossLevel {
        NONE,
        MINI_BOSS,
        BOSS
    }

    public MobRarityCalculator(DimensionRarityAnalyzer dimensionAnalyzer) {
        this.dimensionAnalyzer = dimensionAnalyzer;
    }

    public boolean isBoss(EntityType<?> entityType) {
        BossLevel level = detectBossLevel(entityType);
        return level == BossLevel.BOSS;
    }

    public boolean isMiniBoss(EntityType<?> entityType) {
        BossLevel level = detectBossLevel(entityType);
        return level == BossLevel.MINI_BOSS;
    }

    public double calculateRarity(EntityType<?> entityType) {
        return rarityCache.computeIfAbsent(entityType, this::calculateRarityInternal);
    }

    private double calculateRarityInternal(EntityType<?> entityType) {
        boolean isModded = isModdedEntity(entityType);

        if (!isModded) {
            Double hardcoded = getHardcodedRarity(entityType);
            if (hardcoded != null) {
                ComplexityAnalyzer.LOGGER.debug("[Vanilla] Using hardcoded rarity for {}: {}x",
                        getEntityName(entityType), hardcoded);
                return hardcoded;
            }
        }

        double rarity = 0.0;

        BossLevel bossLevel = detectBossLevel(entityType);
        switch (bossLevel) {
            case BOSS -> {
                rarity += BOSS_RARITY;
                ComplexityAnalyzer.LOGGER.debug("[{}] Detected as BOSS: {}x",
                        isModded ? "Modded" : "Vanilla", BOSS_RARITY);
            }
            case MINI_BOSS -> {
                rarity += MINI_BOSS_RARITY;
                ComplexityAnalyzer.LOGGER.debug("[{}] Detected as MINI-BOSS: {}x",
                        isModded ? "Modded" : "Vanilla", MINI_BOSS_RARITY);
            }
        }

        double structRarity = dimensionAnalyzer.getStructureMultiplier(entityType);
        if (structRarity > 0) {
            rarity += structRarity;
            ComplexityAnalyzer.LOGGER.debug("[{}] Structure spawn bonus: {}x",
                    isModded ? "Modded" : "Vanilla", structRarity);
        }

        if (structRarity == 0) {
            double biomeRarity = dimensionAnalyzer.getBiomeMultiplier(entityType);
            if (biomeRarity > 0) {
                rarity += biomeRarity;
                ComplexityAnalyzer.LOGGER.debug("[{}] Biome spawn bonus: {}x",
                        isModded ? "Modded" : "Vanilla", biomeRarity);
            }
        }

        double health = getEntityHealth(entityType);
        if (health >= BOSS_HEALTH_THRESHOLD && bossLevel != BossLevel.BOSS) {
            rarity += VERY_HIGH_HEALTH_BONUS;
            ComplexityAnalyzer.LOGGER.debug("[{}] Very high health ({}): +{}x",
                    isModded ? "Modded" : "Vanilla", health, VERY_HIGH_HEALTH_BONUS);
        } else if (health >= HIGH_HEALTH_THRESHOLD && bossLevel == BossLevel.NONE) {
            rarity += HIGH_HEALTH_BONUS;
            ComplexityAnalyzer.LOGGER.debug("[{}] High health ({}): +{}x",
                    isModded ? "Modded" : "Vanilla", health, HIGH_HEALTH_BONUS);
        }

        double categoryBonus = analyzeMobCategory(entityType);
        if (categoryBonus > 0) {
            rarity += categoryBonus;
        }

        double dimensionBonus = analyzeDimensionExclusivity(entityType);
        if (dimensionBonus > 0) {
            rarity += dimensionBonus;
        }

        if (isModded) {
            double nameBonus = analyzeEntityName(entityType);
            if (nameBonus > 0) {
                rarity += nameBonus;
            }
            rarity += MODDED_BONUS;
        }

        double yLevelBonus = analyzeYLevelRestrictions(entityType);
        if (yLevelBonus > 0) {
            rarity += yLevelBonus;
        }

        if (rarity == 0) {
            rarity = FALLBACK_RARITY;
            ComplexityAnalyzer.LOGGER.debug("[{}] No special rarity data, using fallback: {}x",
                    isModded ? "Modded" : "Vanilla", FALLBACK_RARITY);
        }

        ComplexityAnalyzer.LOGGER.info("[{}] Final rarity for {}: {}x",
                isModded ? "Modded" : "Vanilla", getEntityName(entityType), rarity);
        return rarity;
    }

    private boolean isModdedEntity(EntityType<?> entityType) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        return !id.getNamespace().equals("minecraft");
    }

    private BossLevel detectBossLevel(EntityType<?> entityType) {
        return bossCache.computeIfAbsent(entityType, type -> {
            if (type == EntityType.ENDER_DRAGON || type == EntityType.WITHER) {
                return BossLevel.BOSS;
            }

            if (type == EntityType.ELDER_GUARDIAN || type == EntityType.WARDEN) {
                return BossLevel.MINI_BOSS;
            }

            double health = getEntityHealth(type);
            if (health >= BOSS_HEALTH_THRESHOLD) {
                return BossLevel.BOSS;
            } else if (health >= MINI_BOSS_HEALTH_THRESHOLD) {
                return BossLevel.MINI_BOSS;
            }

            String name = getEntityName(type).toLowerCase();
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String idString = id.getPath().toLowerCase();

            for (String keyword : BOSS_KEYWORDS) {
                if (name.contains(keyword) || idString.contains(keyword)) {
                    return BossLevel.BOSS;
                }
            }

            for (String keyword : RARE_KEYWORDS) {
                if (name.contains(keyword) || idString.contains(keyword)) {
                    return BossLevel.MINI_BOSS;
                }
            }

            if (type.getCategory() == MobCategory.MISC && health > 40.0) {
                return BossLevel.MINI_BOSS;
            }

            return BossLevel.NONE;
        });
    }

    private double getEntityHealth(EntityType<?> entityType) {
        return healthCache.computeIfAbsent(entityType, type -> {
            try {
                @SuppressWarnings("unchecked")
                EntityType<? extends LivingEntity> livingType = (EntityType<? extends LivingEntity>) type;

                AttributeSupplier attributes = DefaultAttributes.getSupplier(livingType);
                if (attributes.hasAttribute(Attributes.MAX_HEALTH)) {
                    return attributes.getValue(Attributes.MAX_HEALTH);
                }
            } catch (ClassCastException ignored) {
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("Could not get attributes for {}: {}",
                        getEntityName(type), e.getMessage());
            }

            if (!isModdedEntity(type)) {
                Double vanillaHealth = getVanillaHealth(type);
                if (vanillaHealth != null) return vanillaHealth;
            }

            return 20.0;
        });
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
        String name = getEntityName(entityType).toLowerCase();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        String idString = id.getPath().toLowerCase();

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
        return entityType == EntityType.GHAST ||
                entityType == EntityType.BLAZE ||
                entityType == EntityType.MAGMA_CUBE ||
                entityType == EntityType.WITHER_SKELETON ||
                entityType == EntityType.PIGLIN ||
                entityType == EntityType.PIGLIN_BRUTE ||
                entityType == EntityType.HOGLIN ||
                entityType == EntityType.STRIDER;
    }

    private boolean isEndExclusive(EntityType<?> entityType) {
        return entityType == EntityType.ENDER_DRAGON ||
                entityType == EntityType.SHULKER ||
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