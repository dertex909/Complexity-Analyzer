package org.complexityanalyzer.api;

import net.minecraft.world.entity.EntityType;

public interface IBossRegistry {

    enum BossType {
        BOSS,
        MINI_BOSS
    }

    void registerBoss(EntityType<?> entityType, BossType type);

    void registerBoss(String entityId, BossType type);

    boolean isBoss(EntityType<?> entityType);

    boolean isMiniBoss(EntityType<?> entityType);
}