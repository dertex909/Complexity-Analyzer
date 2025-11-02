package org.complexityanalyzer.analyzer.resource.providers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MobPropertyProvider {
    private final Map<EntityType<?>, MobProperties> propertiesCache = new HashMap<>();
    private MobRarityCalculator rarityCalculator;

    private static final Map<EntityType<?>, MobProperties> MANUAL_OVERRIDES = new HashMap<>();

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
        Map<EntityType<? extends LivingEntity>, AttributeSupplier> attributeMap = this.getAttributesViaReflection();

        int analyzed = 0;
        int skipped = 0;
        for (Map.Entry<EntityType<? extends LivingEntity>, AttributeSupplier> entry : attributeMap.entrySet()) {
            EntityType<?> type = entry.getKey();

            if (MANUAL_OVERRIDES.containsKey(type)) {
                skipped++;
                continue;
            }

            AttributeSupplier attributes = entry.getValue();
            try {
                double maxHealth = attributes.getBaseValue(Attributes.MAX_HEALTH);
                double attackDamage = attributes.hasAttribute(Attributes.ATTACK_DAMAGE)
                        ? attributes.getBaseValue(Attributes.ATTACK_DAMAGE)
                        : 0.0;
                double armor = attributes.hasAttribute(Attributes.ARMOR)
                        ? attributes.getBaseValue(Attributes.ARMOR)
                        : 0.0;

                attackDamage = Math.max(attackDamage, 0.1);
                MobCategory classification = type.getCategory();

                propertiesCache.put(type, new MobProperties(
                        maxHealth,
                        attackDamage,
                        armor,
                        classification
                ));
                analyzed++;
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Could not fully analyze attributes for entity type: {}",
                        BuiltInRegistries.ENTITY_TYPE.getKey(type));
            }
        }

        ComplexityAnalyzer.LOGGER.info("MobPropertyProvider initialized. Analyzed {} entities via reflection, {} entities were manually overridden.", analyzed, skipped);
    }

    public void setRarityCalculator(MobRarityCalculator calculator) {
        this.rarityCalculator = calculator;
    }

    @SuppressWarnings("unchecked")
    private Map<EntityType<? extends LivingEntity>, AttributeSupplier> getAttributesViaReflection() {
        try {
            Field suppliersField = DefaultAttributes.class.getDeclaredField("SUPPLIERS");
            suppliersField.setAccessible(true);
            return (Map<EntityType<? extends LivingEntity>, AttributeSupplier>) suppliersField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            ComplexityAnalyzer.LOGGER.error(
                    "!!! FAILED TO ACCESS MOB ATTRIBUTES VIA REFLECTION !!! Mob analysis will be degraded.", e);
            return Collections.emptyMap();
        }
    }

    public Optional<MobProperties> getProperties(EntityType<?> type) {
        if (MANUAL_OVERRIDES.containsKey(type)) {
            return Optional.of(MANUAL_OVERRIDES.get(type));
        }
        return Optional.ofNullable(propertiesCache.get(type));
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