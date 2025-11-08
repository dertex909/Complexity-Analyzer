/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.analyzer.resource;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.util.Optional;

public interface IResourceSource {

    void initialize(Level level);

    boolean canProvide(Item item);

    Optional<BaseResourceData> analyze(Item item);

    BaseResourceData.ResourceSourceType getSourceType();

    default int getPriority() {
        return 0;
    }

    /**
     * @return true if this source primarily mirrors crafted recipes and should be ignored
     *         when a legitimate crafting path already exists.
     */
    default boolean prefersRecipeOutputs() {
        return false;
    }

    String getName();
}
