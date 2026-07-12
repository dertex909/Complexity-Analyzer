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

import java.util.Optional;

/**
 * Read-only access to the analyzer's mob model: spawn rarity (derived from biome/structure spawn weights) and
 * combat difficulty (derived from real attribute suppliers). Used internally to price mob drops; exposed so
 * addons can reuse the same numbers.
 */
public interface MobData {

    /**
     * @return spawn-rarity multiplier: {@code 1.0} for the most common mob, higher for rarer ones,
     * {@code Double.POSITIVE_INFINITY} for mobs that never spawn naturally (summoned/boss).
     */
    double getRarity(EntityType<?> type);

    /**
     * @return kill-difficulty score {@code sqrt(maxHealth * max(1, attackDamage)) * (1 + armor*0.05)}, or 0 if unknown.
     */
    double getCombatPower(EntityType<?> type);

    boolean isBoss(EntityType<?> type);

    boolean isMiniBoss(EntityType<?> type);

    /**
     * @return whether the mob is breedable/farmable (its drops receive the renewable discount).
     */
    boolean isRenewable(EntityType<?> type);

    /**
     * @return the full combat + rarity snapshot for the mob, if it has analyzable attributes.
     */
    Optional<MobInfo> getInfo(EntityType<?> type);
}