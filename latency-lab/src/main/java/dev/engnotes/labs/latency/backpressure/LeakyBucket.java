/*
 * Copyright 2026 engnotes.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.engnotes.labs.latency.backpressure;

import java.util.ArrayDeque;
import java.util.Deque;

public final class LeakyBucket implements BackpressureController {

    private final int capacity;
    private final long leakIntervalMs;
    private final Deque<Long> completionTimes;
    private long nextLeakMs;

    public LeakyBucket(int capacity, long leakPerSecond) {
        if (capacity < 1 || leakPerSecond < 1) {
            throw new IllegalArgumentException("capacity and leakPerSecond must be >= 1");
        }
        this.capacity = capacity;
        this.leakIntervalMs = Math.max(1L, 1_000L / leakPerSecond);
        this.completionTimes = new ArrayDeque<>(capacity);
        this.nextLeakMs = 0L;
    }

    @Override
    public BackpressureStrategy strategy() {
        return BackpressureStrategy.LEAKY_BUCKET;
    }

    @Override
    public BackpressureDecision admit(long arrivalMs) {
        removeCompleted(arrivalMs);
        if (completionTimes.size() >= capacity) {
            return BackpressureDecision.rejected();
        }
        long completionMs = Math.max(arrivalMs, nextLeakMs) + leakIntervalMs;
        nextLeakMs = completionMs;
        completionTimes.addLast(completionMs);
        return BackpressureDecision.accepted(completionMs - arrivalMs);
    }

    @Override
    public int buffered() {
        return completionTimes.size();
    }

    private void removeCompleted(long arrivalMs) {
        while (!completionTimes.isEmpty() && completionTimes.peekFirst() <= arrivalMs) {
            completionTimes.removeFirst();
        }
    }
}
