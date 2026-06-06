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

/**
 * Downstream rate over one aligned window: what the server sees per gate, against the offered
 * load. The token-bucket series spikes with the input; the leaky-bucket series stays at or below
 * the leak rate - shaping versus policing in one row.
 *
 * @param windowStartMs  window start, aligned to the window size
 * @param offeredRps     offered arrival rate in this window
 * @param tokenBucketRps rate released downstream by the token bucket in this window
 * @param leakyBucketRps rate released downstream by the leaky bucket in this window
 */
public record ShapingWindowSample(
        long windowStartMs,
        double offeredRps,
        double tokenBucketRps,
        double leakyBucketRps) {
}
