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

/**
 * Registry for marking mob types as renewable (breedable / farmable indefinitely). Renewable mobs receive a
 * large discount on the complexity of their drops, because the player can mass-produce them.
 *
 * <p>By default the analyzer auto-detects vanilla {@code Animal} subclasses. Use this to flag modded mobs that
 * are renewable but do not extend {@code Animal} (e.g. custom breeding mechanics, spawner-farmable mobs).
 */
public interface IRenewableRegistry {

    /**
     * Flags the given entity type as renewable.
     */
    void markRenewable(EntityType<?> type);

    /**
     * @return {@code true} if the type is currently considered renewable (auto-detected or registered).
     */
    boolean isRenewable(EntityType<?> type);
}