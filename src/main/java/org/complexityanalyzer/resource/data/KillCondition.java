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

package org.complexityanalyzer.resource.data;

import net.minecraft.world.entity.EntityType;
import org.complexityanalyzer.resource.providers.MobPropertyProvider;
import org.jetbrains.annotations.Nullable;

public enum KillCondition {
    NORMAL("Normal", null, 1.0),
    BOSS_KILL("Boss Kill", null, 1.0),
    CHARGED_CREEPER("Charged Creeper", EntityType.CREEPER, 4.0),
    SKELETON_ARROW("Skeleton Arrow", EntityType.SKELETON, 1.0);

    private final String displayName;
    @Nullable
    private final EntityType<?> helperMob;
    private final double difficultyMultiplier;

    KillCondition(String displayName, @Nullable EntityType<?> helperMob, double difficultyMultiplier) {
        this.displayName = displayName;
        this.helperMob = helperMob;
        this.difficultyMultiplier = difficultyMultiplier;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public EntityType<?> getHelperMob() {
        return helperMob;
    }

    public double calculateExtraCost(MobPropertyProvider provider) {
        if (helperMob == null) return 0.0;
        var props = provider.getProperties(helperMob);
        if (props == null) return 0.0;
        return props.calculateCombatPower() * provider.getRarity(helperMob) * difficultyMultiplier;
    }
}