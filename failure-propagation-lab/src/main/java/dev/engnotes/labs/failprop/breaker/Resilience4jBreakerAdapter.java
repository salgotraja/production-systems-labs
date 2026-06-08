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

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives a real Resilience4j {@code CircuitBreaker} under the simulation's synthetic clock.
 *
 * <p>Determinism contract: the window is count-based, automatic open-to-half-open transition is
 * disabled, and {@code waitDurationInOpenState} is set to a day so Resilience4j's own (wall)
 * clock can never trigger the lazy transition mid-run - this adapter performs the transition
 * manually when <em>synthetic</em> time passes the configured open duration, mirroring the
 * hand-rolled breaker exactly. Verified byte-stable by the determinism test; the library's
 * decision path here depends only on call counts and these manual transitions.
 */
public final class Resilience4jBreakerAdapter implements EdgeBreaker {

    private static final RuntimeException SYNTHETIC_FAILURE =
            new RuntimeException("synthetic failure (simulated timeout)");

    private final io.github.resilience4j.circuitbreaker.CircuitBreaker delegate;
    private final long openDurationMs;
    private final List<CircuitBreaker.Transition> transitions = new ArrayList<>();
    private CircuitBreaker.State lastState = CircuitBreaker.State.CLOSED;
    private double openedAtMs;

    public Resilience4jBreakerAdapter(String name, BreakerConfig config) {
        this.openDurationMs = config.openDurationMs();
        this.delegate = io.github.resilience4j.circuitbreaker.CircuitBreaker.of(name,
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(config.slidingWindowSize())
                        .minimumNumberOfCalls(config.minimumCalls())
                        .failureRateThreshold((float) config.failureRateThresholdPct())
                        .permittedNumberOfCallsInHalfOpenState(config.halfOpenProbes())
                        .waitDurationInOpenState(Duration.ofDays(1))
                        .automaticTransitionFromOpenToHalfOpenEnabled(false)
                        .build());
    }

    @Override
    public boolean allow(double nowMs) {
        if (lastState == CircuitBreaker.State.OPEN && nowMs >= openedAtMs + openDurationMs) {
            delegate.transitionToHalfOpenState();
            sync(nowMs);
        }
        boolean permitted = delegate.tryAcquirePermission();
        sync(nowMs);
        return permitted;
    }

    @Override
    public void onSuccess(double nowMs) {
        if (lastState == CircuitBreaker.State.OPEN) {
            return; // mirror the hand-rolled breaker: late results describe the past
        }
        delegate.onSuccess(0, TimeUnit.MILLISECONDS);
        sync(nowMs);
    }

    @Override
    public void onFailure(double nowMs) {
        if (lastState == CircuitBreaker.State.OPEN) {
            return; // mirror the hand-rolled breaker: late results describe the past
        }
        delegate.onError(0, TimeUnit.MILLISECONDS, SYNTHETIC_FAILURE);
        sync(nowMs);
    }

    @Override
    public List<CircuitBreaker.Transition> transitions() {
        return Collections.unmodifiableList(transitions);
    }

    private void sync(double nowMs) {
        CircuitBreaker.State current = map(delegate.getState());
        if (current != lastState) {
            transitions.add(new CircuitBreaker.Transition(nowMs, lastState, current));
            if (current == CircuitBreaker.State.OPEN) {
                openedAtMs = nowMs;
            }
            lastState = current;
        }
    }

    private static CircuitBreaker.State map(
            io.github.resilience4j.circuitbreaker.CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> CircuitBreaker.State.CLOSED;
            case OPEN -> CircuitBreaker.State.OPEN;
            case HALF_OPEN -> CircuitBreaker.State.HALF_OPEN;
            default -> throw new IllegalStateException("unexpected resilience4j state: " + state);
        };
    }
}
