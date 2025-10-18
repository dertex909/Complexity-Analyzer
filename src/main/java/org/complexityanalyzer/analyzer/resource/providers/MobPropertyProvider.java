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

    public void initialize() {
        ComplexityAnalyzer.LOGGER.info("Initializing MobPropertyProvider...");
        Map<EntityType<? extends LivingEntity>, AttributeSupplier> attributeMap = this.getAttributesViaReflection();

        int analyzed = 0;
        for (Map.Entry<EntityType<? extends LivingEntity>, AttributeSupplier> entry : attributeMap.entrySet()) {
            EntityType<?> type = entry.getKey();
            AttributeSupplier attributes = entry.getValue();
            try {
                double maxHealth = attributes.getBaseValue(Attributes.MAX_HEALTH);
                double attackDamage = attributes.hasAttribute(Attributes.ATTACK_DAMAGE) ? attributes.getBaseValue(Attributes.ATTACK_DAMAGE) : 0.0;
                double armor = attributes.hasAttribute(Attributes.ARMOR) ? attributes.getBaseValue(Attributes.ARMOR) : 0.0;
                attackDamage = Math.max(attackDamage, 0.1);
                MobCategory classification = type.getCategory();
                boolean isBoss = !classification.isFriendly() && (type == EntityType.WITHER || type == EntityType.ENDER_DRAGON);
                propertiesCache.put(type, new MobProperties(maxHealth, attackDamage, armor, classification, isBoss));
                analyzed++;
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Could not fully analyze attributes for entity type: {}", BuiltInRegistries.ENTITY_TYPE.getKey(type));
            }
        }
        ComplexityAnalyzer.LOGGER.info("MobPropertyProvider initialized, analyzed {} entities.", analyzed);
    }

    @SuppressWarnings("unchecked")
    private Map<EntityType<? extends LivingEntity>, AttributeSupplier> getAttributesViaReflection() {
        try {
            Field suppliersField = DefaultAttributes.class.getDeclaredField("SUPPLIERS");
            suppliersField.setAccessible(true);
            return (Map<EntityType<? extends LivingEntity>, AttributeSupplier>) suppliersField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            ComplexityAnalyzer.LOGGER.error("!!! FAILED TO ACCESS MOB ATTRIBUTES VIA REFLECTION !!! Mob analysis will be degraded.", e);
            return Collections.emptyMap();
        }
    }

    public Optional<MobProperties> getProperties(EntityType<?> type) {
        return Optional.ofNullable(propertiesCache.get(type));
    }

    public record MobProperties(
            double maxHealth,
            double attackDamage,
            double armor,
            MobCategory classification,
            boolean isBoss
    ) {}
}