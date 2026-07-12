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

package org.complexityanalyzer.export.cabin.api;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.nio.charset.StandardCharsets;

public final class StringPool {

    public static final int EMPTY = 0;
    private static final int MAX_LEN = 0xFFFF;
    private final Object2IntOpenHashMap<String> index;
    private final ObjectList<byte[]> entries;
    private long bytesEstimate;

    public StringPool(int initialCapacity) {
        this.index = new Object2IntOpenHashMap<>(initialCapacity);
        this.index.defaultReturnValue(-1);
        this.entries = new ObjectArrayList<>(initialCapacity);
        intern("");
    }

    private static String oversizedSurrogate(byte[] data) {
        return "[oversized:" + data.length + ":" + Long.toHexString(XxHash64.hash(data, 0L)) + "]";
    }

    public int intern(String s) {
        if (s == null) return EMPTY;
        int existing = index.getInt(s);
        if (existing >= 0) return existing;

        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_LEN) {
            String surrogate = oversizedSurrogate(data);
            int surExisting = index.getInt(surrogate);
            if (surExisting >= 0) return surExisting;
            return add(surrogate, surrogate.getBytes(StandardCharsets.UTF_8));
        }
        return add(s, data);
    }

    private int add(String key, byte[] data) {
        int ref = entries.size();
        entries.add(data);
        index.put(key, ref);
        bytesEstimate += data.length + 2L;
        return ref;
    }

    public int size() {
        return entries.size();
    }

    public long bytesEstimate() {
        return bytesEstimate + 4L;
    }

    public void writeTo(LeBuf buf) {
        int n = entries.size();
        buf.i32(n);
        for (int i = 0; i < n; i++) {
            byte[] data = entries.get(i);
            buf.u16(data.length);
            buf.bytes(data);
        }
    }
}