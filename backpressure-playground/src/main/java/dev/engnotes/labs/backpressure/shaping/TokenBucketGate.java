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
package dev.engnotes.labs.backpressure.shaping;

import java.util.OptionalDouble;

/**
 * Token-bucket rate gate: <em>policing</em>. Tokens refill continuously at the sustained rate
 * and bank up to {@code burstCapacity} during quiet periods. An arrival that finds a token is
 * released downstream <em>immediately</em> - the gate never delays admitted work, so a banked
 * burst passes through at line rate and any queueing it causes lands at the server.
 *
 * <p>{@code burstCapacity} is the design knob this experiment sweeps: it bounds how much of a
 * spike the gate hands to the server at once, and therefore the worst-case server wait.
 */
public final class TokenBucketGate implements RateGate {

    private final double burstCapacity;
    private final double refillPerMs;
    private double tokens;
    private double lastRefillMs;

    public TokenBucketGate(double ratePerSecond, int burstCapacity) {
        if (ratePerSecond <= 0 || burstCapacity < 1) {
            throw new IllegalArgumentException("ratePerSecond must be > 0 and burstCapacity >= 1");
        }
        this.burstCapacity = burstCapacity;
        this.refillPerMs = ratePerSecond / 1000.0;
        this.tokens = burstCapacity;
        this.lastRefillMs = 0.0;
    }

    @Override
    public OptionalDouble offer(double arrivalMs) {
        refill(arrivalMs);
        if (tokens < 1.0) {
            return OptionalDouble.empty();
        }
        tokens -= 1.0;
        return OptionalDouble.of(arrivalMs);
    }

    private void refill(double nowMs) {
        tokens = Math.min(burstCapacity, tokens + (nowMs - lastRefillMs) * refillPerMs);
        lastRefillMs = nowMs;
    }
}
