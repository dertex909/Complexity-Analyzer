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

import org.complexityanalyzer.analyzer.resource.IResourceSource;

/**
 * Registry for fully custom {@link IResourceSource} implementations. A resource source tells the analyzer how
 * a raw (non-crafted) item can be obtained and at what base cost — e.g. a custom mining mechanic, a quest
 * reward, a modded gathering profession.
 *
 * <p>Sources registered here participate in analysis exactly like the built-in ones: their
 * {@link IResourceSource#initialize} runs during engine build, and {@link IResourceSource#analyze} is queried
 * when resolving an item's cheapest acquisition path. Higher {@link IResourceSource#getPriority()} wins ties.
 *
 * <p>Register during {@link org.complexityanalyzer.api.event.ComplexityRegistrationEvent}.
 */
public interface IResourceSourceRegistry {

    /**
     * Adds a custom resource source to the analysis pipeline.
     */
    void register(IResourceSource source);
}