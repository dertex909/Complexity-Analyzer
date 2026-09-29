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

package org.complexityanalyzer.export.format;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

import static java.util.Locale.ROOT;

public final class ExportFormats {

    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(
            new Snapshot(new Object2ObjectLinkedOpenHashMap<>(), ObjectLists.emptyList())
    );

    static {
        register(new JsonExportFormat());
        register(new CsvExportFormat());
    }

    private ExportFormats() {
    }

    public static void register(IExportFormat format) {
        if (format == null) return;
        String id = format.getId().toLowerCase(ROOT);

        SNAPSHOT.updateAndGet(current -> {
            var newMap = new Object2ObjectLinkedOpenHashMap<>(current.map);
            newMap.put(id, format);
            var newIds = ObjectLists.unmodifiable(new ObjectArrayList<>(newMap.keySet()));
            return new Snapshot(newMap, newIds);
        });
    }

    @Nullable
    public static IExportFormat get(String id) {
        return id == null ? null : SNAPSHOT.get().map.get(id.toLowerCase(ROOT));
    }

    public static ObjectList<String> getAvailableFormatIds() {
        return SNAPSHOT.get().ids;
    }

    private record Snapshot(Object2ObjectLinkedOpenHashMap<String, IExportFormat> map, ObjectList<String> ids) {
    }
}