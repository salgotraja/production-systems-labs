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

public final class LoadShedder implements BackpressureController {

    private final int maxInFlight;
    private final long serviceTimeMs;
    private final Deque<Long> completions;

    public LoadShedder(int maxInFlight, long serviceTimeMs) {
        if (maxInFlight < 1 || serviceTimeMs < 1) {
            throw new IllegalArgumentException("maxInFlight and serviceTimeMs must be >= 1");
        }
        this.maxInFlight = maxInFlight;
        this.serviceTimeMs = serviceTimeMs;
        this.completions = new ArrayDeque<>(maxInFlight);
    }

    @Override
    public BackpressureStrategy strategy() {
        return BackpressureStrategy.LOAD_SHEDDER;
    }

    @Override
    public BackpressureDecision admit(long arrivalMs) {
        removeCompleted(arrivalMs);
        if (completions.size() >= maxInFlight) {
            return BackpressureDecision.rejected();
        }
        completions.addLast(arrivalMs + serviceTimeMs);
        return BackpressureDecision.accepted(serviceTimeMs);
    }

    @Override
    public int buffered() {
        return completions.size();
    }

    private void removeCompleted(long arrivalMs) {
        while (!completions.isEmpty() && completions.peekFirst() <= arrivalMs) {
            completions.removeFirst();
        }
    }
}
