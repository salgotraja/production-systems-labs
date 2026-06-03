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

import dev.engnotes.labs.commons.cli.CliArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * Sweeps offered load from well below to well above server capacity, with and without client
 * retries, and reports goodput at each level. The sweep reveals the collapse cliff: goodput
 * tracks offered load up to capacity, then falls <em>below</em> capacity instead of plateauing
 * there, because an unmanaged queue serves requests whose clients have already given up.
 *
 * <p>The model constants below are chosen deliberately for a legible, synthetic demonstration
 * (the experiment is illustrative, not a fit to any real service):
 * <ul>
 *   <li>{@code SERVICE_TIME_MS = 10} gives a round capacity of mu = 100 rps.</li>
 *   <li>{@code QUEUE_CAPACITY = 100_000} is effectively unbounded for the run window, so the
 *       collapse is driven by deadline-exceeded work, not queue-full rejection. (A small queue
 *       with fast rejection is itself a form of admission control - that is Post 4's topic, not
 *       this one.)</li>
 *   <li>{@code CLIENT_DEADLINE_MS = 200} is 20x the service time, so a request only misses its
 *       deadline once the backlog is deep - the signature of sustained overload.</li>
 *   <li>{@code MAX_RETRIES = 3} with {@code RETRY_BACKOFF_MS = 50} models an ordinary client
 *       retry policy; it is enough to turn the overload into a visible death spiral.</li>
 * </ul>
 * Below capacity, no request times out, so retries never fire and the two modes coincide. They
 * diverge only past capacity, which is exactly where the lesson lives.
 */
public final class CollapseScenario {

    private static final long SERVICE_TIME_MS = 10L;
    private static final int QUEUE_CAPACITY = 100_000;
    private static final long CLIENT_DEADLINE_MS = 200L;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 50L;

    /** Offered load per level, in rps. Capacity is 100 rps, so this spans 0.25x to 3x. */
    private static final int[] OFFERED_RPS_LEVELS = {25, 50, 75, 90, 100, 110, 125, 150, 200, 300};

    /**
     * Runs the full sweep for the window length in {@code args}.
     *
     * @return no-retry levels first, then retry levels, each ascending by offered load
     */
    public CollapseRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());
        CollapseSimulator simulator = new CollapseSimulator(
                SERVICE_TIME_MS, QUEUE_CAPACITY, CLIENT_DEADLINE_MS, MAX_RETRIES, RETRY_BACKOFF_MS);

        List<LoadLevelResult> levels = new ArrayList<>(OFFERED_RPS_LEVELS.length * 2);
        for (boolean retriesEnabled : new boolean[] {false, true}) {
            for (int offeredRps : OFFERED_RPS_LEVELS) {
                levels.add(simulator.run(offeredRps, durationMs, retriesEnabled));
            }
        }
        return new CollapseRunResult(simulator.serverCapacityRps(), levels);
    }
}
