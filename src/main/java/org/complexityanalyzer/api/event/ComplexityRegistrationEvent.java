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

package org.complexityanalyzer.api.event;

import net.neoforged.bus.api.Event;
import org.complexityanalyzer.api.IBossRegistry;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.api.IRenewableRegistry;
import org.complexityanalyzer.api.IResourceSourceRegistry;

/**
 * Fired on the NeoForge game bus ({@code NeoForge.EVENT_BUS}) immediately before the analyzer builds its
 * resource-source pipeline — once per analysis build (server start and every reload). Subscribe to contribute
 * data: bosses, renewable mobs, hardcoded sources/overrides and fully custom resource sources.
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void onRegister(ComplexityRegistrationEvent e) {
 *     e.bosses().registerBoss(MyEntities.DRAGON_LORD.get(), IBossRegistry.BossType.BOSS);
 *     e.renewables().markRenewable(MyEntities.MANA_SLIME.get());
 *     e.hardcodedSources().registerUnobtainable(MyItems.DEBUG_WAND.get(), "creative only");
 *     e.resourceSources().register(new MyCustomGatheringSource());
 * }
 * }</pre>
 * <p>
 * Everything registered here is rebuilt from scratch on each build, so registrations are idempotent — always
 * register in the handler rather than caching across builds.
 */
public class ComplexityRegistrationEvent extends Event {

    private final IBossRegistry bosses;
    private final IHardcodedSourceRegistry hardcodedSources;
    private final IRenewableRegistry renewables;
    private final IResourceSourceRegistry resourceSources;

    public ComplexityRegistrationEvent(IBossRegistry bosses, IHardcodedSourceRegistry hardcodedSources,
                                       IRenewableRegistry renewables, IResourceSourceRegistry resourceSources) {
        this.bosses = bosses;
        this.hardcodedSources = hardcodedSources;
        this.renewables = renewables;
        this.resourceSources = resourceSources;
    }

    /**
     * Register/query boss and mini-boss mobs (affects rarity weighting).
     */
    public IBossRegistry bosses() {
        return bosses;
    }

    /**
     * Register hardcoded acquisition sources, value overrides and unobtainable items.
     */
    public IHardcodedSourceRegistry hardcodedSources() {
        return hardcodedSources;
    }

    /**
     * Flag modded mobs as renewable (breedable/farmable) so their drops get the renewable discount.
     */
    public IRenewableRegistry renewables() {
        return renewables;
    }

    /**
     * Add fully custom {@link org.complexityanalyzer.resource.IResourceSource} implementations.
     */
    public IResourceSourceRegistry resourceSources() {
        return resourceSources;
    }
}