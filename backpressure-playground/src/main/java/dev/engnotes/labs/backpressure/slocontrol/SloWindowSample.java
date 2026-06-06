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
package dev.engnotes.labs.backpressure.slocontrol;

/**
 * The burst view, one aligned window at a time: per-class success rate by arrival window under
 * each policy. Through a spike, blind's critical line dives with everyone else's; priority's
 * critical line stays at ~100 while its background line pays the bill.
 *
 * @param windowStartMs         window start, aligned to the window size
 * @param blindCriticalPct      critical success rate under {@code blind} in this window
 * @param priorityCriticalPct   critical success rate under {@code priority} in this window
 * @param blindBackgroundPct    background success rate under {@code blind} in this window
 * @param priorityBackgroundPct background success rate under {@code priority} in this window
 */
public record SloWindowSample(
        long windowStartMs,
        double blindCriticalPct,
        double priorityCriticalPct,
        double blindBackgroundPct,
        double priorityBackgroundPct) {
}
