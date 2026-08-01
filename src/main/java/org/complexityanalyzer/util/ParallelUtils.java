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