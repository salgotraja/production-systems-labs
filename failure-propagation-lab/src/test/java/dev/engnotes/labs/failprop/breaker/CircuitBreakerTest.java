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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the breaker state machine edge by edge. The trip arithmetic, the open-period gate, the
 * probe accounting, and the window reset are each asserted at their exact boundary - a breaker
 * that trips one call early or probes one call too many changes every downstream golden file.
 */
class CircuitBreakerTest {

    // 50% over a 10-call window, 5-call minimum, 500ms open, single probe.
    private static CircuitBreaker breaker() {
        return new CircuitBreaker(new BreakerConfig(50.0, 10, 5, 500L, 1));
    }

    private static void drive(CircuitBreaker breaker, double timeMs, boolean success) {
        assertTrue(breaker.allow(timeMs), "call should be allowed at " + timeMs);
        if (success) {
            breaker.onSuccess(timeMs);
        } else {
            breaker.onFailure(timeMs);
        }
    }

    @Test
    void staysClosedBelowTheMinimumCallCount() {
        CircuitBreaker breaker = breaker();
        for (int i = 0; i < 4; i++) {
            drive(breaker, i, false);
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(),
                "4 failures is 100% but below the 5-call minimum");
    }

    @Test
    void tripsExactlyAtThresholdAndMinimum() {
        CircuitBreaker breaker = breaker();
        // 5 successes, then failures: rate crosses 50% on the 5th failure (5/10).
        for (int i = 0; i < 5; i++) {
            drive(breaker, i, true);
        }
        for (int i = 5; i < 9; i++) {
            drive(breaker, i, false);
            assertEquals(CircuitBreaker.State.CLOSED, breaker.state(), "rate still below 50% at " + i);
        }
        drive(breaker, 9, false);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(), "5 failures of 10 = 50% = trip");
    }

    @Test
    void openRejectsUntilTheOpenPeriodElapses() {
        CircuitBreaker breaker = trippedAt(1_000.0);
        assertFalse(breaker.allow(1_000.0));
        assertFalse(breaker.allow(1_499.9));
        assertTrue(breaker.allow(1_500.0), "first call at openedAt + openDuration is the probe");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    void halfOpenAdmitsExactlyTheProbeBudget() {
        CircuitBreaker breaker = trippedAt(1_000.0);
        assertTrue(breaker.allow(1_500.0), "the probe");
        assertFalse(breaker.allow(1_500.0), "second concurrent call is not a probe");
        assertFalse(breaker.allow(1_600.0), "still waiting on the probe outcome");
    }

    @Test
    void probeSuccessClosesAndResetsTheWindow() {
        CircuitBreaker breaker = trippedAt(1_000.0);
        assertTrue(breaker.allow(1_500.0));
        breaker.onSuccess(1_510.0);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(0.0, breaker.failureRatePct(), "window must reset - old failures are history");
        // One fresh failure must not re-trip: the reset window is below the minimum again.
        drive(breaker, 1_520.0, false);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void probeFailureReopensAndReArmsTheTimer() {
        CircuitBreaker breaker = trippedAt(1_000.0);
        assertTrue(breaker.allow(1_500.0));
        breaker.onFailure(1_900.0); // probe timed out 400ms later
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allow(2_399.9), "timer re-armed from the probe failure, not the first trip");
        assertTrue(breaker.allow(2_400.0));
    }

    @Test
    void lateOutcomesDuringOpenAreIgnored() {
        CircuitBreaker breaker = trippedAt(1_000.0);
        breaker.onSuccess(1_100.0); // in-flight call from before the trip completes now
        breaker.onFailure(1_200.0);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allow(1_499.0), "late outcomes must not move the open deadline");
        assertTrue(breaker.allow(1_500.0));
    }

    @Test
    void slidingWindowEvictsTheOldestOutcome() {
        CircuitBreaker breaker = breaker();
        for (int i = 0; i < 5; i++) {
            drive(breaker, i, false); // 5 failures, still below... exactly at minimum: 100% >= 50%
        }
        // 5 failures IS the minimum and 100% rate - the breaker trips on the 5th.
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        // Fresh breaker: fill the window with 10 successes, then slide 5 failures in; the rate
        // is computed over the last 10 only.
        CircuitBreaker sliding = breaker();
        for (int i = 0; i < 10; i++) {
            drive(sliding, i, true);
        }
        for (int i = 10; i < 14; i++) {
            drive(sliding, i, false);
            assertEquals(CircuitBreaker.State.CLOSED, sliding.state(), "4/10 in window at " + i);
        }
        drive(sliding, 14, false);
        assertEquals(CircuitBreaker.State.OPEN, sliding.state(), "5/10 in window = 50%");
    }

    @Test
    void recordsEveryTransitionInOrder() {
        CircuitBreaker breaker = trippedAt(1_000.0);
        assertTrue(breaker.allow(1_500.0));
        breaker.onSuccess(1_510.0);

        List<CircuitBreaker.Transition> transitions = breaker.transitions();
        assertEquals(3, transitions.size());
        assertEquals(CircuitBreaker.State.OPEN, transitions.get(0).to());
        assertEquals(CircuitBreaker.State.HALF_OPEN, transitions.get(1).to());
        assertEquals(CircuitBreaker.State.CLOSED, transitions.get(2).to());
        assertEquals(1_000.0, transitions.get(0).timeMs());
        assertEquals(1_500.0, transitions.get(1).timeMs());
        assertEquals(1_510.0, transitions.get(2).timeMs());
    }

    /** A breaker driven to OPEN with its trip recorded at exactly {@code tripTimeMs}. */
    private static CircuitBreaker trippedAt(double tripTimeMs) {
        CircuitBreaker breaker = breaker();
        for (int i = 0; i < 4; i++) {
            drive(breaker, tripTimeMs - 10 + i, false);
        }
        drive(breaker, tripTimeMs, false);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        return breaker;
    }
}
