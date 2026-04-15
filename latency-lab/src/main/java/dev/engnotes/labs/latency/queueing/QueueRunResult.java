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
package dev.engnotes.labs.latency.queueing;

import dev.engnotes.labs.commons.histogram.PercentileSnapshot;

import java.util.List;

/**
 * Aggregate result from one {@link QueueSimulator} run at a fixed arrival rate.
 *
 * <p>Captures both the per-interval percentile snapshots (sojourn time recorded as
 * latency) and the summary statistics needed to verify Little's Law:
 * {@code L = λ × W}.
 *
 * @param snapshots            per-interval ADR-005 snapshots (sojourn time as latency)
 * @param avgQueueDepth        time-averaged queue depth across the run
 * @param totalRejections      requests rejected because the queue was full
 * @param actualThroughputRps  steady-state completed requests per second
 * @param meanSojournMs        mean sojourn time (wait + service) in milliseconds
 */
public record QueueRunResult(
        List<PercentileSnapshot> snapshots,
        double avgQueueDepth,
        long totalRejections,
        double actualThroughputRps,
        double meanSojournMs) {}
