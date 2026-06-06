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
 * The hangover view, one aligned window at a time: p99 sojourn of the requests each policy
 * completed in this window. After a burst, FIFO keeps serving stale backlog long after load
 * normalized; LIFO stays fresh throughout; the bounded policies cap the damage at the deadline.
 * A value of 0 means the policy completed nothing in the window.
 *
 * @param windowStartMs  window start, aligned to the window size
 * @param fifoP99Ms      p99 of work completed by {@code fifo} in this window
 * @param tailDropP99Ms  p99 of work completed by {@code tail-drop} in this window
 * @param expireP99Ms    p99 of work completed by {@code expire} in this window
 * @param lifoP99Ms      p99 of work completed by {@code lifo} in this window
 */
public record ShedWindowSample(
        long windowStartMs,
        double fifoP99Ms,
        double tailDropP99Ms,
        double expireP99Ms,
        double lifoP99Ms) {
}
