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
package dev.engnotes.labs.backpressure.shedding;

/**
 * Outcome of one shedding run (one policy at one offered load).
 *
 * <p>Goodput alone cannot rank the real policies - under sustained overload they all hold
 * roughly capacity. The ranking lives in {@code p99ServedMs} (what kind of latency the lucky
 * requests saw) and {@code shedWaitP50Ms} (how long the system typically held a request it
 * abandoned: 0 fast-fails at the door, around the deadline slow-fails at dequeue, and the
 * starved are never told - the median rather than the p99, which would only pick up the
 * end-of-window measurement tail).
 *
 * @param policy         shedding policy label
 * @param offeredRps     average offered load over the run
 * @param goodputRps     requests completed within the client deadline, per second
 * @param shedPct        percentage of arrivals abandoned (door rejects + dequeue discards + still queued at window end)
 * @param servedLatePct  percentage of arrivals served but past the deadline (wasted service)
 * @param p99ServedMs    99th-percentile sojourn of served requests (in-deadline or not)
 * @param shedWaitP50Ms  median time from arrival to abandonment for shed requests
 * @param wastedPct      share of consumed service time spent on served-late work
 */
public record ShedPointResult(
        String policy,
        double offeredRps,
        double goodputRps,
        double shedPct,
        double servedLatePct,
        double p99ServedMs,
        double shedWaitP50Ms,
        double wastedPct) {
}
