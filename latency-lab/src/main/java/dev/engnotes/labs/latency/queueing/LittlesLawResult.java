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

/**
 * Result of a Little's Law verification.
 *
 * <p>Little's Law: {@code L = λ × W}, where:
 * <ul>
 *   <li>{@code λ} (lambda) - steady-state throughput in requests/second</li>
 *   <li>{@code W} - mean sojourn time (wait + service) in seconds</li>
 *   <li>{@code L} - average number of requests in the system</li>
 * </ul>
 *
 * @param lambdaRps      measured throughput (requests per second)
 * @param meanSojournMs  measured mean sojourn time (milliseconds)
 * @param computedL      L computed via λ × W (W converted to seconds)
 * @param measuredL      L measured as time-averaged queue depth
 * @param relativeError  |computedL - measuredL| / measuredL (0.0 if measuredL == 0)
 */
public record LittlesLawResult(
        double lambdaRps,
        double meanSojournMs,
        double computedL,
        double measuredL,
        double relativeError) {}
