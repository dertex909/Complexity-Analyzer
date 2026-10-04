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

package org.complexityanalyzer.util;

import io.netty.util.ReferenceCounted;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

@FunctionalInterface
public interface Scope extends AutoCloseable {

    static Scope of(Runnable onClose) {
        return onClose::run;
    }

    static Scope empty() {
        return () -> {
        };
    }

    static Scope lock(Lock lock) {
        lock.lock();
        return lock::unlock;
    }

    static <T> Scope inProgress(Collection<T> collection, T element) {
        collection.add(element);
        return () -> collection.remove(element);
    }

    static Scope counter(AtomicInteger counter) {
        counter.incrementAndGet();
        return counter::decrementAndGet;
    }

    static Scope flag(AtomicBoolean flag) {
        flag.set(true);
        return () -> flag.set(false);
    }

    static Scope release(@Nullable ReferenceCounted rc) {
        return () -> {
            if (rc != null && rc.refCnt() > 0) rc.release();
        };
    }

    @Override
    void close();
}