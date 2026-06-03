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
package dev.engnotes.labs.backpressure.collapse;

/**
 * Aggregate outcome of one offered-load level in the collapse sweep.
 *
 * @param mode            {@code "no-retry"} or {@code "retry"}
 * @param offeredRps      original first-attempt arrival rate
 * @param effectiveRps    total attempts processed per second (offered + retries); shows amplification
 * @param idealGoodputRps {@code min(offeredRps, capacity)} - what a backpressured system would deliver
 * @param goodputRps      requests completed within the client deadline, per second
 * @param wastedPct       percentage of processed attempts that delivered nothing (served-late or rejected)
 * @param p50Ms           median sojourn of served requests
 * @param p99Ms           99th-percentile sojourn of served requests
 * @param avgQueueDepth   mean number of requests ahead of an arrival
 */
public record LoadLevelResult(
        String mode,
        int offeredRps,
        double effectiveRps,
        double idealGoodputRps,
        double goodputRps,
        double wastedPct,
        double p50Ms,
        double p99Ms,
        double avgQueueDepth) {
}
