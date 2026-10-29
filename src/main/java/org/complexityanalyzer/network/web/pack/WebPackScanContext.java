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

package org.complexityanalyzer.network.web.pack;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

public final class WebPackScanContext {
    final String token;
    final ObjectArrayList<JsonObject> tabs = new ObjectArrayList<>();
    final ConcurrentHashMap<String, WebResourceSource> sources = new ConcurrentHashMap<>();
    final Object2ObjectOpenHashMap<String, Object2ObjectOpenHashMap<String, String>> mimeTypesByNamespace = new Object2ObjectOpenHashMap<>();
    final ObjectOpenHashSet<String> seenTabIds = new ObjectOpenHashSet<>();

    public WebPackScanContext(String token) {
        this.token = token;
    }

    CabinResourceResolver.TabRegistry buildRegistry() {
        tabs.sort(Comparator.comparingInt(tab -> tab.has(WebTabManifest.PROP_ORDER)
                ? tab.get(WebTabManifest.PROP_ORDER).getAsInt() : WebTabManifest.DEFAULT_TAB_ORDER));
        return new CabinResourceResolver.TabRegistry(tabs, sources, mimeTypesByNamespace);
    }
}