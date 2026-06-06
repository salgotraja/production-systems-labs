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
 * A rate gate placed in front of the server. Offered an arrival, it either rejects it
 * (fail-fast) or admits it and answers <em>when</em> the request is released downstream.
 *
 * <p>The release time is the whole design difference between the two gates in this experiment:
 * a token bucket (policing) releases admitted requests immediately, so bursts pass through and
 * the wait lands at the server; a leaky bucket (shaping) schedules releases at the leak rate, so
 * the downstream rate is flat and the wait lands at the gate.
 *
 * <p>Implementations are stateful and single-run: offer arrivals in non-decreasing time order
 * and use a fresh instance per run.
 */
public interface RateGate {

    /**
     * Offers one arrival to the gate.
     *
     * @param arrivalMs arrival time, non-decreasing across calls
     * @return the downstream release time (>= arrival), or empty if rejected
     */
    OptionalDouble offer(double arrivalMs);
}
