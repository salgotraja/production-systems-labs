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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Determinism pin: drive a Resilience4j CircuitBreaker with a fixed synthetic outcome
 * sequence (count-based window, automatic open-to-half-open transition disabled, manual
 * transitions only) and assert the observable behavior sequence is identical across runs.
 * If this holds, the library comparison can sit in a golden artifact; if not, it stays in
 * non-golden tests and the live mode.
 */
class Resilience4jDeterminismTest {

    private static List<String> drive() {
        io.github.resilience4j.circuitbreaker.CircuitBreaker breaker =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.of("spike",
                        CircuitBreakerConfig.custom()
                                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(20)
                                .minimumNumberOfCalls(10)
                                .failureRateThreshold(50.0f)
                                .waitDurationInOpenState(Duration.ofMillis(500))
                                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                                .permittedNumberOfCallsInHalfOpenState(1)
                                .build());

        List<String> log = new ArrayList<>();
        // 5 successes, then failures until the trip.
        for (int i = 0; i < 5; i++) {
            log.add("permit=" + breaker.tryAcquirePermission());
            breaker.onSuccess(10, TimeUnit.MILLISECONDS);
            log.add(breaker.getState().name());
        }
        for (int i = 0; i < 8; i++) {
            boolean permitted = breaker.tryAcquirePermission();
            log.add("permit=" + permitted);
            if (permitted) {
                breaker.onError(400, TimeUnit.MILLISECONDS, new RuntimeException("synthetic"));
            }
            log.add(breaker.getState().name());
        }
        // Manual half-open, one probe success, then a post-close failure burst.
        breaker.transitionToHalfOpenState();
        log.add(breaker.getState().name());
        log.add("permit=" + breaker.tryAcquirePermission());
        breaker.onSuccess(10, TimeUnit.MILLISECONDS);
        log.add(breaker.getState().name());
        for (int i = 0; i < 12; i++) {
            boolean permitted = breaker.tryAcquirePermission();
            log.add("permit=" + permitted);
            if (permitted) {
                breaker.onError(400, TimeUnit.MILLISECONDS, new RuntimeException("synthetic"));
            }
            log.add(breaker.getState().name());
        }
        return log;
    }

    @Test
    void behaviorSequenceIsIdenticalAcrossRuns() {
        List<String> first = drive();
        List<String> second = drive();
        assertEquals(first, second);
        System.out.println("R4J SPIKE LOG: " + first);
    }
}
