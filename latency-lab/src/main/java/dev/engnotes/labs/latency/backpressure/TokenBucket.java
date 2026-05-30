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

public final class TokenBucket implements BackpressureController {

    private final double capacity;
    private final double refillPerMs;
    private final long serviceTimeMs;
    private double tokens;
    private long lastRefillMs;

    public TokenBucket(long capacity, long refillPerSecond, long serviceTimeMs) {
        if (capacity < 1 || refillPerSecond < 1 || serviceTimeMs < 1) {
            throw new IllegalArgumentException("capacity, refillPerSecond, and serviceTimeMs must be >= 1");
        }
        this.capacity = capacity;
        this.refillPerMs = refillPerSecond / 1_000.0;
        this.serviceTimeMs = serviceTimeMs;
        this.tokens = capacity;
        this.lastRefillMs = 0L;
    }

    @Override
    public BackpressureStrategy strategy() {
        return BackpressureStrategy.TOKEN_BUCKET;
    }

    @Override
    public BackpressureDecision admit(long arrivalMs) {
        refill(arrivalMs);
        if (tokens < 1.0) {
            return BackpressureDecision.rejected();
        }
        tokens -= 1.0;
        return BackpressureDecision.accepted(serviceTimeMs);
    }

    @Override
    public int buffered() {
        return 0;
    }

    private void refill(long nowMs) {
        long elapsedMs = Math.max(0L, nowMs - lastRefillMs);
        tokens = Math.min(capacity, tokens + (elapsedMs * refillPerMs));
        lastRefillMs = nowMs;
    }
}
