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
package dev.engnotes.labs.failprop.breaker;

/**
 * A propagated deadline: the request carries an absolute budget of {@code deadlineMs} from its
 * arrival, and every hop refuses to start a downstream call it cannot finish in time. Because
 * the deadline travels <em>with</em> the request rather than being a fresh per-call timeout at
 * each hop, even an abandoned subtree (whose caller has already given up) self-terminates at the
 * deadline instead of running on - which is the wasted work Post 2's uncoordinated timeouts
 * leave behind.
 *
 * @param deadlineMs total budget from a request's arrival; a hop will not start a call once the
 *                   request is this old
 * @param floorMs    minimum remaining budget worth starting a call with: below it, a call could
 *                   not complete usefully, so the hop fails fast rather than spawn a doomed call
 */
public record TimeoutBudget(long deadlineMs, long floorMs) {

    public TimeoutBudget {
        if (deadlineMs <= 0) {
            throw new IllegalArgumentException("deadlineMs must be > 0");
        }
        if (floorMs < 0 || floorMs >= deadlineMs) {
            throw new IllegalArgumentException("floorMs must be in [0, deadlineMs)");
        }
    }
}
