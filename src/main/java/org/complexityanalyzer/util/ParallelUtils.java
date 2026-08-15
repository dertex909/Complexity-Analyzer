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

import org.complexityanalyzer.core.ThreadPoolManager;

import java.util.concurrent.RecursiveAction;
import java.util.function.IntConsumer;

public final class ParallelUtils {

    private ParallelUtils() {
    }

    public static void forRange(int start, int end, int threshold, IntConsumer action) {
        if (end - start <= 0) return;
        if (end - start <= threshold) {
            for (int i = start; i < end; i++) action.accept(i);
        } else {
            ThreadPoolManager.getInstance().invokeParallel(() -> new ParallelRangeTask(start, end, threshold, action).invoke());
        }
    }

    private static final class ParallelRangeTask extends RecursiveAction {
        private final int start;
        private final int end;
        private final int threshold;
        private final IntConsumer action;

        ParallelRangeTask(int start, int end, int threshold, IntConsumer action) {
            this.start = start;
            this.end = end;
            this.threshold = threshold;
            this.action = action;
        }

        @Override
        protected void compute() {
            int length = end - start;
            if (length <= threshold) {
                for (int i = start; i < end; i++) action.accept(i);
            } else {
                int mid = (start + end) >>> 1;
                invokeAll(new ParallelRangeTask(start, mid, threshold, action), new ParallelRangeTask(mid, end, threshold, action));
            }
        }
    }
}