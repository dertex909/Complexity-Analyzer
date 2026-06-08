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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.api;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * Immutable snapshot of everything the analyzer knows about a mob's combat profile and spawn rarity.
 *
 * @param type         the entity type this describes
 * @param maxHealth    base max health attribute
 * @param attackDamage base attack-damage attribute (≥ 0.1)
 * @param armor        base armor attribute
 * @param combatPower  derived kill-difficulty score: {@code sqrt(maxHealth * max(1, attackDamage)) * (1 + armor*0.05)}
 * @param rarity       spawn-rarity multiplier (1.0 = most common; higher = rarer; {@code Infinity} = never spawns)
 * @param category     vanilla spawn category (MONSTER, CREATURE, …)
 * @param boss         whether the mob is registered/detected as a boss
 * @param miniBoss     whether the mob is registered as a mini-boss
 * @param renewable    whether the mob is breedable/farmable (drops get the renewable discount)
 */
public record MobInfo(
        EntityType<?> type,
        double maxHealth,
        double attackDamage,
        double armor,
        double combatPower,
        double rarity,
        MobCategory category,
        boolean boss,
        boolean miniBoss,
        boolean renewable
) {
}