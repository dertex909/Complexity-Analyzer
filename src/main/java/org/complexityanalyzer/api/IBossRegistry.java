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

package org.complexityanalyzer.api;

import net.minecraft.world.entity.EntityType;

/**
 * Registry for tagging mobs as bosses or mini-bosses. Boss status raises a mob's effective rarity (and thus
 * the complexity of its drops), since bosses are rare, deliberate encounters rather than ambient spawns.
 *
 * <p>Vanilla bosses (Ender Dragon, Wither) are auto-detected. Use this to flag modded bosses so their drops
 * are priced accordingly. Register during
 * {@link org.complexityanalyzer.api.event.ComplexityRegistrationEvent}.
 */
public interface IBossRegistry {

    /**
     * Registers a mob as a boss or mini-boss.
     *
     * @param entityType the mob to tag
     * @param type       whether it is a {@link BossType#BOSS} or {@link BossType#MINI_BOSS}
     */
    void registerBoss(EntityType<?> entityType, BossType type);

    /**
     * Registers a mob by its registry id (e.g. {@code "mymod:dragon_lord"}). Useful when the {@link EntityType}
     * is not directly available. Unparseable ids are ignored.
     *
     * @param entityId the entity registry id
     * @param type     whether it is a {@link BossType#BOSS} or {@link BossType#MINI_BOSS}
     */
    void registerBoss(String entityId, BossType type);

    /**
     * @return {@code true} if the mob is a registered or auto-detected boss.
     */
    boolean isBoss(EntityType<?> entityType);

    /**
     * @return {@code true} if the mob is a registered mini-boss.
     */
    boolean isMiniBoss(EntityType<?> entityType);

    /**
     * Classification of a registered mob.
     */
    enum BossType {
        /**
         * A full boss — rare, summoned or arena-style encounter.
         */
        BOSS,
        /**
         * A mini-boss — tougher-than-normal but more frequently encountered than a full boss.
         */
        MINI_BOSS
    }
}