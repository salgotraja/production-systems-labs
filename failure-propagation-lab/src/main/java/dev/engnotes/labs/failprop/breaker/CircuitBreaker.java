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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hand-rolled circuit breaker: the full CLOSED / OPEN / HALF_OPEN state machine in one page of
 * arithmetic, with time passed in explicitly so the same class runs under the deterministic
 * simulation clock and under wall clock in the Javalin live mode.
 *
 * <p>Mechanics:
 * <ul>
 *   <li><strong>CLOSED</strong> - every call is allowed; outcomes feed a count-based sliding
 *       window. Once at least {@code minimumCalls} outcomes are recorded and the windowed
 *       failure rate reaches the threshold, the breaker opens.</li>
 *   <li><strong>OPEN</strong> - every call is rejected without touching the downstream (the
 *       fail-fast). Outcomes from calls still in flight when the breaker opened are ignored -
 *       they describe the past. After {@code openDurationMs} the next {@link #allow} begins
 *       the half-open trial.</li>
 *   <li><strong>HALF_OPEN</strong> - up to {@code halfOpenProbes} trial calls are allowed
 *       through; the rest stay rejected. All probes succeeding closes the breaker and resets
 *       the window; any probe failing reopens it and re-arms the timer.</li>
 * </ul>
 *
 * <p>The breaker never measures where a failure happened - only that calls on this edge are
 * failing. That is the point: it breaks on observed edge health, so it works the same whether
 * the slow thing is its direct callee or three hops down.
 *
 * <p>Not thread-safe by design: the simulation is single-threaded, and the live mode wraps the
 * instance in a synchronized adapter at the call site.
 */
public final class CircuitBreaker implements EdgeBreaker {

    /** Breaker states; the transition log records every change for the experiment's timeline. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** One state change, for timelines and assertions. */
    public record Transition(double timeMs, State from, State to) {}

    private final BreakerConfig config;
    private final boolean[] window;
    private final List<Transition> transitions = new ArrayList<>();

    private State state = State.CLOSED;
    private int windowCount;
    private int windowIndex;
    private int windowFailures;
    private double openedAtMs;
    private int probesIssued;
    private int probeSuccesses;

    public CircuitBreaker(BreakerConfig config) {
        this.config = config;
        this.window = new boolean[config.slidingWindowSize()];
    }

    /**
     * Gate a call at {@code nowMs}: {@code true} means proceed, {@code false} means fail fast.
     * The open-to-half-open transition happens here, on the first call after the open period -
     * a breaker with no traffic has nothing to probe with.
     */
    @Override
    public boolean allow(double nowMs) {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (nowMs < openedAtMs + config.openDurationMs()) {
                    yield false;
                }
                transition(nowMs, State.HALF_OPEN);
                probesIssued = 1;
                probeSuccesses = 0;
                yield true;
            }
            case HALF_OPEN -> {
                if (probesIssued < config.halfOpenProbes()) {
                    probesIssued++;
                    yield true;
                }
                yield false;
            }
        };
    }

    /** Report a successful call outcome observed at {@code nowMs}. */
    @Override
    public void onSuccess(double nowMs) {
        switch (state) {
            case CLOSED -> record(true);
            case HALF_OPEN -> {
                probeSuccesses++;
                if (probeSuccesses >= config.halfOpenProbes()) {
                    resetWindow();
                    transition(nowMs, State.CLOSED);
                }
            }
            case OPEN -> { /* late result from before the trip - describes the past, ignored */ }
        }
    }

    /** Report a failed call outcome (timeout or error response) observed at {@code nowMs}. */
    @Override
    public void onFailure(double nowMs) {
        switch (state) {
            case CLOSED -> {
                record(false);
                if (windowCount >= config.minimumCalls()
                        && failureRatePct() >= config.failureRateThresholdPct()) {
                    open(nowMs);
                }
            }
            case HALF_OPEN -> open(nowMs);
            case OPEN -> { /* late result from before the trip - describes the past, ignored */ }
        }
    }

    /** Current state. OPEN is reported until the first post-open call begins the trial. */
    public State state() {
        return state;
    }

    /** Windowed failure rate in percent; 0 until any outcome is recorded. */
    public double failureRatePct() {
        return windowCount == 0 ? 0.0 : 100.0 * windowFailures / windowCount;
    }

    /** Every state change so far, oldest first. */
    @Override
    public List<Transition> transitions() {
        return Collections.unmodifiableList(transitions);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void record(boolean success) {
        if (windowCount == window.length) {
            // Evict the oldest outcome before overwriting its slot.
            if (!window[windowIndex]) {
                windowFailures--;
            }
        } else {
            windowCount++;
        }
        window[windowIndex] = success;
        if (!success) {
            windowFailures++;
        }
        windowIndex = (windowIndex + 1) % window.length;
    }

    private void open(double nowMs) {
        openedAtMs = nowMs;
        transition(nowMs, State.OPEN);
    }

    private void resetWindow() {
        windowCount = 0;
        windowIndex = 0;
        windowFailures = 0;
    }

    private void transition(double nowMs, State to) {
        transitions.add(new Transition(nowMs, state, to));
        state = to;
    }
}
