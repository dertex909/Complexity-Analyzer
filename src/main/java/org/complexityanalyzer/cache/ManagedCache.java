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

package org.complexityanalyzer.cache;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;

public interface ManagedCache {

    String id();

    @Nullable
    Path file(MinecraftServer server);

    default boolean delete(MinecraftServer server) {
        var f = file(server);
        if (f == null) return false;
        try {
            return Files.deleteIfExists(f);
        } catch (Exception e) {
            return false;
        }
    }

    static void register(ManagedCache cache) {
        Registry.MAP.put(cache.id(), cache);
    }

    static Collection<ManagedCache> all() {
        return Collections.unmodifiableCollection(Registry.MAP.values());
    }

    static Collection<String> ids() {
        return Collections.unmodifiableCollection(Registry.MAP.keySet());
    }

    @Nullable
    static ManagedCache get(String id) {
        return Registry.MAP.get(id);
    }

    final class Registry {
        private static final Object2ObjectLinkedOpenHashMap<String, ManagedCache> MAP = new Object2ObjectLinkedOpenHashMap<>();

        static {
            register(RecipeGraphCache.INSTANCE);
            register(MachineRegistryCache.INSTANCE);
            register(BlockBreakCache.INSTANCE);
        }

        private Registry() {
        }
    }
}